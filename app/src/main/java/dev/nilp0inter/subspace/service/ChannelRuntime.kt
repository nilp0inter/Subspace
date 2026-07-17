package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelProviderError
import kotlinx.coroutines.flow.StateFlow

enum class ChannelExecutionStatus {
    IDLE, RECORDING, PROCESSING, SUCCESS, FAILED
}

/** Provider-neutral input eligibility used by routing and host-owned presentation. */
sealed interface ChannelPreparationAvailability {
    data object Available : ChannelPreparationAvailability
    data class Recoverable(val reason: ChannelPreparationReason) : ChannelPreparationAvailability
    data class Unavailable(val reason: ChannelPreparationReason) : ChannelPreparationAvailability
}

/** Typed, host-safe explanation for why a channel cannot immediately accept input. */
sealed interface ChannelPreparationReason {
    val message: String

    data object Disabled : ChannelPreparationReason { override val message = "Channel is disabled" }
    data object ProviderInitialising : ChannelPreparationReason { override val message = "Channel is initialising" }
    data class Provider(val error: ChannelProviderError) : ChannelPreparationReason {
        override val message: String = error.message
    }
    data class ConnectionProfileUnavailable(
        val reason: dev.nilp0inter.subspace.model.OpenAiAvailabilityReason,
    ) : ChannelPreparationReason {
        override val message: String = "Connection profile is unavailable"
    }
    data object ModelUnavailable : ChannelPreparationReason {
        override val message: String = "Selected model is unavailable"
    }
    data object RuntimeBusy : ChannelPreparationReason { override val message = "Channel is busy" }
    data object RuntimeTimedOut : ChannelPreparationReason { override val message = "Channel operation timed out" }
    data object RuntimeCancelled : ChannelPreparationReason { override val message = "Channel operation was cancelled" }
    data object RuntimeClosed : ChannelPreparationReason { override val message = "Channel is no longer available" }
    data class RuntimeFailed(override val message: String = "Channel operation failed") : ChannelPreparationReason
    data object UnknownInstance : ChannelPreparationReason { override val message = "Channel was not found" }
    data object RegistryShutDown : ChannelPreparationReason { override val message = "Channel service is shutting down" }
}

data class ChannelRuntimeSnapshot(
    val id: String,
    val name: String,
    val implementationId: ChannelImplementationId,
    val enabled: Boolean,
    val preparation: ChannelPreparationAvailability,
    val executionStatus: ChannelExecutionStatus,
    val summary: String? = null,
    val pendingCount: Int = 0,
    val playbackPaused: Boolean = false,
)

/** Provider-neutral result of bounded, protected runtime startup. */
sealed interface ChannelActivationResult {
    data object Ready : ChannelActivationResult
    data class Failed(val message: String) : ChannelActivationResult
}

interface ChannelRuntime {
    val id: String
    val snapshot: StateFlow<ChannelRuntimeSnapshot>

    suspend fun prepareInput(): ChannelInputAcceptance
    suspend fun handleSos() {}
    suspend fun refreshReadiness() {}

    /**
     * Provider-neutral opt-in cadence for registry-owned readiness refresh.
     * Null preserves the existing on-demand-only behavior.
     */
    val readinessRefreshIntervalMillis: Long? get() = null

    /**
     * Bounded, host-protected runtime startup invoked after construction and, for staged
     * successors, after the predecessor has fully closed and capabilities are authorized.
     * The default returns [ChannelActivationResult.Ready] so existing Kotlin providers that
     * do not override this hook remain ready immediately.
     */
    suspend fun activate(): ChannelActivationResult = ChannelActivationResult.Ready

    /** Implementations must make terminal closure idempotent and await their child work. */
    suspend fun close()
}
