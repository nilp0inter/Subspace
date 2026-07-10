package dev.nilp0inter.subspace.audio

import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /** Internal startup facts used by the audio input subsystem before channel handoff. */
    val startupEvidence: CaptureStartupEvidence
        get() = CaptureStartupEvidence()

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
    data class Started(
        val session: CaptureSession,
        val evidence: CaptureStartupEvidence,
    ) : CaptureStartResult

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

    /** Android reported that the opened recorder is silenced before channel handoff. */
    data class RecordingSilenced(val evidence: CaptureStartupEvidence) : CaptureStartResult
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
    private var active: CaptureSessionImpl? = null

    /**
     * Start a capture session.
     *
     * Performs: single-session assertion → acquire [sco] → check [shouldProceed]
     * → open [source] for capture preflight → play ready beep on [output]
     * (cold-start priming from [ScoRoute.coldStart]) → start the read loop.
     *
     * The caller-supplied [shouldProceed] predicate is checked after SCO
     * acquisition, after capture preflight, and after the beep — returning
     * false produces [CaptureStartResult.Cancelled] (short-tap-during-beep /
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
        var routeAcquired = false
        var preCommitDrain: Job? = null
        suspend fun cleanupSetup() = withContext(NonCancellable) {
            preCommitDrain?.cancelAndJoin()
            preCommitDrain = null
            val sourceToClose = opened
            opened = null
            runCatching { sourceToClose?.close() }
            if (routeAcquired) {
                routeAcquired = false
                sco.release()
            }
        }

        suspend fun startPreCommitDrain(source: OpenedCaptureSource) {
            val started = CompletableDeferred<Unit>()
            preCommitDrain = scope.launch(readDispatcher, start = CoroutineStart.ATOMIC) {
                started.complete(Unit)
                val discardBuffer = ShortArray(source.bufferSizeShorts.coerceAtLeast(1))
                while (currentCoroutineContext().isActive) {
                    source.read(discardBuffer)
                    // AudioRecord.read may return no data; this also provides a
                    // cancellation checkpoint after every discard attempt.
                    delay(1)
                }
            }
            started.await()
        }

        try {
            if (!sco.acquire()) return@withLock CaptureStartResult.ScoUnavailable
            routeAcquired = true

            // PTT released during SCO acquisition: keep SCO warm, no beep, no record.
            // The service owns the SCO release on this failure branch — controllers
            // MUST NOT also release SCO here (capture-service / sco-audio specs).
            if (!shouldProceed()) {
                cleanupSetup()
                return@withLock CaptureStartResult.Cancelled
            }

            opened = source.open()
            if (opened == null) {
                cleanupSetup()
                return@withLock CaptureStartResult.RecordingFailed
            }
            val evidence = opened!!.startupEvidence.copy(
                recorderOpened = true,
                sourceId = source.sourceId,
                sampleRate = opened!!.sampleRate,
            )
            if (evidence.clientSilenced == true) {
                cleanupSetup()
                return@withLock CaptureStartResult.RecordingSilenced(evidence)
            }

            // PTT released after capture preflight but before the ready beep:
            // no record, no ready signal, and no channel-visible frames.
            if (!shouldProceed()) {
                cleanupSetup()
                return@withLock CaptureStartResult.Cancelled
            }

            // AudioRecord is started during open() so startup failures happen before
            // the user hears readiness. Drain it exclusively until the beep ends.
            startPreCommitDrain(opened!!)
            output.playReadyBeep(sco.coldStart)

            // Stop and join the discard reader before handing the source to the
            // channel-visible session. No pre-beep samples can cross this boundary.
            preCommitDrain?.cancelAndJoin()
            preCommitDrain = null

            // PTT released during the ready beep: no record and no user audio
            // delivery. The opened recorder is discarded before read-loop handoff.
            if (!shouldProceed()) {
                cleanupSetup()
                return@withLock CaptureStartResult.Cancelled
            }

            val session = CaptureSessionImpl(
                scope = scope,
                opened = opened!!,
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
            routeAcquired = false // route lifecycle remains with the caller
            active = session
            _isCapturing.value = true
            CaptureStartResult.Started(session, evidence)
        } catch (cancellation: CancellationException) {
            cleanupSetup()
            throw cancellation
        } catch (error: Throwable) {
            cleanupSetup()
            CaptureStartResult.RecordingFailed
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
        val pcm = (session as? CaptureSessionImpl)?.cancel() ?: RecordedPcm(shortArrayOf(), DEFAULT_SAMPLE_RATE)
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

    companion object {
        /** Default negotiated PCM rate. The Android source negotiates 16k or 8k. */
        const val DEFAULT_SAMPLE_RATE: Int = 16_000

        /** Maximum capture duration per session (spec: Maximum capture duration). */
        const val DEFAULT_MAX_DURATION_MS: Long = 60_000L

        /** Buffer cap factor (seconds worth of samples retained for terminal `stop()`). */
        const val DEFAULT_BUFFER_FACTOR: Int = 60
    }
}
