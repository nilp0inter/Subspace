package dev.nilp0inter.subspace.lua

import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.dependency.PackageResourcesDeclaration
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.model.GenerationExecutionContext
import dev.nilp0inter.subspace.model.ProviderRevisionFingerprint
import dev.nilp0inter.subspace.lua.actor.ActorConstructResult
import dev.nilp0inter.subspace.lua.actor.ActorPolicy
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeCreationResult
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import dev.nilp0inter.subspace.model.ChannelConfigurationMigrationStep
import dev.nilp0inter.subspace.model.ChannelConfigurationProvider
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.ChannelImplementationDescriptor
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.ChannelPreparationTraits
import dev.nilp0inter.subspace.model.ChannelPresentationMetadata
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ProviderConfigurationResult
import dev.nilp0inter.subspace.model.ValidatedChannelConfiguration
import org.json.JSONArray
import org.json.JSONObject

internal data class LuaRuntimeResources(
    val storagePort: dev.nilp0inter.subspace.storage.MountedStoragePort,
    val audioFilePortFactory: LuaAudioFilePortFactory,
    val mountReadinessStatus: LuaMountReadinessStatus,
    val close: () -> Unit,
)

internal fun interface LuaRuntimeResourcesFactory {
    fun create(
        request: ChannelRuntimeConstructionRequest,
        declarations: PackageResourcesDeclaration,
    ): LuaRuntimeResources
}

/**
 * Host-supplied Lua implementation provider.
 *
 * Program source and identity remain outside catalogue configuration: [programImage]
 * is supplied to this constructor, is immutable, and is never persisted. Constructing
 * the provider itself is lazy and creates neither a Lua actor nor a Lua state.
 */
internal class LuaChannelImplementationProvider private constructor(
    private val implementationId: ChannelImplementationId,
    private val presentation: ChannelPresentationMetadata,
    private val imageResult: ProgramImageCreationResult,
    override val fingerprint: ProviderRevisionFingerprint,
    private val actorFactory: (GenerationExecutionContext, ChannelCapabilityScope, LuaKernelBridge, ActorPolicy) -> ActorRuntimeCreationResult,
    private val bridge: LuaKernelBridge,
    private val actorPolicy: ActorPolicy,
    private val validationBounds: ValidationBounds,
    private val logSink: PluginLogSink = NoOpPluginLogSink,
    private val configurationProvider: ChannelConfigurationProvider,
    private val configurationProviderFields: List<ChannelConfigurationField> = emptyList(),
    private val configurationRequiredCapabilities: Set<ChannelCapability> = emptySet(),
    private val resourceDeclarations: PackageResourcesDeclaration = PackageResourcesDeclaration(emptyList()),
    private val storagePort: dev.nilp0inter.subspace.storage.MountedStoragePort? = null,
    private val audioFilePortFactory: LuaAudioFilePortFactory? = null,
    private val mountReadinessStatus: LuaMountReadinessStatus? = null,
    private val runtimeResourcesFactory: LuaRuntimeResourcesFactory? = null,
) : ChannelImplementationProvider {

    companion object {
        private val RECOGNIZED_CALLBACKS = setOf(
            "startup", "handle_lifecycle", "handle_input", "handle_sos", "handle_readiness",
        )

        fun create(
            implementationId: ChannelImplementationId,
            presentation: ChannelPresentationMetadata,
            programImage: ImmutableProgramImage,
            fingerprint: ProviderRevisionFingerprint,
            actorFactory: (GenerationExecutionContext, ChannelCapabilityScope, LuaKernelBridge, ActorPolicy) -> ActorRuntimeCreationResult,
            bridge: LuaKernelBridge,
            actorPolicy: ActorPolicy = ActorPolicy.startingEvidence(),
            validationBounds: ValidationBounds = ValidationBounds.DEFAULT,
            logSink: PluginLogSink = NoOpPluginLogSink,
            configurationProvider: ChannelConfigurationProvider,
            configurationFields: List<ChannelConfigurationField> = emptyList(),
            requiredCapabilities: Set<ChannelCapability> = emptySet(),
            resourceDeclarations: PackageResourcesDeclaration = PackageResourcesDeclaration(emptyList()),
            storagePort: dev.nilp0inter.subspace.storage.MountedStoragePort? = null,
            audioFilePortFactory: LuaAudioFilePortFactory? = null,
            mountReadinessStatus: LuaMountReadinessStatus? = null,
            runtimeResourcesFactory: LuaRuntimeResourcesFactory? = null,
        ) = LuaChannelImplementationProvider(
            implementationId = implementationId,
            presentation = presentation,
            imageResult = ProgramImageCreationResult.Success(programImage),
            fingerprint = fingerprint,
            actorFactory = actorFactory,
            bridge = bridge,
            actorPolicy = actorPolicy,
            validationBounds = validationBounds,
            logSink = logSink,
            configurationProvider = configurationProvider,
            configurationProviderFields = configurationFields,
            configurationRequiredCapabilities = requiredCapabilities,
            resourceDeclarations = resourceDeclarations,
            storagePort = storagePort,
            audioFilePortFactory = audioFilePortFactory,
            mountReadinessStatus = mountReadinessStatus,
            runtimeResourcesFactory = runtimeResourcesFactory,
        )

        /**
         * Test-visible factory that accepts a [ProgramImageCreationResult] directly,
         * allowing tests to exercise failure-projection paths with incompatible images.
         */
        internal fun fromImageResult(
            implementationId: ChannelImplementationId,
            presentation: ChannelPresentationMetadata,
            imageResult: ProgramImageCreationResult,
            fingerprint: ProviderRevisionFingerprint,
            actorFactory: (GenerationExecutionContext, ChannelCapabilityScope, LuaKernelBridge, ActorPolicy) -> ActorRuntimeCreationResult,
            bridge: LuaKernelBridge,
            actorPolicy: ActorPolicy = ActorPolicy.startingEvidence(),
            validationBounds: ValidationBounds = ValidationBounds.DEFAULT,
            logSink: PluginLogSink = NoOpPluginLogSink,
            configurationProvider: ChannelConfigurationProvider,
            configurationFields: List<ChannelConfigurationField> = emptyList(),
            requiredCapabilities: Set<ChannelCapability> = emptySet(),
            resourceDeclarations: PackageResourcesDeclaration = PackageResourcesDeclaration(emptyList()),
            storagePort: dev.nilp0inter.subspace.storage.MountedStoragePort? = null,
        ) = LuaChannelImplementationProvider(
            implementationId = implementationId,
            presentation = presentation,
            imageResult = imageResult,
            fingerprint = fingerprint,
            actorFactory = actorFactory,
            bridge = bridge,
            actorPolicy = actorPolicy,
            validationBounds = validationBounds,
            logSink = logSink,
            configurationProvider = configurationProvider,
            configurationProviderFields = configurationFields,
            configurationRequiredCapabilities = requiredCapabilities,
            resourceDeclarations = resourceDeclarations,
            storagePort = storagePort,
        )
    }

    override val descriptor = ChannelImplementationDescriptor(
        implementationId = implementationId,
        presentation = presentation,
        configuration = configurationProvider,
        configurationFields = configurationProviderFields,
        requiredCapabilities = configurationRequiredCapabilities,
        preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        resourceDeclarations = resourceDeclarations,
    )

    /**
     * Validates requirements and immutable image before actor/state creation,
     * then creates exactly one actor and one state for this generation.
     */
    override suspend fun constructRuntime(
        request: ChannelRuntimeConstructionRequest,
    ): ChannelRuntimeConstructionResult {
        val programImage = when (val result = imageResult) {
            is ProgramImageCreationResult.Success -> result.image
            is ProgramImageCreationResult.Failure -> {
                return ChannelRuntimeConstructionResult.Failure(result.error.toProviderError())
            }
        }
        compatibilityFailure(programImage)?.let { return ChannelRuntimeConstructionResult.Failure(it) }
        when (val validation = ProgramImageValidator.validate(programImage, validationBounds)) {
            ProgramImageValidationResult.Success -> Unit
            is ProgramImageValidationResult.Failure -> {
                return ChannelRuntimeConstructionResult.Failure(validation.error.toProviderError())
            }
        }

        val actor = when (
            val result = actorFactory(
                request.generationContext,
                request.capabilities,
                bridge,
                actorPolicy,
            )
        ) {
            is ActorRuntimeCreationResult.Success -> result.actor
            is ActorRuntimeCreationResult.Failure -> {
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.detail))
            }
        }

        val constructResult = try {
            actor.construct()
        } catch (error: Throwable) {
            actor.close()
            return ChannelRuntimeConstructionResult.Failure(constructionFailure(error.message ?: "actor construction failed"))
        }
        val stateHandle = when (val result = constructResult) {
            is ActorConstructResult.Success -> result.stateHandle
            ActorConstructResult.AlreadyConstructed -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure("actor state was already constructed"))
            }
            is ActorConstructResult.FatalFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
        }

        // Install the immutable instance identity and declared resource
        // authority before any package source is evaluated.
        when (val rc = bridge.setResourceContext(stateHandle, buildResourceContextJson(request))) {
            is LuaKernelOutcome.Completed -> Unit
            is LuaKernelOutcome.ValidationFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(rc.diagnostic))
            }
            else -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(
                    constructionFailure("resource context installation failed"),
                )
            }
        }


        val imageLoad = try {
            bridge.loadProgramImage(
                handle = stateHandle,
                entryPoint = programImage.entryPoint,
                sourceMap = programImage.sourceMap,
            )
        } catch (error: Throwable) {
            actor.close()
            return ChannelRuntimeConstructionResult.Failure(
                constructionFailure(error.message ?: "program image loading failed"),
            )
        }
        val callbacks = when (val result = imageLoad) {
            is LuaKernelOutcome.Completed -> {
                val parsedCallbacks = callbackHandles(stateHandle, result.value)
                if (parsedCallbacks.isFailure) {
                    actor.close()
                    return ChannelRuntimeConstructionResult.Failure(
                        constructionFailure(parsedCallbacks.exceptionOrNull()?.message ?: "invalid callback list"),
                    )
                }
                parsedCallbacks.getOrThrow()
            }
            is LuaKernelOutcome.ValidationFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
            is LuaKernelOutcome.SyntaxFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
            is LuaKernelOutcome.RuntimeFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
            is LuaKernelOutcome.MemoryFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
            is LuaKernelOutcome.Interrupted -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(
                    constructionFailure(result.diagnostic ?: "program image evaluation interrupted"),
                )
            }
            else -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(
                    constructionFailure("unexpected program image outcome: ${result::class.simpleName}"),
                )
            }
        }

        val runtimeResources = runtimeResourcesFactory?.create(request, resourceDeclarations)
        return ChannelRuntimeConstructionResult.Success(
            LuaAdapterRuntime(
                definition = request.definition,
                actor = actor,
                generationContext = request.generationContext,
                stateHandle = stateHandle,
                bridge = bridge,
                callbacks = callbacks,
                configuration = request.configuration,
                logSink = logSink,
                capabilities = request.capabilities,
                initialSummary = presentation.summary,
                storagePort = runtimeResources?.storagePort ?: storagePort,
                audioFilePortFactory =
                    runtimeResources?.audioFilePortFactory ?: audioFilePortFactory,
                resourceDeclarations = resourceDeclarations,
                mountReadinessStatus =
                    runtimeResources?.mountReadinessStatus ?: mountReadinessStatus,
                resourceClose = runtimeResources?.close,
            ),
        )
    }

    /**
     * Build the kernel resource-context JSON from the declared capabilities and
     * `resources.mounts`. Mount `status` reflects the resolved live binding; the
     * generic binding layer supplies it per generation — declared mounts default
     * to `available` here and fail closed at operation time via the storage port
     * when no live grant backs them.
     */
    private fun buildResourceContextJson(request: ChannelRuntimeConstructionRequest): String {
        val storageDeclared = ChannelCapability.StorageFiles in request.capabilities.declaredCapabilities
        val audioFilesDeclared =
            ChannelCapability.AudioFiles in request.capabilities.declaredCapabilities
        val mounts = JSONObject()
        for (mount in resourceDeclarations.mounts) {
            mounts.put(
                mount.id,
                JSONObject()
                    .put("access", mount.access.value)
                    .put("status", "available"),
            )
        }
        return JSONObject()
            .put("instanceId", request.definition.id)
            .put("storageFiles", storageDeclared)
            .put("audioFiles", audioFilesDeclared)
            .put("mounts", mounts)
            .toString()
    }

    /** Exact version mapping happens before all actor/state work. */
    private fun compatibilityFailure(programImage: ImmutableProgramImage): ChannelProviderError.RuntimeCompatibilityFailure? = when {
        programImage.requirements.luaVersion != LUA_VERSION -> ChannelProviderError.RuntimeCompatibilityFailure(
            implementationId = descriptor.implementationId,
            requirement = "luaVersion",
            requiredVersion = programImage.requirements.luaVersion,
            supportedVersion = LUA_VERSION,
        )
        programImage.requirements.apiVersion != API_VERSION -> ChannelProviderError.RuntimeCompatibilityFailure(
            implementationId = descriptor.implementationId,
            requirement = "apiVersion",
            requiredVersion = programImage.requirements.apiVersion,
            supportedVersion = API_VERSION,
        )
        else -> null
    }

    private fun constructionFailure(detail: String): ChannelProviderError.RuntimeConstructionFailed =
        ChannelProviderError.RuntimeConstructionFailed(descriptor.implementationId, detail)

    private fun callbackHandles(
        stateHandle: LuaStateHandle,
        encodedNames: String?,
    ): Result<Map<String, LuaCallbackHandle>> = runCatching {
        val names = JSONArray(encodedNames ?: error("missing callback list"))
        val callbacks = buildMap {
            for (index in 0 until names.length()) {
                val name = names.opt(index) as? String ?: error("callback name at index $index is not a string")
                if (name !in RECOGNIZED_CALLBACKS) continue
                check(put(name, LuaCallbackHandle(stateHandle, name)) == null) { "duplicate callback '$name'" }
            }
        }
        require("startup" in callbacks) { "required callback 'startup' is missing" }
        callbacks
    }

    private fun ProgramImageValidationError.toProviderError(): ChannelProviderError = when (this) {
        is ProgramImageValidationError.IncompatibleRequirements -> ChannelProviderError.RuntimeCompatibilityFailure(
            implementationId = descriptor.implementationId,
            requirement = requirement,
            requiredVersion = requiredVersion,
            supportedVersion = supportedVersion,
        )
        else -> constructionFailure(message)
    }

}

