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
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapability
import dev.nilp0inter.subspace.channel.capability.SynthesisCapability
import dev.nilp0inter.subspace.channel.capability.AudioOperationCapability
import dev.nilp0inter.subspace.channel.capability.JournalStorageCapability
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import kotlinx.coroutines.withTimeoutOrNull

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
) : ChannelCapabilityHost {
    override suspend fun availability(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<*>,
    ): CapabilityAvailability = when (key) {
        CapabilityKey.TextOutput -> textOutputAvailability()
        CapabilityKey.Transcription -> transcription(identity).availability()
        CapabilityKey.Synthesis -> synthesis(identity).availability()
        CapabilityKey.AudioOperation -> audioOperation(identity).availability()
        CapabilityKey.Journal -> journal(identity).availability()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : ChannelCapabilityPort> acquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
    ): HostedCapabilityAcquisition<T> = (
        when (key) {
            CapabilityKey.TextOutput -> textOutputAcquisition(identity)
            CapabilityKey.Transcription -> availableOrUnavailable(transcription(identity))
            CapabilityKey.Synthesis -> availableOrUnavailable(synthesis(identity))
            CapabilityKey.AudioOperation -> availableOrUnavailable(audioOperation(identity))
            CapabilityKey.Journal -> availableOrUnavailable(journal(identity))
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
                    available(textOutputService.capabilityFor(identity.channelInstanceId))
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
    ): HostedCapabilityAcquisition<T> = port?.let(::available)
        ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)

    private fun <T : ChannelCapabilityPort> available(port: T): HostedCapabilityAcquisition<T> =
        HostedCapabilityAcquisition.Available(port) {
            // Capability leases never own shared host services. Per-operation cancellation is
            // contained by the concrete port before this no-op lease cleanup returns.
        }
}
