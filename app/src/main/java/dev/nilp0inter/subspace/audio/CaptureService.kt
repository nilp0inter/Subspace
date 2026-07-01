package dev.nilp0inter.subspace.audio

import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Identifier for the capture input source the service starts its recorder with.
 *
 * Replaces the per-source recorder classes ([InMemoryRecorder],
 * [PhoneMicRecorder]) — `AudioSource` becomes a parameter of one recorder,
 * not a separate class. See `capture-service` design D3.
 */
enum class CaptureSourceId {
    /** Bluetooth SCO communication route. Maps to `VOICE_COMMUNICATION`. */
    VoiceCommunication,

    /** Phone fallback when no SCO device is available. Maps to `MIC`. */
    Mic,
}

/**
 * A capture input source opened by [CaptureService.startSession].
 *
 * Implementations wrap `AudioRecord` construction for a given
 * [CaptureSourceId] (production) or a fake PCM stream (tests).
 */
interface CaptureSource {
    val sourceId: CaptureSourceId

    /**
     * Open the source for recording. Returns null if the source cannot be
     * opened (hardware absent, sample-rate negotiation failed, etc.).
     */
    suspend fun open(): OpenedCaptureSource?
}

/**
 * A capture source that has been opened and is ready to be read.
 *
 * Ownership transfers to the caller, which must [close] it exactly once.
 */
interface OpenedCaptureSource {
    val sampleRate: Int

    /** Suggested read buffer size in samples (typ. `minBuffer / 2`). */
    val bufferSizeShorts: Int

    /**
     * Read up to [buffer.size] samples into [buffer]. Returns the number of
     * samples read, or a value `<= 0` to indicate "no data right now"
     * (the read loop will retry).
     */
    fun read(buffer: ShortArray): Int

    /** Stop and release underlying resources. Idempotent. */
    fun close()
}

/**
 * Typed outcome of [CaptureService.startSession].
 *
 * The capture service enforces the single-session invariant and the
 * acquire → beep → record sequencing; controllers branch on these outcomes.
 */
sealed interface CaptureStartResult {
    /** A new capture session is running. Hold [session] and call [CaptureSession.stop] on release. */
    data class Started(val session: CaptureSession) : CaptureStartResult

    /** Rejected: another session is already active. */
    data object SessionActive : CaptureStartResult

    /** SCO route could not be acquired. */
    data object ScoUnavailable : CaptureStartResult

    /**
     * The session was cancelled before recording began (PTT released during
     * SCO acquisition or the ready beep). The SCO route is left warm for the
     * configured retention window per the `sco-audio` spec.
     */
    data object Cancelled : CaptureStartResult

    /** The selected [CaptureSource] could not be opened. */
    data object RecordingFailed : CaptureStartResult
}

/**
 * How a [CaptureSession] ended. Reported via [CaptureSession.completion].
 */
sealed interface CaptureCompletion {
    val recordedPcm: RecordedPcm

    /** The session reached the 60-second maximum capture duration. */
    data class MaxDuration(override val recordedPcm: RecordedPcm) : CaptureCompletion

    /** [CaptureSession.stop] was called. */
    data class Stopped(override val recordedPcm: RecordedPcm) : CaptureCompletion

    /** [CaptureService.cancelSession] was called. */
    data class Cancelled(override val recordedPcm: RecordedPcm) : CaptureCompletion
}

/**
 * A running capture session returned by [CaptureService.startSession].
 *
 * Exposes both:
 *  - [frames]: a hot stream of PCM chunks as read from the loop, for live
 *    consumers (level meter, journal WAV writer, future streaming channels).
 *  - [stop]: returns the complete buffered capture (up to 60s), for
 *    consumers that operate on the whole capture (echo playback, STT).
 */
interface CaptureSession {
    /** Live PCM frames. `DROP_OLDEST` overflow — a slow subscriber never backpressures the read loop. */
    val frames: SharedFlow<ShortArray>

    /**
     * Completes with the capture outcome when the session ends
     * (max-duration, stop, or cancel). The PCM inside is identical to what
     * [stop] returns.
     */
    val completion: Deferred<CaptureCompletion>

    /**
     * The sample rate negotiated by the opened capture source for this
     * session. Consumers that write capture-derived artifacts (WAV headers,
     * metadata) MUST read this value rather than assuming a hardcoded rate,
     * because [AndroidCaptureSource.selectSampleRate] may negotiate 8 kHz or
     * 16 kHz depending on the route.
     */
    val sampleRate: Int

    /**
     * Stop the session (if still running) and return the captured PCM.
     * Idempotent: subsequent calls return the same captured samples.
     */
    suspend fun stop(): RecordedPcm
}

/**
 * Unified PTT audio capture service.
 *
 * Owns the single active [AudioRecord] (via an [OpenedCaptureSource]) for the
 * whole app. Enforces the half-duplex PTT invariant: at most one capture
 * session may be active at a time (design D1, D7).
 *
 * Centralizes the `acquire SCO → play ready beep → start capture` sequence
 * that was previously duplicated across channel controllers (design D6),
 * and exposes [isCapturing] + [level] as the unified transmit-state and
 * audio-level signals (design D4).
 */
class CaptureService(
    private val scope: CoroutineScope,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val maxBufferSamplesFactor: Int = DEFAULT_BUFFER_FACTOR,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val mutex = Mutex()
    private var active: ActiveSession? = null

    /**
     * Start a capture session.
     *
     * Performs: single-session assertion → acquire [sco] → check [shouldProceed]
     * → play ready beep on [output] (cold-start priming from [ScoRoute.coldStart])
     * → open [source] → start the read loop.
     *
     * The caller-supplied [shouldProceed] predicate is checked after SCO
     * acquisition and after the beep — returning false in either place
     * produces [CaptureStartResult.Cancelled] (short-tap-during-beep /
     * release-during-sco-acquisition per `sco-audio` spec).
     */
    suspend fun startSession(
        source: CaptureSource,
        sco: ScoRoute,
        output: PcmOutput,
        shouldProceed: () -> Boolean,
    ): CaptureStartResult = mutex.withLock {
        if (active != null) return@withLock CaptureStartResult.SessionActive

        var opened: OpenedCaptureSource? = null
        try {
            if (!sco.acquire()) return@withLock CaptureStartResult.ScoUnavailable

            // PTT released during SCO acquisition: keep SCO warm, no beep, no record.
            // The service owns the SCO release on this failure branch — controllers
            // MUST NOT also release SCO here (capture-service / sco-audio specs).
            if (!shouldProceed()) {
                sco.release()
                return@withLock CaptureStartResult.Cancelled
            }

            output.playReadyBeep(sco.coldStart)

            // PTT released during the ready beep: no record, retain warm SCO.
            // Service-owned release; controllers MUST NOT release SCO here.
            if (!shouldProceed()) {
                sco.release()
                return@withLock CaptureStartResult.Cancelled
            }

            opened = source.open() ?: run {
                // Source open failed after the beep — service releases the SCO
                // reference it acquired above so the warmup window starts and
                // the route is not leaked for the rest of the session.
                sco.release()
                return@withLock CaptureStartResult.RecordingFailed
            }

            val session = ActiveSession(
                scope = scope,
                opened = opened,
                coldStart = sco.coldStart,
                readDispatcher = readDispatcher,
                maxDurationMs = maxDurationMs,
                maxBufferSamplesFactor = maxBufferSamplesFactor,
                clock = clock,
                onCaptureSignalChange = { capturing ->
                    if (!capturing) {
                        _level.value = 0f
                    }
                    _isCapturing.value = capturing
                },
                onLevelUpdate = { rms -> _level.value = rms },
                // Synchronous finalize hook: clears the service's `active`
                // reference inside `finalizeLock` so a rapid re-press after
                // `stop()` / `cancelSession()` is never rejected as
                // `SessionActive`. The identity check (`active === session`)
                // is performed inside `finalize()` so this lambda is safe to
                // call even after a new session has taken `active`.
                onFinalize = { finalizedSession ->
                    if (active === finalizedSession) active = null
                },
            )
            opened = null // ownership transferred
            active = session
            _isCapturing.value = true
            CaptureStartResult.Started(session)
        } catch (cancellation: CancellationException) {
            opened?.close()
            throw cancellation
        }
    }

    /**
     * Cancel the active session (if any) and release internal state.
     *
     * Used by controller `cancelAndRelease` paths (mode switch, service
     * teardown). Does NOT release the SCO route — the caller owns that
     * lifecycle (mirrors the legacy recorder pattern).
     *
     * Returns the captured PCM (or an empty result if no session was active).
     */
    suspend fun cancelSession(session: CaptureSession): RecordedPcm {
        val pcm = (session as? ActiveSession)?.cancel() ?: RecordedPcm(shortArrayOf(), DEFAULT_SAMPLE_RATE)
        return pcm
    }

    /** Cancel any active session regardless of the caller. */
    suspend fun cancelActiveSession(): RecordedPcm? {
        val current = mutex.withLock {
            val a = active
            active = null
            a
        } ?: return null
        val pcm = current.cancel()
        _isCapturing.value = false
        _level.value = 0f
        return pcm
    }

    private class ActiveSession(
        private val scope: CoroutineScope,
        private val opened: OpenedCaptureSource,
        private val coldStart: Boolean,
        private val readDispatcher: CoroutineDispatcher,
        private val maxDurationMs: Long,
        private val maxBufferSamplesFactor: Int,
        private val clock: () -> Long,
        private val onCaptureSignalChange: (Boolean) -> Unit,
        private val onLevelUpdate: (Float) -> Unit,
        private val onFinalize: (ActiveSession) -> Unit,
    ) : CaptureSession {
        override val frames: SharedFlow<ShortArray>
        override val completion: Deferred<CaptureCompletion>
        override val sampleRate: Int = opened.sampleRate

        private val buffer = mutableListOf<Short>()
        private val bufferLock = Any()
        private val finalizeLock = Any()
        @Volatile private var finalized: RecordedPcm? = null
        private val _completion = CompletableDeferred<CaptureCompletion>()

        private val readJob: Job

        init {
            val mutableFrames = MutableSharedFlow<ShortArray>(
                replay = 0,
                // DROP_OLDEST requires a positive buffer; one slot keeps the
                // most recent chunk available to a slow subscriber without
                // ever backpressuring the read loop.
                extraBufferCapacity = 1,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
            )
            frames = mutableFrames.asSharedFlow()
            completion = _completion
            val sampleRate = opened.sampleRate
            val maxBufferSamples = sampleRate * maxBufferSamplesFactor
            readJob = scope.launch(readDispatcher) {
                readLoop(mutableFrames, maxBufferSamples)
            }
        }

        private suspend fun readLoop(
            emitter: MutableSharedFlow<ShortArray>,
            maxBufferSamples: Int,
        ) {
            val readBuffer = ShortArray(opened.bufferSizeShorts.coerceAtLeast(1))
            val startedAt = clock()
            try {
                while (scope.isActive && finalized == null) {
                    if (clock() - startedAt >= maxDurationMs) {
                        finalize(CaptureCompletion.MaxDuration(readFinalPcm()))
                        return
                    }
                    val read = opened.read(readBuffer)
                    if (read > 0) {
                        val chunk = readBuffer.copyOfRange(0, read)
                        accumulate(chunk, maxBufferSamples)
                        emitter.tryEmit(chunk)
                        onLevelUpdate(computeRms(chunk))
                    }
                    // Tick virtual time forward so test dispatchers can drive
                    // the loop and the max-duration check deterministically.
                    // In production this is a no-op-ish 1ms pause between
                    // chunks; [android.media.AudioRecord.read] already blocks
                    // the IO thread until the next chunk is available.
                    delay(1)
                }
            } finally {
                opened.close()
            }
        }

        private fun accumulate(chunk: ShortArray, maxBufferSamples: Int) {
            synchronized(bufferLock) {
                val remaining = maxBufferSamples - buffer.size
                if (remaining <= 0) return
                val toCopy = minOf(chunk.size, remaining)
                for (i in 0 until toCopy) buffer += chunk[i]
            }
        }

        private fun computeRms(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) {
                val n = s.toDouble() / Short.MAX_VALUE
                sum += n * n
            }
            return sqrt(sum / samples.size).toFloat()
        }

        private fun readFinalPcm(): RecordedPcm = synchronized(bufferLock) {
            RecordedPcm(buffer.toShortArray(), opened.sampleRate)
        }

        private fun finalize(reason: CaptureCompletion): RecordedPcm {
            val pcm = reason.recordedPcm
            synchronized(finalizeLock) {
                if (finalized != null) return finalized!!
                finalized = pcm
                onFinalize(this)
                onCaptureSignalChange(false)
                _completion.complete(reason)
            }
            readJob.cancel()
            return pcm
        }

        override suspend fun stop(): RecordedPcm {
            val existing = finalized
            if (existing != null) return existing
            return finalize(CaptureCompletion.Stopped(readFinalPcm())).also {
                // Ensure the read loop has unwound before returning so the
                // caller observes a quiescent source (matches the legacy
                // recorder's synchronous stop semantics).
                runCatching { readJob.join() }
            }
        }

        suspend fun cancel(): RecordedPcm {
            val existing = finalized
            if (existing != null) return existing
            return finalize(CaptureCompletion.Cancelled(readFinalPcm())).also {
                runCatching { readJob.join() }
            }
        }
    }

    companion object {
        /** Default negotiated PCM rate. The Android source negotiates 16k or 8k. */
        const val DEFAULT_SAMPLE_RATE: Int = 16_000

        /** Maximum capture duration per session (spec: Maximum capture duration). */
        const val DEFAULT_MAX_DURATION_MS: Long = 60_000L

        /** Buffer cap factor (seconds worth of samples retained for terminal `stop()`). */
        const val DEFAULT_BUFFER_FACTOR: Int = 60
    }
}
