package dev.nilp0inter.subspace.lua

import dev.nilp0inter.subspace.dependency.InstalledProviderId
import dev.nilp0inter.subspace.dependency.ValidatedPackageRevision
import dev.nilp0inter.subspace.lua.actor.ActorPolicy
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.InstalledProviderBinding
import dev.nilp0inter.subspace.model.ChannelPresentationMetadata

internal object LuaPackageMaterializer {
    internal fun materialize(
        revision: ValidatedPackageRevision,
        bridge: LuaKernelBridge,
        actorPolicy: ActorPolicy = ActorPolicy.startingEvidence(),
        validationBounds: ValidationBounds = ValidationBounds.DEFAULT,
    ): InstalledProviderBinding {
        val implementationId = InstalledProviderId.derive(revision.manifest.repositoryId)
        val presentation = ChannelPresentationMetadata(
            label = revision.manifest.presentation.label,
            summary = revision.manifest.presentation.summary,
            unavailableMessage = "Lua package is unavailable or failed to initialize."
        )
        val provider = LuaChannelImplementationProvider.create(
            implementationId = implementationId,
            presentation = presentation,
            programImage = revision.programImage,
            fingerprint = revision.fingerprint,
            actorFactory = { context, capabilities, kernelBridge, policy ->
                ActorRuntimeFactory.createForGeneration(context, capabilities, kernelBridge, policy)
            },
            bridge = bridge,
            actorPolicy = actorPolicy,
            validationBounds = validationBounds,
        )
        return InstalledProviderBinding(
            repositoryId = revision.manifest.repositoryId,
            expectedDigest = revision.digest,
            provider = provider
        )
    }
}
