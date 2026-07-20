package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.SleepwalkerTextOutputService
import dev.nilp0inter.subspace.channel.TextOutputAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityFailureReason
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.CapabilityLeaseTermination
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapability
import dev.nilp0inter.subspace.channel.capability.SynthesisCapability
import dev.nilp0inter.subspace.channel.capability.AudioOperationCapability
import dev.nilp0inter.subspace.channel.capability.JournalStorageCapability
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.AsynchronousConversationCapability
import dev.nilp0inter.subspace.channel.capability.DelayedPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.DeferredAudioPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.OpenAiCompletionCapability
import dev.nilp0inter.subspace.channel.capability.OpenAiModelDiscoveryCapability
import kotlinx.coroutines.withTimeoutOrNull

internal interface GenerationCapabilityResource {
    suspend fun onGenerationTermination(identity: CapabilityScopeIdentity, termination: CapabilityLeaseTermination)
}

/**
 * Composition root for instance-scoped capability leases.
 *
 * Every factory receives the requesting instance/generation identity. The only shared resource
 * here is [textOutputService]; a lease may detach its own work but can never close that service.
 */
internal class ServiceChannelCapabilityHost(
    private val textOutputService: SleepwalkerTextOutputService,
    private val transcription: (CapabilityScopeIdentity) -> TranscriptionCapability?,
    private val synthesis: (CapabilityScopeIdentity) -> SynthesisCapability?,
    private val audioOperation: (CapabilityScopeIdentity) -> AudioOperationCapability?,
    private val journal: (CapabilityScopeIdentity) -> JournalStorageCapability?,
    private val openAiModelDiscovery: (CapabilityScopeIdentity) -> OpenAiModelDiscoveryCapability? = { null },
    private val openAiCompletion: (CapabilityScopeIdentity) -> OpenAiCompletionCapability? = { null },
    private val asynchronousConversation: (CapabilityScopeIdentity) -> AsynchronousConversationCapability? = { null },
    private val delayedPlayback: (CapabilityScopeIdentity) -> DelayedPlaybackCapability? = { null },
    private val deferredAudioPlayback: (CapabilityScopeIdentity) -> DeferredAudioPlaybackCapability? = { null },
) : ChannelCapabilityHost {
    override suspend fun onGenerationTermination(
        identity: CapabilityScopeIdentity,
        termination: CapabilityLeaseTermination,
    ) {
        if (termination != CapabilityLeaseTermination.REVOKED) return
        // Scheduling leases are deliberately short-lived: a queued playback entry can remain
        // after its lease is released. Revoke every host-owned generation resource directly so
        // scope revocation still drains those entries. Each resource performs its own idempotent
        // instance+generation filtering.
        deferredAudioPlayback(identity)?.let { resource ->
            if (resource is GenerationCapabilityResource) {
                try {
                    resource.onGenerationTermination(identity, termination)
                } catch (_: Exception) {
                    // Resource cleanup is idempotent; a host cleanup failure must not block scope revocation.
                }
            }
        }
    }

    override suspend fun availability(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<*>,
    ): CapabilityAvailability = when (key) {
        CapabilityKey.TextOutput -> textOutputAvailability()
        CapabilityKey.Transcription -> transcription(identity).availability()
        CapabilityKey.Synthesis -> synthesis(identity).availability()
        CapabilityKey.AudioOperation -> audioOperation(identity).availability()
        CapabilityKey.Journal -> journal(identity).availability()
        CapabilityKey.OpenAiModelDiscovery -> openAiModelDiscovery(identity).availability()
        CapabilityKey.OpenAiCompletion -> openAiCompletion(identity).availability()
        CapabilityKey.AsynchronousConversation -> asynchronousConversation(identity).availability()
        CapabilityKey.DelayedPlayback -> delayedPlayback(identity).availability()
        CapabilityKey.DeferredAudioPlayback -> deferredAudioPlayback(identity).availability()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : ChannelCapabilityPort> acquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
    ): HostedCapabilityAcquisition<T> = (
        when (key) {
            CapabilityKey.TextOutput -> textOutputAcquisition(identity)
            CapabilityKey.Transcription -> availableOrUnavailable(transcription(identity), identity)
            CapabilityKey.Synthesis -> availableOrUnavailable(synthesis(identity), identity)
            CapabilityKey.AudioOperation -> availableOrUnavailable(audioOperation(identity), identity)
            CapabilityKey.Journal -> availableOrUnavailable(journal(identity), identity)
            CapabilityKey.OpenAiModelDiscovery -> availableOrUnavailable(openAiModelDiscovery(identity), identity)
            CapabilityKey.OpenAiCompletion -> availableOrUnavailable(openAiCompletion(identity), identity)
            CapabilityKey.AsynchronousConversation -> availableOrUnavailable(asynchronousConversation(identity), identity)
            CapabilityKey.DelayedPlayback -> availableOrUnavailable(delayedPlayback(identity), identity)
            CapabilityKey.DeferredAudioPlayback -> availableOrUnavailable(deferredAudioPlayback(identity), identity)
        }
    ) as HostedCapabilityAcquisition<T>

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
        timeoutMillis: Long,
    ): HostedCapabilityAcquisition<T> {
        if (key != CapabilityKey.TextOutput) return acquire(identity, key)
        val preparation = withTimeoutOrNull(timeoutMillis) { textOutputService.prepare() }
            ?: dev.nilp0inter.subspace.channel.TextOutputPreparation.TimedOut
        return (when (preparation) {
            dev.nilp0inter.subspace.channel.TextOutputPreparation.Available -> {
                if (textOutputService.isReadyForDelivery()) {
                    available(textOutputService.capabilityFor(identity.channelInstanceId), identity)
                } else {
                    HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.HOST_NOT_READY)
                }
            }
            dev.nilp0inter.subspace.channel.TextOutputPreparation.TimedOut ->
                HostedCapabilityAcquisition.Failed(CapabilityFailureReason.PREPARATION_FAILED)
            is dev.nilp0inter.subspace.channel.TextOutputPreparation.Failed ->
                HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
        }) as HostedCapabilityAcquisition<T>
    }
    private fun textOutputAcquisition(
        identity: CapabilityScopeIdentity,
    ): HostedCapabilityAcquisition<TextOutputCapability> = when (textOutputService.availability.value) {
        TextOutputAvailability.Available -> available(
            textOutputService.capabilityFor(identity.channelInstanceId),
            identity,
        )
        TextOutputAvailability.Preparing,
        TextOutputAvailability.Unavailable,
        -> HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.HOST_NOT_READY)
        TextOutputAvailability.Closed ->
            HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
    }


    private fun textOutputAvailability(): CapabilityAvailability = when (textOutputService.availability.value) {
        TextOutputAvailability.Available -> CapabilityAvailability.Available
        TextOutputAvailability.Preparing,
        TextOutputAvailability.Unavailable -> CapabilityAvailability.Recoverable
        TextOutputAvailability.Closed -> CapabilityAvailability.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
    }

    private fun ChannelCapabilityPort?.availability(): CapabilityAvailability =
        if (this == null) CapabilityAvailability.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
        else CapabilityAvailability.Available

    private fun <T : ChannelCapabilityPort> availableOrUnavailable(
        port: T?,
        identity: CapabilityScopeIdentity,
    ): HostedCapabilityAcquisition<T> = port?.let { available(it, identity) }
        ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)

    private fun <T : ChannelCapabilityPort> available(
        port: T,
        identity: CapabilityScopeIdentity,
    ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Available(port) { termination ->
        if (termination == CapabilityLeaseTermination.REVOKED && port is GenerationCapabilityResource) {
            port.onGenerationTermination(identity, termination)
        }
    }
}
