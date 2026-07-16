package dev.nilp0inter.subspace.service

import android.content.Context
import dev.nilp0inter.subspace.audio.DefaultTextToSpeechFactory
import dev.nilp0inter.subspace.audio.ModelVerifier
import dev.nilp0inter.subspace.audio.NavigationTtsEngine
import dev.nilp0inter.subspace.audio.NavigationTtsFailure
import dev.nilp0inter.subspace.audio.OggEncoder
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.PrepareResult
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.StateLossCallback
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.TranscriptionService
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.channel.JournalController
import dev.nilp0inter.subspace.channel.SleepwalkerTextOutputService
import dev.nilp0inter.subspace.channel.TextOutputAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.JournalStorageCapabilityAdapter
import dev.nilp0inter.subspace.channel.capability.SpeechSynthesisParameters
import dev.nilp0inter.subspace.channel.capability.SynthesisCapabilityAdapter
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapabilityAdapter
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.SynthesisCapability
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapability
import dev.nilp0inter.subspace.channel.capability.JournalStorageCapability
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.JournalProviderConfigurationCodec
import dev.nilp0inter.subspace.model.SttModelStatus
import dev.nilp0inter.subspace.model.TtsModelStatus
import dev.nilp0inter.subspace.model.TtsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Narrow status-update callback. The initializer publishes model and TTS
 * status changes through this lambda so the service can fold them into its
 * [MonitorState] projection. No [dev.nilp0inter.subspace.model.AppState]
 * reference is exposed.
 */
internal fun interface ModelStatusSink {
    fun publish(update: ModelStatusUpdate)
}

/**
 * Batched model/controller status update. Only non-null fields are applied.
 */
internal data class ModelStatusUpdate(
    val sttModelStatus: SttModelStatus? = null,
    val ttsModelStatus: TtsModelStatus? = null,
    val ttsStatus: TtsStatus? = null,
)

/** Constructs the JNI STT transcriber and its model directory. */
internal fun interface SttFactory {
    fun create(nativeLibraryDir: String, modelDir: File): SttTranscriber?
}

/** Constructs the JNI TTS synthesizer and its model directory. */
internal fun interface TtsFactory {
    fun create(nativeLibraryDir: String, modelDir: File): TtsSynthesizer?
}

/** Constructs the host diagnostic [TtsController] for a prepared synthesizer. */
internal fun interface TtsControllerFactory {
    fun create(
        scope: CoroutineScope,
        synthesizer: TtsSynthesizer,
        play: suspend (RecordedPcm) -> Boolean,
    ): TtsController
}

/** Constructs a [TranscriptionService] wrapping a transcriber. */
internal fun interface TranscriptionServiceFactory {
    fun create(transcriber: SttTranscriber): TranscriptionService
}

/** Supplies a fallback [PcmTranscriber] when STT is unavailable. */
internal fun interface PcmTranscriberFallback {
    fun create(): PcmTranscriber
}

/** Constructs the Android navigation TTS engine. */
internal fun interface NavigationTtsEngineFactory {
    fun create(context: Context, stateLossCallback: StateLossCallback): NavigationTtsEngine
}

/** Constructs the journal controller from a transcriber. */
internal fun interface JournalControllerFactory {
    fun create(scope: CoroutineScope, transcriber: PcmTranscriber): JournalController
}

/** Factory for the STT model-status poller job. */
internal fun interface SttPollerFactory {
    fun create(
        transcriber: SttTranscriber,
        scope: CoroutineScope,
        pollMs: Long,
        onStatus: (SttModelStatus) -> Unit,
    ): Job
}

/** Factory for the TTS model-status and controller-status poller jobs. */
internal fun interface TtsPollerFactory {
    fun create(
        synthesizer: TtsSynthesizer,
        controller: TtsController,
        scope: CoroutineScope,
        pollMs: Long,
        onModelStatus: (TtsModelStatus) -> Unit,
        onTtsStatus: (TtsStatus) -> Unit,
    ): Job
}

// ---- Production default factories ----

internal object DefaultSttFactory : SttFactory {
    override fun create(nativeLibraryDir: String, modelDir: File): SttTranscriber? {
        return try {
            dev.nilp0inter.subspace.audio.ParakeetJniTranscriber(
                nativeLibDir = nativeLibraryDir,
                modelDir = modelDir.absolutePath,
            )
        } catch (err: Throwable) {
            SubspaceLogger.w(TAG, "STT transcriber unavailable: ${err.message}")
            null
        }
    }
}

internal object DefaultTtsFactory : TtsFactory {
    override fun create(nativeLibraryDir: String, modelDir: File): TtsSynthesizer? {
        return try {
            dev.nilp0inter.subspace.audio.SupertonicJniSynthesizer(
                nativeLibDir = nativeLibraryDir,
                modelDir = modelDir.absolutePath,
            )
        } catch (err: Throwable) {
            SubspaceLogger.w(TAG, "TTS synthesizer unavailable: ${err.message}")
            null
        }
    }
}

internal val DefaultTtsControllerFactory = TtsControllerFactory { scope, synthesizer, play ->
    TtsController(scope = scope, synthesizer = synthesizer, play = play)
}

internal val DefaultTranscriptionServiceFactory = TranscriptionServiceFactory { transcriber ->
    TranscriptionService(transcriber)
}

internal val DefaultPcmTranscriberFallback = PcmTranscriberFallback {
    object : PcmTranscriber {
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
            throw IllegalStateException("STT transcriber unavailable")
        }
    }
}

internal val DefaultNavigationTtsEngineFactory = NavigationTtsEngineFactory { context, stateLossCallback ->
    NavigationTtsEngine(
        context = context,
        factory = DefaultTextToSpeechFactory(context),
        stateLossCallback = stateLossCallback,
    )
}

internal val DefaultJournalControllerFactory = JournalControllerFactory { scope, transcriber ->
    JournalController(
        scope = scope,
        encoder = OggEncoder(),
        transcriber = transcriber,
    )
}

internal val DefaultSttPollerFactory = SttPollerFactory { transcriber, scope, pollMs, onStatus ->
    scope.launch {
        var lastStatus: SttModelStatus? = null
        while (true) {
            val status = transcriber.modelStatus
            if (status != lastStatus) {
                lastStatus = status
                onStatus(status)
            }
            delay(pollMs)
        }
    }
}

internal val DefaultTtsPollerFactory = TtsPollerFactory { synthesizer, controller, scope, pollMs, onModelStatus, onTtsStatus ->
    scope.launch {
        var lastModelStatus: TtsModelStatus? = null
        while (true) {
            val status = synthesizer.modelStatus
            if (status != lastModelStatus) {
                lastModelStatus = status
                onModelStatus(status)
            }
            delay(pollMs)
        }
    }.also { modelJob ->
        scope.launch {
            controller.status.collect { status ->
                onTtsStatus(status)
            }
        }
    }
}

private const val TAG = "ServiceCoreInitializer"

/**
 * Core native-resource initializer. Owns STT/TTS/journal/navigation-TTS
 * controller references, model directories, model-status polling jobs, and
 * the journal-storage-backend registry. Implements [CoreInit] for
 * [BootstrapCoordinator].
 *
 * Construction results, model-path/status polling, bootstrap ordering,
 * cancellation/release order, navigation replacement, and text-output
 * availability projection are identical to the prior service-local
 * implementation. The initializer receives no [android.app.Service]
 * reference and no mutable
 * [dev.nilp0inter.subspace.model.AppState] — status changes flow out
 * through [ModelStatusSink] and navigation state-loss through
 * [StateLossCallback].
 *
 * [discardControllers] is the retry-discard boundary (cancels pollers,
 * releases controllers, clears backends). [shutdown] is the idempotent
 * service-teardown boundary — safe to call after partial initialization.
 * Both preserve the exact cancellation/release order of the original
 * service implementation.
 */
internal class ServiceCoreInitializer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val nativeLibraryDirProvider: () -> String,
    private val filesDirProvider: () -> File,
    private val textOutputService: SleepwalkerTextOutputService,
    private val journalStorageBackends: ConcurrentHashMap<String, ServiceJournalStorageBackend>,
    private val channelCatalogue: () -> ChannelCatalogueSnapshot,
    private val modelStatusSink: ModelStatusSink,
    private val navigationStateLoss: StateLossCallback,
    private val hostAudioPlay: suspend (RecordedPcm) -> Boolean,
    private val sttFactory: SttFactory = DefaultSttFactory,
    private val ttsFactory: TtsFactory = DefaultTtsFactory,
    private val ttsControllerFactory: TtsControllerFactory = DefaultTtsControllerFactory,
    private val transcriptionServiceFactory: TranscriptionServiceFactory = DefaultTranscriptionServiceFactory,
    private val pcmTranscriberFallback: PcmTranscriberFallback = DefaultPcmTranscriberFallback,
    private val navigationTtsEngineFactory: NavigationTtsEngineFactory = DefaultNavigationTtsEngineFactory,
    private val journalControllerFactory: JournalControllerFactory = DefaultJournalControllerFactory,
    private val sttPollerFactory: SttPollerFactory = DefaultSttPollerFactory,
    private val ttsPollerFactory: TtsPollerFactory = DefaultTtsPollerFactory,
    private val sttModelPollMs: Long = 500L,
    private val ttsModelPollMs: Long = 500L,
) : CoreInit {

    override var navigationTtsEngine: NavigationTtsEngine? = null
        private set
    override var sttTranscriber: SttTranscriber? = null
        private set
    override var ttsSynthesizer: TtsSynthesizer? = null
        private set
    override var ttsController: TtsController? = null
        private set
    override var journalController: JournalController? = null
        private set

    /** Model directory for the Supertonic synthesizer (null until constructed). */
    var supertonicModelDir: File? = null
        private set

    /** Model directory for the Parakeet transcriber (null until constructed). */
    var sttModelDir: File? = null
        private set

    /** Transcription service (null until STT is constructed). */
    var transcriptionService: TranscriptionService? = null
        private set

    private var sttModelStatusJob: Job? = null
    private var ttsModelStatusJob: Job? = null

    override val textOutputAvailability: CapabilityAvailability
        get() = when (textOutputService.availability.value) {
            TextOutputAvailability.Available -> CapabilityAvailability.Available
            TextOutputAvailability.Preparing,
            TextOutputAvailability.Unavailable -> CapabilityAvailability.Recoverable
            TextOutputAvailability.Closed -> CapabilityAvailability.Unavailable(
                CapabilityUnavailableReason.HOST_NOT_READY,
            )
        }

    // ---- CoreInit construction ----

    override fun constructSttTranscriber(): SttTranscriber? {
        val nativeLibDir = nativeLibraryDirProvider()
        val modelDir = File(filesDirProvider(), ModelVerifier.PARAKEET_DIR)
        sttModelDir = modelDir
        val transcriber = sttFactory.create(nativeLibDir, modelDir) ?: return null
        sttTranscriber = transcriber
        transcriptionService = transcriptionServiceFactory.create(transcriber)
        sttModelStatusJob = sttPollerFactory.create(
            transcriber = transcriber,
            scope = scope,
            pollMs = sttModelPollMs,
        ) { status ->
            modelStatusSink.publish(ModelStatusUpdate(sttModelStatus = status))
        }
        return transcriber
    }

    override fun constructTtsSynthesizer(): TtsSynthesizer? {
        val nativeLibDir = nativeLibraryDirProvider()
        val modelDir = File(filesDirProvider(), ModelVerifier.SUPERTONIC_DIR)
        supertonicModelDir = modelDir
        val synth = ttsFactory.create(nativeLibDir, modelDir) ?: return null
        ttsSynthesizer = synth
        return synth
    }

    override fun constructTtsController(synthesizer: TtsSynthesizer) {
        val controller = ttsControllerFactory.create(
            scope = scope,
            synthesizer = synthesizer,
            play = hostAudioPlay,
        )
        ttsController = controller
        ttsModelStatusJob = ttsPollerFactory.create(
            synthesizer = synthesizer,
            controller = controller,
            scope = scope,
            pollMs = ttsModelPollMs,
            onModelStatus = { status ->
                modelStatusSink.publish(ModelStatusUpdate(ttsModelStatus = status))
            },
            onTtsStatus = { status ->
                modelStatusSink.publish(ModelStatusUpdate(ttsStatus = status))
            },
        )
    }

    override fun constructJournalPttController() {
        if (journalController != null) return
        val transcriber = sttTranscriber
        val pcmTranscriber: PcmTranscriber = if (transcriber != null) {
            transcriptionServiceFactory.create(transcriber)
        } else {
            pcmTranscriberFallback.create()
        }
        journalController = journalControllerFactory.create(scope, pcmTranscriber)
    }

    override fun initializeTextOutputCapability() {
        // The text-output service is constructed by the service before
        // bootstrap. This is a readiness gate, not a construction step.
    }

    override suspend fun prepareNavigationTts(): PrepareResult {
        navigationTtsEngine?.shutdown()
        navigationTtsEngine = null
        val engine = navigationTtsEngineFactory.create(context, navigationStateLoss)
        return try {
            when (val result = engine.prepare()) {
                is PrepareResult.Success -> {
                    navigationTtsEngine = engine
                    result
                }
                is PrepareResult.Failure -> {
                    engine.shutdown()
                    result
                }
            }
        } catch (error: CancellationException) {
            engine.shutdown()
            throw error
        } catch (error: Exception) {
            engine.shutdown()
            PrepareResult.Failure(
                NavigationTtsFailure.BootstrapSetupFailure.EngineInitFailed(
                    error.message ?: "Unable to initialize Android text-to-speech",
                ),
            )
        }
    }

    // ---- Discard / shutdown ----

    /**
     * Retry-discard: cancel pollers, release controllers, clear backends,
     * and null all core references. Safe to call after partial
     * initialization. Preserves the exact release order of the original
     * service implementation.
     */
    override suspend fun discardControllers() {
        releaseCoreResources()
    }

    /**
     * Idempotent service-teardown boundary. Performs the same release as
     * [discardControllers]. Safe to call multiple times — subsequent calls
     * are no-ops because all references are already null.
     *
     * This does NOT cancel [scope] (the service owns the scope lifecycle)
     * and does NOT close [textOutputService] (owned by the service).
     */
    suspend fun shutdown() {
        releaseCoreResources()
    }

    private suspend fun releaseCoreResources() {
        navigationTtsEngine?.shutdown()
        navigationTtsEngine = null
        sttModelStatusJob?.cancel()
        sttModelStatusJob = null
        ttsModelStatusJob?.cancel()
        ttsModelStatusJob = null
        ttsController?.cancelAndRelease()
        ttsController = null
        journalController = null
        journalStorageBackends.clear()
        sttTranscriber = null
        ttsSynthesizer = null
        transcriptionService = null
    }

    // ---- Narrow capability accessors for capability host / runtime composition ----

    /** Transcription capability adapter, or null if STT is not yet constructed. */
    fun transcriptionCapability(identity: CapabilityScopeIdentity): TranscriptionCapability? =
        transcriptionService?.let(::TranscriptionCapabilityAdapter)

    /**
     * Synthesis capability adapter, or null if TTS is not yet constructed.
     * The [voiceStylePath] and [totalSteps] suppliers read current monitor
     * state from the service without exposing it.
     */
    fun synthesisCapability(
        identity: CapabilityScopeIdentity,
        voiceStylePath: () -> String?,
        totalSteps: () -> Int,
    ): SynthesisCapability? = ttsSynthesizer?.let { synthesizer ->
        SynthesisCapabilityAdapter(synthesizer) { voice ->
            if (voice.id != "default") {
                null
            } else {
                voiceStylePath()?.let { path ->
                    SpeechSynthesisParameters(
                        voiceStylePath = path,
                        totalSteps = totalSteps(),
                    )
                }
            }
        }
    }

    /**
     * Journal storage capability adapter, or null if the journal controller
     * or channel configuration is unavailable. Reads the channel catalogue
     * snapshot to resolve the journal base directory.
     */
    fun journalCapability(identity: CapabilityScopeIdentity): JournalStorageCapability? =
        journalController?.let { controller ->
            val definition = channelCatalogue().definitions
                .find { it.id == identity.channelInstanceId }
                ?: return@let null
            val configuration = JournalProviderConfigurationCodec.decode(definition.configPayload).getOrNull()
                ?.takeIf { !it.baseDirectory.isNullOrBlank() }
                ?: return@let null
            val key = "${identity.channelInstanceId}:${identity.runtimeGeneration.value}"
            JournalStorageCapabilityAdapter(
                journalStorageBackends.computeIfAbsent(key) {
                    ServiceJournalStorageBackend(identity.channelInstanceId, configuration, controller)
                },
            )
        }
}