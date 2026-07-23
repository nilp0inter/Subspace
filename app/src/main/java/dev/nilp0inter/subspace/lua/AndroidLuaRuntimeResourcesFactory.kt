package dev.nilp0inter.subspace.lua

import android.content.ContentResolver
import android.net.Uri
import dev.nilp0inter.subspace.audio.OggEncoder
import dev.nilp0inter.subspace.audiofile.AudioFileAdapter
import dev.nilp0inter.subspace.audiofile.AudioFileLimits
import dev.nilp0inter.subspace.audiofile.AudioFileStaging
import dev.nilp0inter.subspace.dependency.PackageResourcesDeclaration
import dev.nilp0inter.subspace.mount.saf.SafGrantController
import dev.nilp0inter.subspace.mount.saf.vfs.AndroidSafDocumentGateway
import dev.nilp0inter.subspace.mount.saf.vfs.SafMountLeaseRevalidator
import dev.nilp0inter.subspace.mount.saf.vfs.SafVfsMountFactory
import dev.nilp0inter.subspace.mount.saf.vfs.SafVfsMountResolver
import dev.nilp0inter.subspace.resource.MountBindingState
import dev.nilp0inter.subspace.resource.MountBindingStore
import dev.nilp0inter.subspace.storage.LeaseOwner
import dev.nilp0inter.subspace.storage.MountLeaseRegistry
import dev.nilp0inter.subspace.storage.MountedFilesystem
import java.io.File
import java.util.UUID

/** Production composition for one installed Lua runtime generation. */
internal class AndroidLuaRuntimeResourcesFactory(
    private val contentResolver: ContentResolver,
    private val bindings: MountBindingStore,
    private val grants: SafGrantController,
    private val stagingRoot: File,
) : LuaRuntimeResourcesFactory {
    override fun create(
        request: dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest,
        declarations: PackageResourcesDeclaration,
    ): LuaRuntimeResources {
        val identity = request.capabilities.identity
        val implementationId = request.definition.implementationId
        val instanceId = request.definition.id
        val generation = identity.runtimeGeneration.value
        val mountFactory = SafVfsMountFactory(grants) { treeUri ->
            AndroidSafDocumentGateway(contentResolver, Uri.parse(treeUri))
        }
        val resolver = SafVfsMountResolver(
            store = bindings,
            factory = mountFactory,
            channelInstanceId = instanceId,
            implementationId = implementationId,
            generationProvider = { generation },
        )
        val leases = MountLeaseRegistry(
            owner = LeaseOwner(
                stateId = UUID.randomUUID().toString(),
                instanceId = instanceId,
                generation = generation,
            ),
            resolver = resolver,
            revalidator = SafMountLeaseRevalidator(
                store = bindings,
                grants = grants,
                implementationId = implementationId,
            ),
        )
        val filesystem = MountedFilesystem(leases)
        val generationStagingRoot = File(
            stagingRoot,
            UUID.randomUUID().toString(),
        )
        val audioFiles = LuaAudioFilePortFactory { recordings ->
            val limits = AudioFileLimits()
            AudioFileAdapter(
                leases = leases,
                recordings = recordings,
                encoder = OggEncoder(),
                staging = AudioFileStaging(generationStagingRoot, limits),
                limits = limits,
            )
        }
        val readiness = LuaMountReadinessStatus { declarationId ->
            val declared = declarations.mounts.any { it.id == declarationId }
            val binding = if (declared) {
                bindings.currentBinding(instanceId, implementationId, declarationId)
            } else {
                null
            }
            if (binding?.state == MountBindingState.ACTIVE) {
                binding.status.portable
            } else {
                "unavailable"
            }
        }
        return LuaRuntimeResources(
            storagePort = filesystem,
            audioFilePortFactory = audioFiles,
            mountReadinessStatus = readiness,
            close = {
                filesystem.close()
                generationStagingRoot.deleteRecursively()
            },
        )
    }
}
