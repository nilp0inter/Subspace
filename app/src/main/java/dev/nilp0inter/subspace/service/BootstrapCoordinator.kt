package dev.nilp0inter.subspace.service

import android.content.Context
import dev.nilp0inter.subspace.audio.ModelAssetRepository
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.audio.SystemAnnouncer
import dev.nilp0inter.subspace.channel.JournalController
import dev.nilp0inter.subspace.channel.KeyboardPttController
import dev.nilp0inter.subspace.model.AnnouncementResult
import dev.nilp0inter.subspace.model.BootstrapStage
import dev.nilp0inter.subspace.model.BootstrapState
import dev.nilp0inter.subspace.model.ModelAssetResult
import dev.nilp0inter.subspace.model.SttModelStatus
import dev.nilp0inter.subspace.model.TtsModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service-owned bootstrap coordinator. Owns the authoritative bootstrap
 * [StateFlow] and drives the transition from launch through prerequisite
 * checking, model acquisition, native initialization, controller
 * construction, and announcement rendering to [BootstrapState.Ready].
 *
 * The coordinator delegates native engine construction and controller wiring
 * to [CoreInit] (implemented by [PttForegroundService]) because the service
 * owns the audio infrastructure, JNI bridges, and capture pipeline.
 *
 * RSM, SPP, HFP, Keyboard BLE, Android Auto, Telecom, reconnect, and
 * journal-recovery readiness are explicitly excluded from the bootstrap
 * completion predicate (see [isCoreReady]).
 *
 * Each attempt owns its own [Job] so that a safe retry can cancel and
 * discard prior attempt jobs, pollers, and controllers before
 * reconstructing.
 */
class BootstrapCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val modelRepository: ModelAssetRepository,
    private val coreInit: CoreInit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.ConnectingService)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    /**
     * Per-attempt bootstrap job. Cancelled on retry.
     */
    private var attemptJob: Job? = null
    private var retryJob: Job? = null

    /**
     * Test-injectable finite timeouts per stage.
     */
    var sttTimeoutMs: Long = 60_000L
    var ttsTimeoutMs: Long = 60_000L
    var announcementTimeoutMs: Long = 120_000L

    private fun launchAttempt() {
        if (attemptJob?.isActive == true || retryJob?.isActive == true) {
            return
        }
        attemptJob = scope.launch {
            val currentJob = coroutineContext[Job]
            try {
                runAttempt()
            } finally {
                if (attemptJob === currentJob) {
                    attemptJob = null
                }
            }
        }
    }

    /**
     * Called by the service after binding to start bootstrap.
     * Checks permissions and model assets, then either routes to setup
     * or proceeds to core preparation.
     */
    fun startBootstrap() {
        if (_state.value is BootstrapState.ConnectingService ||
            _state.value is BootstrapState.Failed
        ) {
            launchAttempt()
        }
    }

    /**
     * Binder command: refresh prerequisites. Re-checks permissions and model
     * assets. Used after a permission result or a model-acquisition retry.
     */
    fun refreshPrerequisites() {
        launchAttempt()
    }

    /**
     * Binder command: explicitly start model acquisition. Transitions to
     * [BootstrapState.AcquiringModels] and starts downloading all invalid
     * sets through the model repository.
     */
    fun startModelAcquisition() {
        scope.launch {
            _state.value = BootstrapState.AcquiringModels(
                progress = modelRepository.progress.value,
            )
            val ready = modelRepository.ensureAllReady()
            if (ready) {
                launchAttempt()
            } else {
                val results = modelRepository.inspectAll()
                val failedSets = results.filterIsInstance<ModelAssetResult.Failed>()
                    .map { it.dirName }
                val errorMessages = results.filterIsInstance<ModelAssetResult.Failed>()
                    .joinToString("; ") { it.reason }
                _state.value = BootstrapState.NeedsSetup(
                    invalidModelSets = failedSets,
                    error = errorMessages.ifEmpty { "Model acquisition failed" },
                )
            }
        }
    }

    /**
     * Binder command: retry a failed bootstrap. Cancels and discards prior
     * attempt jobs, pollers, and controllers before re-entering prerequisite
     * checking.
     */
    fun retry() {
        if (retryJob?.isActive == true) {
            return
        }
        val prior = attemptJob

        val replacement = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            try {
                prior?.cancelAndJoin()
                coreInit.discardControllers()
                runAttempt()
            } finally {
                if (retryJob === currentJob) {
                    retryJob = null
                }
                if (attemptJob === currentJob) {
                    attemptJob = null
                }
            }
        }

        retryJob = replacement
        attemptJob = replacement
        replacement.start()
    }

    /**
     * Cancel all jobs from the current attempt. Called on retry and on
     * service destruction.
     */
    fun cancelAttempt() {
        attemptJob?.cancel()
        retryJob?.cancel()
        attemptJob = null
        retryJob = null
    }

    private suspend fun runAttempt() {
        _state.value = BootstrapState.CheckingPrerequisites(
            BootstrapStage.CheckingPermissions,
        )
        val missingPermissions = withContext(ioDispatcher) {
            RequiredPermissions.missing(context)
        }
        if (missingPermissions.isNotEmpty()) {
            _state.value = BootstrapState.NeedsSetup(
                missingPermissions = missingPermissions,
            )
            return
        }

        _state.value = BootstrapState.CheckingPrerequisites(
            BootstrapStage.CheckingModels,
        )
        val results = modelRepository.inspectAll()
        val invalidSets = results.filterIsInstance<ModelAssetResult.UserActionRequired>()
            .map { it.dirName }
        if (invalidSets.isNotEmpty()) {
            _state.value = BootstrapState.NeedsSetup(
                invalidModelSets = invalidSets,
            )
            return
        }

        _state.value = BootstrapState.PreparingCore(
            BootstrapStage.InitializingStt,
        )

        kotlinx.coroutines.coroutineScope {
            val sttDeferred = async(ioDispatcher) {
                prepareStt()
            }
            val ttsDeferred = async(ioDispatcher) {
                prepareTts()
            }

            val sttResult = withTimeoutOrNull(sttTimeoutMs) { sttDeferred.await() }
            val ttsResult = withTimeoutOrNull(ttsTimeoutMs) { ttsDeferred.await() }

            if (sttResult is PrepareResult.Failed) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.InitializingStt,
                    sttResult.reason,
                )
                return@coroutineScope
            }
            if (ttsResult is PrepareResult.Failed) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.InitializingTts,
                    ttsResult.reason,
                )
                return@coroutineScope
            }
            if (sttResult == null) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.InitializingStt,
                    "STT initialization timed out after ${sttTimeoutMs}ms",
                )
                return@coroutineScope
            }
            if (ttsResult == null) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.InitializingTts,
                    "TTS initialization timed out after ${ttsTimeoutMs}ms",
                )
                return@coroutineScope
            }

            _state.value = BootstrapState.PreparingCore(
                BootstrapStage.ConstructingControllers,
            )
            val transcriber = (sttResult as PrepareResult.Success).transcriber
            val synthesizer = (ttsResult as PrepareResult.SuccessTts).synthesizer
            coreInit.constructSttTtsController(transcriber, synthesizer)
            coreInit.constructJournalPttController()

            _state.value = BootstrapState.PreparingCore(
                BootstrapStage.RenderingAnnouncements,
            )
            val announcer = coreInit.announcer
            if (announcer != null) {
                val vocab = coreInit.buildVocabulary()
                val voiceStylePath = coreInit.voiceStylePath()
                val announceResult = withTimeoutOrNull(announcementTimeoutMs) {
                    announcer.precompute(vocab, voiceStylePath, SCO_RATE)
                }
                when (announceResult) {
                    is AnnouncementResult.Ready -> { /* OK */ }
                    is AnnouncementResult.Failed -> {
                        _state.value = BootstrapState.Failed(
                            BootstrapStage.RenderingAnnouncements,
                            "Announcement '${announceResult.failedKey}' failed: ${announceResult.reason}",
                        )
                        return@coroutineScope
                    }
                    null -> {
                        _state.value = BootstrapState.Failed(
                            BootstrapStage.RenderingAnnouncements,
                            "Announcement rendering timed out after ${announcementTimeoutMs}ms",
                        )
                        return@coroutineScope
                    }
                    else -> {
                        _state.value = BootstrapState.Failed(
                            BootstrapStage.RenderingAnnouncements,
                            "Announcement rendering did not complete: $announceResult",
                        )
                        return@coroutineScope
                    }
                }
            }

            _state.value = BootstrapState.PreparingCore(
                BootstrapStage.VerifyingReadiness,
            )
            if (isCoreReady()) {
                _state.value = BootstrapState.Ready
            } else {
                val diag = isCoreReadyDiagnostic()
                try {
                    android.util.Log.w("BootstrapCoordinator", "Core readiness failed: $diag")
                } catch (e: Throwable) {
                    println("[BootstrapCoordinator] Core readiness failed: $diag")
                }
                _state.value = BootstrapState.Failed(
                    BootstrapStage.VerifyingReadiness,
                    "Core readiness verification failed: $diag",
                )
            }
        }
    }


    /**
     * Core readiness predicate: verified model assets, ready native STT and
     * TTS engines, constructed required controllers, and every required
     * announcement phrase cached as non-empty SCO-ready audio.
     *
     * RSM, SPP, HFP, Keyboard BLE, Android Auto, Telecom, reconnect, and
     * journal-recovery are explicitly excluded.
     */
    private fun isCoreReady(): Boolean {
        return coreInit.sttTranscriber != null &&
            coreInit.ttsSynthesizer != null &&
            coreInit.sttController != null &&
            coreInit.ttsController != null &&
            coreInit.sttTtsController != null &&
            coreInit.journalController != null &&
            coreInit.keyboardController != null &&
            coreInit.announcer != null &&
            coreInit.announcer?.precomputeState?.value is AnnouncementResult.Ready
    }

    private fun isCoreReadyDiagnostic(): String {
        val checks = listOf(
            "sttTranscriber=${coreInit.sttTranscriber != null}",
            "ttsSynthesizer=${coreInit.ttsSynthesizer != null}",
            "sttController=${coreInit.sttController != null}",
            "ttsController=${coreInit.ttsController != null}",
            "sttTtsController=${coreInit.sttTtsController != null}",
            "journalController=${coreInit.journalController != null}",
            "keyboardController=${coreInit.keyboardController != null}",
            "announcer=${coreInit.announcer != null}",
            "announcerState=${coreInit.announcer?.precomputeState?.value}",
        )
        return checks.joinToString("; ")
    }

    /**
     * Prepare STT: construct JNI transcriber via [CoreInit], wait for Ready
     * or Failed, construct SttController and KeyboardPttController.
     */
    private suspend fun prepareStt(): PrepareResult {
        return try {
            val transcriber = coreInit.constructSttTranscriber()
                ?: return PrepareResult.Failed(
                    "STT transcriber construction failed",
                )
            // Wait for STT model Ready or Failed.
            while (true) {
                when (transcriber.modelStatus) {
                    SttModelStatus.Ready -> break
                    SttModelStatus.Failed -> {
                        return PrepareResult.Failed(
                            "STT model failed to load: ${transcriber.loadError ?: "unknown"}",
                        )
                    }
                    else -> delay(STT_MODEL_POLL_MS)
                }
            }
            coreInit.constructSttController(transcriber)
            coreInit.constructKeyboardController(transcriber)
            PrepareResult.Success(transcriber)
        } catch (e: Exception) {
            PrepareResult.Failed("STT initialization failed: ${e.message}")
        }
    }

    /**
     * Prepare TTS: construct JNI synthesizer via [CoreInit], wait for Ready
     * or Failed, construct TtsController and SystemAnnouncer.
     */
    private suspend fun prepareTts(): PrepareResult {
        return try {
            val synthesizer = coreInit.constructTtsSynthesizer()
                ?: return PrepareResult.Failed(
                    "TTS synthesizer construction failed",
                )
            // Wait for TTS model Ready or Failed.
            while (true) {
                when (synthesizer.modelStatus) {
                    TtsModelStatus.Ready -> break
                    TtsModelStatus.Failed -> {
                        return PrepareResult.Failed(
                            "TTS model failed to load: ${synthesizer.loadError ?: "unknown"}",
                        )
                    }
                    else -> delay(TTS_MODEL_POLL_MS)
                }
            }
            coreInit.constructTtsController(synthesizer)
            coreInit.constructAnnouncer(synthesizer)
            PrepareResult.SuccessTts(synthesizer)
        } catch (e: Exception) {
            PrepareResult.Failed("TTS initialization failed: ${e.message}")
        }
    }

    private sealed interface PrepareResult {
        data class Success(val transcriber: SttTranscriber) : PrepareResult
        data class SuccessTts(val synthesizer: TtsSynthesizer) : PrepareResult
        data class Failed(val reason: String) : PrepareResult
    }

    companion object {
        private const val STT_MODEL_POLL_MS = 500L
        private const val TTS_MODEL_POLL_MS = 500L
        private const val SCO_RATE = 16_000
    }
}

/**
 * Interface implemented by [PttForegroundService] to delegate native engine
 * construction and controller wiring to the service, which owns the audio
 * infrastructure, JNI bridges, and capture pipeline.
 */
interface CoreInit {
    val sttTranscriber: SttTranscriber?
    val ttsSynthesizer: TtsSynthesizer?
    val sttController: SttController?
    val ttsController: TtsController?
    val sttTtsController: SttTtsController?
    val journalController: JournalController?
    val keyboardController: KeyboardPttController?
    val announcer: SystemAnnouncer?

    /** Construct the STT JNI transcriber and return it, or null on failure. */
    fun constructSttTranscriber(): SttTranscriber?

    /** Construct the TTS JNI synthesizer and return it, or null on failure. */
    fun constructTtsSynthesizer(): TtsSynthesizer?

    /** Construct the SttController and start its status poller. */
    fun constructSttController(transcriber: SttTranscriber)

    /** Construct the TtsController and start its status poller. */
    fun constructTtsController(synthesizer: TtsSynthesizer)

    /** Construct the SttTtsController from both ready engines. */
    fun constructSttTtsController(
        transcriber: SttTranscriber,
        synthesizer: TtsSynthesizer,
    )

    /** Construct the JournalPttController. */
    fun constructJournalPttController()

    /** Construct the KeyboardPttController from the ready STT transcriber. */
    fun constructKeyboardController(transcriber: SttTranscriber)

    /** Construct the SystemAnnouncer from the ready TTS synthesizer. */
    fun constructAnnouncer(synthesizer: TtsSynthesizer)

    /** Build the announcement vocabulary map. */
    fun buildVocabulary(): Map<String, String>

    /** Return the voice style file path for announcement rendering. */
    fun voiceStylePath(): String

    /** Discard all controllers, pollers, and transcriber/synthesizer for retry. */
    fun discardControllers()
}