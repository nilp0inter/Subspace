package dev.nilp0inter.subspace.model


/**
 * Sealed hierarchy of bootstrap states owned by [PttForegroundService] and
 * observed by `MainActivity` to decide which root surface to render.
 *
 * The state machine transitions are:
 *
 * ```text
 * ConnectingService
 *   └─> CheckingPrerequisites(stage)
 *         ├─> NeedsSetup(missingPermissions, invalidModelSets, error?)
 *         │     └─> AcquiringModels(progress)  [user starts download]
 *         │           ├─> NeedsSetup(..., error)  [acquisition failed]
 *         │           └─> CheckingPrerequisites  [acquisition succeeded]
 *         ├─> AcquiringModels(progress)  [autonomous repair during checking]
 *         │     └─> CheckingPrerequisites  [repair complete]
 *         └─> PreparingCore(stage, completedUnits, totalUnits)
 *               ├─> Failed(stage, diagnostic, retryable)
 *               └─> Ready
 * ```
 *
 * `Failed` can arise from any non-`Ready` state; `Ready` is terminal until
 * an explicit retry re-enters `CheckingPrerequisites`.
 */
sealed interface BootstrapState {

    /**
     * The bootstrap-owning service is not yet bound. The activity shows a
     * passive "starting service" loading stage.
     */
    data object ConnectingService : BootstrapState

    /**
     * Bootstrap is checking runtime permissions and model asset validity.
     * [stage] identifies the sub-stage for loading display.
     */
    data class CheckingPrerequisites(
        val stage: BootstrapStage = BootstrapStage.CheckingPermissions,
    ) : BootstrapState

    /**
     * One or more user-resolvable prerequisites are missing: runtime permissions, all-files
     * storage access, or model assets that require explicit download or repair.
     *
     * [missingPermissions] lists the exact missing runtime permission strings.
     * [needsManageExternalStorage] identifies Android's separate all-files settings grant; it
     * cannot be requested through the runtime-permission dialog.
     * [invalidModelSets] lists the model set directory names that are absent,
     * version-mismatched, or hash-invalid. [error] carries a diagnostic from a failed
     * acquisition attempt so the setup surface can display it alongside the retry action.
     */
    data class NeedsSetup(
        val missingPermissions: List<String> = emptyList(),
        val needsManageExternalStorage: Boolean = false,
        val invalidModelSets: List<String> = emptyList(),
        val error: String? = null,
    ) : BootstrapState

    /**
     * Model acquisition is in progress. [progress] carries real byte/count
     * progress from the active download operation.
     */
    data class AcquiringModels(
        val progress: ModelAcquisitionProgress = ModelAcquisitionProgress(),
    ) : BootstrapState

    /**
     * Core initialization is in progress: native STT/TTS engine loading,
     * controller construction, and announcement rendering.
     *
     * [stage] identifies the current core-preparation sub-stage.
     * [completedUnits] and [totalUnits] carry measurable progress when the
     * underlying operation reports a real count (e.g. announcement phrases).
     */
    data class PreparingCore(
        val stage: BootstrapStage = BootstrapStage.InitializingStt,
        val completedUnits: Int = 0,
        val totalUnits: Int = 0,
    ) : BootstrapState

    /**
     * All core readiness conditions have completed successfully. The
     * dashboard is shown.
     */
    data object Ready : BootstrapState

    /**
     * A bootstrap failure has occurred. [stage] identifies the failed
     * stage. [diagnostic] carries a concrete human-readable diagnostic.
     * [retryable] indicates whether a safe retry is available.
     */
    data class Failed(
        val stage: BootstrapStage,
        val diagnostic: String,
        val retryable: Boolean = true,
    ) : BootstrapState
}

/**
 * Sub-stages within the bootstrap lifecycle. Used by [BootstrapState]
 * implementations and by the loading surface to report the current
 * observed stage.
 */
enum class BootstrapStage {
    ConnectingService,
    CheckingPermissions,
    CheckingModels,
    AcquiringModels,
    InitializingStt,
    InitializingTts,
    ConstructingControllers,
    RenderingAnnouncements,
    VerifyingReadiness,
}

/**
 * Real progress from a model acquisition operation. Each [ModelSetProgress]
 * tracks one model set's download progress.
 */
data class ModelAcquisitionProgress(
    val sets: List<ModelSetProgress> = emptyList(),
) {
    val totalBytes: Long get() = sets.sumOf { it.totalBytes }
    val bytesRead: Long get() = sets.sumOf { it.bytesRead }
}

data class ModelSetProgress(
    val dirName: String,
    val currentFile: String = "",
    val bytesRead: Long = 0,
    val totalBytes: Long = 0,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
)

/**
 * Terminal result of a model asset inspection or acquisition operation.
 */
sealed interface ModelAssetResult {

    /**
     * The model set is present, non-empty, and every file's SHA-256 matches
     * the bundled manifest.
     */
    data class Valid(val dirName: String) : ModelAssetResult

    /**
     * The model set is absent, version-mismatched, or hash-invalid and
     * requires user-initiated download or repair.
     */
    data class UserActionRequired(
        val dirName: String,
        val reason: String,
    ) : ModelAssetResult

    /**
     * Acquisition is actively in progress for this model set. Concurrent
     * callers join the in-flight operation.
     */
    data class Active(
        val dirName: String,
        val progress: ModelSetProgress,
    ) : ModelAssetResult

    /**
     * Acquisition or verification failed. [reason] carries the diagnostic.
     */
    data class Failed(
        val dirName: String,
        val reason: String,
    ) : ModelAssetResult
}

/**
 * Terminal result of announcement precomputation.
 */
sealed interface AnnouncementResult {

    /** Every required phrase has non-empty SCO-ready PCM in the cache. */
    data class Ready(val renderedKeys: Set<String>) : AnnouncementResult

    /** Rendering is in progress: [completed] of [total] phrases done. */
    data class Rendering(
        val completed: Int,
        val total: Int,
        val currentKey: String,
    ) : AnnouncementResult

    /** Waiting for the TTS engine to report [TtsModelStatus.Ready]. */
    data object WaitingForTts : AnnouncementResult

    /** A required phrase failed to synthesize or produced empty PCM. */
    data class Failed(
        val completed: Int,
        val total: Int,
        val failedKey: String,
        val reason: String,
    ) : AnnouncementResult
}