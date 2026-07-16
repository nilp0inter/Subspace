package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelProviderResolution
import dev.nilp0inter.subspace.model.ChannelRepository
import dev.nilp0inter.subspace.model.ChannelRepositoryError
import dev.nilp0inter.subspace.model.ChannelRepositoryMutationResult
import dev.nilp0inter.subspace.model.OpaqueJsonObject

/**
 * Focused owner of provider-backed channel creation, configuration update,
 * playback coordinators. It holds no service reference and does not mutate
 * app state; selection offset dispatch and PTT dispatch remain in the service.
 *
 * Selection notification order is immediate then deferred, with the selection
 * diagnostic emitted only after both notifications. A failed selection notifies
 * neither coordinator.
 */
internal class ServiceChannelManager(
    private val channelRepository: ChannelRepository,
    private val providerRegistry: ChannelImplementationProviderRegistry,
    private val immediateSelection: (channelInstanceId: String) -> Unit,
    private val deferredSelection: (channelInstanceId: String) -> Unit,
    private val newChannelId: () -> String,
    private val log: (String) -> Unit,
) {
    fun createChannel(
        implementationId: ChannelImplementationId,
        name: String,
        payload: OpaqueJsonObject? = null,
    ): ChannelRepositoryMutationResult {
        val provider = when (val resolution = providerRegistry.resolve(implementationId)) {
            is ChannelProviderResolution.Available -> resolution.provider
            is ChannelProviderResolution.Missing -> {
                return ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.ProviderMigration(
                        definitionId = "new",
                        error = resolution.error,
                    ),
                )
            }
        }
        return channelRepository.addChannel(
            ChannelDefinition(
                id = newChannelId(),
                name = name,
                implementationId = implementationId,
                enabled = true,
                configSchemaVersion = provider.descriptor.configuration.currentSchemaVersion,
                configPayload = payload ?: provider.descriptor.configuration.defaultPayload(),
            ),
        )
    }

    fun updateChannelConfiguration(
        channelId: String,
        payload: OpaqueJsonObject,
    ): ChannelRepositoryMutationResult {
        val definition = channelRepository.catalogueState.value.definitions.find { it.id == channelId }
            ?: return channelRepository.updateChannel(channelId) { it }
        val provider = when (val resolution = providerRegistry.resolve(definition.implementationId)) {
            is ChannelProviderResolution.Available -> resolution.provider
            is ChannelProviderResolution.Missing -> {
                return ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.ProviderMigration(channelId, resolution.error),
                )
            }
        }
        return channelRepository.updateChannel(channelId) { old ->
            old.copy(
                configSchemaVersion = provider.descriptor.configuration.currentSchemaVersion,
                configPayload = payload,
            )
        }
    }

    fun selectChannel(id: String): Boolean {
        val previousId = channelRepository.catalogueState.value.activeChannelId
        val result = channelRepository.selectChannel(id)
        val selected = result is ChannelRepositoryMutationResult.Success
        if (selected) {
            immediateSelection(id)
            deferredSelection(id)
        }
        log(
            "CHANNEL_SELECT requested=$id previous=$previousId selected=$selected " +
                "active=${channelRepository.catalogueState.value.activeChannelId}",
        )
        return selected
    }
}