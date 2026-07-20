package dev.nilp0inter.subspace.model

import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.lua.actor.ActorGenerationGate
import dev.nilp0inter.subspace.service.ChannelRuntime
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/** Typed failures returned by provider registration, configuration, and construction. */
sealed interface ChannelProviderError {
    val implementationId: ChannelImplementationId
    val message: String

    data class DuplicateRegistration(
        override val implementationId: ChannelImplementationId,
    ) : ChannelProviderError {
        override val message = "Implementation provider $implementationId is already registered"
    }

    data class MissingProvider(
        override val implementationId: ChannelImplementationId,
    ) : ChannelProviderError {
        override val message = "Implementation provider $implementationId is not registered"
    }

    data class UnsupportedSchemaVersion(
        override val implementationId: ChannelImplementationId,
        val schemaVersion: Int,
        val currentSchemaVersion: Int,
    ) : ChannelProviderError {
        override val message =
            "Provider $implementationId does not support schema $schemaVersion (current $currentSchemaVersion)"
    }

    data class InvalidConfiguration(
        override val implementationId: ChannelImplementationId,
        val schemaVersion: Int,
        val detail: String,
    ) : ChannelProviderError {
        override val message = "Invalid configuration for $implementationId schema $schemaVersion: $detail"
    }

    data class MigrationFailed(
        override val implementationId: ChannelImplementationId,
        val fromSchemaVersion: Int,
        val detail: String,
    ) : ChannelProviderError {
        override val message =
            "Could not migrate $implementationId configuration from schema $fromSchemaVersion: $detail"
    }

    data class RuntimeConstructionFailed(
        override val implementationId: ChannelImplementationId,
        val detail: String,
    ) : ChannelProviderError {
        override val message = "Could not construct runtime for $implementationId: $detail"
    }

    data class RuntimeCompatibilityFailure(
        override val implementationId: ChannelImplementationId,
        val requirement: String,
        val requiredVersion: String,
        val supportedVersion: String,
    ) : ChannelProviderError {
        override val message =
            "Runtime compatibility failure for $implementationId: $requirement requires $requiredVersion (supported $supportedVersion)"
    }

    /**
     * An installed package provider that was committed to the installed-package index but
     * could not be materialized (archive corruption, compatibility failure, integrity
     * mismatch, etc.). The provider remains in the durable catalogue but cannot construct
     * a runtime until the user explicitly updates, rolls back, or removes the package.
     *
     * [category] and [detail] are an exhaustive normalized projection of the underlying
     * typed package failure; the model layer does not import or depend on the package
     * failure sealed hierarchy. An adapter in the dependency layer performs the mapping.
     */
    data class PackageUnavailable(
        override val implementationId: ChannelImplementationId,
        val category: PackageUnavailableCategory,
        val detail: PackageUnavailableDetail,
    ) : ChannelProviderError {
        override val message =
            "Installed package provider $implementationId is unavailable: $category/$detail"
    }

    /**
     * Normalized package-unavailability category mirroring the package failure hierarchy.
     * Adding a new package failure category REQUIRES adding a matching entry here.
     */
    enum class PackageUnavailableCategory {
        FORMAT,
        IDENTITY,
        COMPATIBILITY,
        INTEGRITY,
        STORAGE,
        RECOVERY,
        MUTATION,
        ROLLBACK,
        LOADING,
        SHUTDOWN,
    }

    /**
     * Exhaustive normalized failure-code enum. Every package failure detail across all
     * categories MUST have exactly one matching entry. The adapter enforces exhaustiveness
     * at compile time via a sealed `when` over the source hierarchy.
     */
    enum class PackageUnavailableDetail {
        INVALID_ZIP,
        UNEXPECTED_ENTRY,
        MISSING_MANIFEST,
        MALFORMED_MANIFEST,
        DUPLICATE_KEYS,
        UNKNOWN_FIELDS,
        INVALID_ENTRY_MODULE,
        INVALID_MODULE_GRAMMAR,
        COLLISION,
        BYTECODE_PROHIBITED,
        UNSUPPORTED_COMPRESSION,
        ENCRYPTED_ENTRY,
        BOUNDS_EXCEEDED,
        REPOSITORY_ID_MISMATCH,
        RESERVED_NAMESPACE_CLAIM,
        UNSUPPORTED_MANIFEST_VERSION,
        LUA_VERSION_INCOMPATIBLE,
        API_VERSION_INCOMPATIBLE,
        DIGEST_MISMATCH,
        CORRUPTED_ARCHIVE,
        HASH_COMPUTATION_FAILED,
        WRITE_FAILED,
        COMMIT_FAILED,
        INSUFFICIENT_SPACE,
        INDEX_CORRUPT,
        RECOVERY_INDEX_INVALID,
        ORPHAN_CLEANUP_FAILED,
        COMMIT_STATE_AMBIGUOUS,
        SERIALIZATION_VIOLATION,
        CONCURRENT_MUTATION,
        STAGE_FAILED,
        NOT_INSTALLED,
        NO_ROLLBACK_REVISION,
        ROLLBACK_VALIDATION_FAILED,
        LOAD_CANCELLED,
        STALE_PUBLICATION,
        LOAD_TIMEOUT,
        RECONCILIATION_FAILED,
        PUBLICATION_REJECTED,
        SHUTDOWN_IN_PROGRESS,
        TRANSACTION_ABORTED,
    }
}

sealed interface ProviderConfigurationResult {
    data class Success(val configuration: ValidatedChannelConfiguration) : ProviderConfigurationResult
    data class Failure(val error: ChannelProviderError) : ProviderConfigurationResult
}

data class ValidatedChannelConfiguration(
    val implementationId: ChannelImplementationId,
    val schemaVersion: Int,
    val payload: OpaqueJsonObject,
)

/** A provider-controlled, one-step schema migration result. */
sealed interface ChannelConfigurationMigrationStep {
    data class Success(val payload: OpaqueJsonObject) : ChannelConfigurationMigrationStep
    data class Failure(val error: ChannelProviderError) : ChannelConfigurationMigrationStep
}

/**
 * Provider-owned deterministic configuration boundary. It accepts and returns opaque objects
 * so host catalogue persistence cannot discard fields added by a newer provider.
 */
interface ChannelConfigurationProvider {
    val implementationId: ChannelImplementationId
    val currentSchemaVersion: Int

    fun defaultPayload(): OpaqueJsonObject

    fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult

    /** Migrates exactly [fromSchemaVersion] to its next integer version. */
    fun migrateStep(
        fromSchemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ChannelConfigurationMigrationStep

    fun migrateAndValidate(
        schemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ProviderConfigurationResult {
        if (schemaVersion > currentSchemaVersion || schemaVersion < 1) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(
                    implementationId,
                    schemaVersion,
                    currentSchemaVersion,
                ),
            )
        }
        var version = schemaVersion
        var migratedPayload = payload
        while (version < currentSchemaVersion) {
            when (val step = migrateStep(version, migratedPayload)) {
                is ChannelConfigurationMigrationStep.Success -> {
                    migratedPayload = step.payload
                    version += 1
                }
                is ChannelConfigurationMigrationStep.Failure -> return ProviderConfigurationResult.Failure(step.error)
            }
        }
        return validate(version, migratedPayload)
    }
}

data class ChannelPresentationMetadata(
    val label: String,
    val summary: String,
    val unavailableMessage: String,
)

sealed interface ChannelConfigurationField {
    val id: String
    val label: String
    val help: String?
    val required: Boolean

    data class BooleanField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
    ) : ChannelConfigurationField

    data class TextField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
        val multiline: Boolean = false,
    ) : ChannelConfigurationField

    data class ChoiceField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
        val choices: List<Choice>,
    ) : ChannelConfigurationField {
        data class Choice(val id: String, val label: String)
    }

    /**
     * Provider-declared choice metadata resolved by the host at editor time. A provider retains
     * only the selected scalar ID; it never receives a repository, SDK client, or UI state.
     */
    data class DynamicChoiceField(
        override val id: String,
        override val label: String,
        val source: DynamicConfigurationChoiceSource,
        override val help: String? = null,
        val dependsOnFieldId: String? = null,
        /** Optional scalar condition for rendering a dependent field. */
        val visibleWhenFieldId: String? = null,
        val visibleWhenValue: String? = null,
        override val required: Boolean = true,
    ) : ChannelConfigurationField {
        init {
            require(dependsOnFieldId?.isNotBlank() != false) {
                "Dynamic choice dependency field ID must not be blank"
            }
            require(visibleWhenFieldId?.isNotBlank() != false) {
                "Dynamic choice visibility field ID must not be blank"
            }
            require((visibleWhenFieldId == null) == (visibleWhenValue == null)) {
                "Dynamic choice visibility requires both field ID and expected value"
            }
            require(source != DynamicConfigurationChoiceSource.OPENAI_MODELS || dependsOnFieldId != null) {
                "OpenAI model choices must depend on a profile field"
            }
        }
    }

    data class NumberField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
        val minimum: Long? = null,
        val maximum: Long? = null,
    ) : ChannelConfigurationField

    /** Host UI owns directory acquisition; providers receive only the resulting string value. */
    data class DirectoryField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
    ) : ChannelConfigurationField
}

/** Host-owned resources that providers may reference declaratively from configuration schema. */
enum class DynamicConfigurationChoiceSource {
    OPENAI_CONNECTION_PROFILES,
    OPENAI_MODELS,
    TEXT_OUTPUT_PROFILES,
}

/** Input passed by an editor to its host resolver; it carries scalar dependency values only. */
data class DynamicConfigurationChoiceRequest(
    val source: DynamicConfigurationChoiceSource,
    val dependencyValue: String? = null,
)

/**
 * Host-facing resolver for declarative choice sources. Providers declare sources but never hold
 * this resolver, preventing repository, SDK, and UI-state injection into provider code.
 */
fun interface DynamicConfigurationChoiceResolver {
    suspend fun resolve(request: DynamicConfigurationChoiceRequest): DynamicConfigurationChoiceResolution
}

data class DynamicConfigurationChoice(
    val id: String,
    val label: String,
) {
    init {
        require(id.isNotBlank()) { "Dynamic configuration choice ID must not be blank" }
        require(label.isNotBlank()) { "Dynamic configuration choice label must not be blank" }
    }
}

/** Host-normalized state for asynchronous or unavailable choice sources. */
sealed interface DynamicConfigurationChoiceResolution {
    data object Loading : DynamicConfigurationChoiceResolution
    data class Available(val choices: List<DynamicConfigurationChoice>) : DynamicConfigurationChoiceResolution
    data class Unavailable(val reason: DynamicConfigurationChoiceUnavailableReason) : DynamicConfigurationChoiceResolution
}

enum class DynamicConfigurationChoiceUnavailableReason {
    DEPENDENCY_MISSING,
    SOURCE_UNAVAILABLE,
    DISCOVERY_FAILED,
    HOST_NOT_READY,
}

data class ChannelPreparationTraits(
    val supportsRecoverablePreparation: Boolean,
)

data class ChannelImplementationDescriptor(
    val implementationId: ChannelImplementationId,
    val presentation: ChannelPresentationMetadata,
    val configuration: ChannelConfigurationProvider,
    val configurationFields: List<ChannelConfigurationField>,
    val requiredCapabilities: Set<ChannelCapability>,
    val preparationTraits: ChannelPreparationTraits,
) {
    init {
        require(configuration.implementationId == implementationId) {
            "Configuration provider ID must match descriptor ID"
        }
        require(configuration.currentSchemaVersion > 0) {
            "Configuration provider version must be positive"
        }
        require(configurationFields.map { it.id }.all { it.isNotBlank() }) {
            "Configuration field IDs must not be blank"
        }
        require(configurationFields.map { it.id }.distinct().size == configurationFields.size) {
            "Configuration field IDs must be unique"
        }
        val fieldIds = configurationFields.map(ChannelConfigurationField::id).toSet()
        configurationFields.filterIsInstance<ChannelConfigurationField.DynamicChoiceField>().forEach { field ->
            field.dependsOnFieldId?.let { dependencyId ->
                require(dependencyId != field.id && dependencyId in fieldIds) {
                    "Dynamic choice field ${field.id} must depend on another declared field"
                }
            }
        }
    }
}
/**
 * Provider-neutral generation execution context.
 *
 * Supplies typed generation-bound admission for internal timers and
 * background tasks plus a liveness query. Does NOT expose CoroutineScope,
 * RuntimeGenerationInvocationGate, CapabilityScopeIdentity,
 * RuntimeGeneration, or any other internal type.
 *
 * The context is bound to a single generation and rejects operations
 * initiated after that generation is closed or replaced.
 *
 * Sealed so only host-owned implementation types exist.
 */
sealed interface GenerationExecutionContext {
    /** Stable channel instance identifier. Consistent across generations. */
    val instanceId: String

    /**
     * Check whether the generation is still active (not closed or replaced).
     * Functions return false/throw after the generation closes.
     */
    fun isActive(): Boolean

    /**
     * Schedule a one-shot timer bound to this generation. The callback fires
     * at most once while live and is suppressed after close. The accepted
     * handle cancels the timer idempotently.
     */
    fun scheduleTimer(
        delaySeconds: Double,
        callback: suspend () -> Unit,
    ): GenerationAdmission<Disposable>

    /**
     * Admit a generation-bound background task. During construction and
     * activation, admission reserves bounded capacity and stages the task;
     * it cannot execute yet. After the registry publishes generation
     * readiness, later admissions become runnable after the current
     * invocation slice.
     */
    fun admitTask(task: suspend () -> Unit): GenerationAdmission<Unit>
}

/** Internal actor adapter port; never exposed through the provider-facing context contract. */
internal interface ActorRuntimeHostContext : GenerationExecutionContext {
    val actorIdentity: CapabilityScopeIdentity
    val actorParentScope: CoroutineScope
    val actorGate: ActorGenerationGate
    fun discardActorStagedTasks()
}

sealed interface GenerationAdmission<out T> {
    data class Accepted<T>(val value: T) : GenerationAdmission<T>
    data class Rejected(val reason: GenerationAdmissionRejection) : GenerationAdmission<Nothing>
}

enum class GenerationAdmissionRejection {
    CLOSED,
    CAPACITY_EXHAUSTED,
}

interface Disposable {
    fun dispose()  // cancel the timer; idempotent
}

data class ChannelRuntimeConstructionRequest(
    val definition: ChannelDefinition,
    val configuration: ValidatedChannelConfiguration,
    val capabilities: ChannelCapabilityScope,
    val generationContext: GenerationExecutionContext,
) {
    init {
        require(definition.implementationId == configuration.implementationId) {
            "Validated configuration provider must match its definition"
        }
        require(definition.configSchemaVersion == configuration.schemaVersion) {
            "Runtime construction requires the definition's current validated schema"
        }
        require(definition.configPayload == configuration.payload) {
            "Runtime construction requires the definition's validated payload"
        }
    }
}

sealed interface ChannelRuntimeConstructionResult {
    data class Success(val runtime: ChannelRuntime) : ChannelRuntimeConstructionResult
    data class Failure(val error: ChannelProviderError) : ChannelRuntimeConstructionResult
}

@JvmInline
public value class ProviderRevisionFingerprint(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Fingerprint must not be blank"
        }
    }

    public companion object {
        public val BUILTIN = ProviderRevisionFingerprint("builtin")
        public fun fromDigest(digest: dev.nilp0inter.subspace.dependency.ArtifactDigest): ProviderRevisionFingerprint {
            return ProviderRevisionFingerprint(digest.value)
        }
    }
}

interface ChannelImplementationProvider {
    val descriptor: ChannelImplementationDescriptor
    val fingerprint: ProviderRevisionFingerprint
        get() = ProviderRevisionFingerprint.BUILTIN

    suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult
}

sealed interface ChannelProviderRegistrationResult {
    data object Registered : ChannelProviderRegistrationResult
    data class Rejected(val error: ChannelProviderError) : ChannelProviderRegistrationResult
}

sealed interface ChannelProviderResolution {
    data class Available(val provider: ChannelImplementationProvider) : ChannelProviderResolution
    data class Missing(val error: ChannelProviderError.MissingProvider) : ChannelProviderResolution
    data class Unavailable(val error: ChannelProviderError.PackageUnavailable) : ChannelProviderResolution
}

sealed interface ChannelDescriptorResolution {
    data class Available(val descriptor: ChannelImplementationDescriptor) : ChannelDescriptorResolution
    data class Missing(val error: ChannelProviderError.MissingProvider) : ChannelDescriptorResolution
}

interface ChannelImplementationDescriptorResolver {
    fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution
}

public data class InstalledProviderBinding(
    val repositoryId: dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity,
    val expectedDigest: dev.nilp0inter.subspace.dependency.ArtifactDigest,
    val provider: ChannelImplementationProvider,
)

public sealed interface InstalledProvidersPublicationResult {
    public data class Success(val snapshotRevision: Long) : InstalledProvidersPublicationResult
    public data class Rejected(val error: InstalledProvidersRejectionReason) : InstalledProvidersPublicationResult
}

public sealed interface InstalledProvidersRejectionReason {
    public data class InvalidId(val id: ChannelImplementationId, val detail: String) : InstalledProvidersRejectionReason
    public data class ReservedCollision(val id: ChannelImplementationId) : InstalledProvidersRejectionReason
    public data class AgreementMismatch(val id: ChannelImplementationId, val detail: String) : InstalledProvidersRejectionReason
    public data class MissingRevision(val id: ChannelImplementationId) : InstalledProvidersRejectionReason
    public data class DuplicateValue(val id: ChannelImplementationId) : InstalledProvidersRejectionReason
    public data class RevisionOverflow(val message: String) : InstalledProvidersRejectionReason
}

private data class InstalledProviderGlobalFallback(
    val category: ChannelProviderError.PackageUnavailableCategory,
    val detail: ChannelProviderError.PackageUnavailableDetail,
)

private data class RegistryState(
    val revision: Long,
    val resolutionMap: Map<ChannelImplementationId, ChannelImplementationProvider>,
    val installedBindings: Map<ChannelImplementationId, InstalledProviderBinding>,
    val installedFailures: Map<ChannelImplementationId, ChannelProviderError.PackageUnavailable>,
    val globalFallback: InstalledProviderGlobalFallback? = null,
)

private data class ValidatedCandidateInfo(
    val id: ChannelImplementationId,
    val binding: InstalledProviderBinding,
    val expectedFingerprint: ProviderRevisionFingerprint,
)

/** Deterministic insertion-ordered registry; a duplicate never replaces the original provider. */
class ChannelImplementationProviderRegistry : ChannelImplementationDescriptorResolver {
    private val builtIns = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()

    @Volatile
    private var state: RegistryState = RegistryState(
        revision = 0L,
        resolutionMap = emptyMap(),
        installedBindings = emptyMap(),
        installedFailures = emptyMap(),
        globalFallback = null,
    )

    val snapshotRevision: Long
        get() = state.revision

    fun register(provider: ChannelImplementationProvider): ChannelProviderRegistrationResult {
        val id = provider.descriptor.implementationId
        return registerInternal(id, provider)
    }

    @Synchronized
    private fun registerInternal(id: ChannelImplementationId, provider: ChannelImplementationProvider): ChannelProviderRegistrationResult {
        if (state.revision > 0L) {
            return ChannelProviderRegistrationResult.Rejected(ChannelProviderError.DuplicateRegistration(id))
        }
        if (dev.nilp0inter.subspace.dependency.InstalledProviderId.isInstalled(id)) {
            return ChannelProviderRegistrationResult.Rejected(ChannelProviderError.DuplicateRegistration(id))
        }
        if (builtIns.containsKey(id)) {
            return ChannelProviderRegistrationResult.Rejected(ChannelProviderError.DuplicateRegistration(id))
        }
        builtIns[id] = provider
        updateState()
        return ChannelProviderRegistrationResult.Registered
    }

    fun publishInstalledProviders(
        candidate: Map<ChannelImplementationId, InstalledProviderBinding>,
        unavailable: Map<ChannelImplementationId, ChannelProviderError.PackageUnavailable> = emptyMap(),
    ): InstalledProvidersPublicationResult {
        if (state.revision == Long.MAX_VALUE) {
            return InstalledProvidersPublicationResult.Rejected(
                InstalledProvidersRejectionReason.RevisionOverflow("Monotonic revision overflowed")
            )
        }

        val validatedCandidates = ArrayList<ValidatedCandidateInfo>(candidate.size)
        val uniqueProviders = java.util.HashSet<ChannelImplementationProvider>()
        val uniqueRepoIds = java.util.HashSet<dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity>()
        val allIds = java.util.HashSet<ChannelImplementationId>()

        for ((id, binding) in candidate) {
            if (isBuiltInId(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.ReservedCollision(id)
                )
            }
            val provider = binding.provider
            val providerId = provider.descriptor.implementationId
            val configId = provider.descriptor.configuration.implementationId
            val providerFingerprint = provider.fingerprint
            val expectedFingerprint = ProviderRevisionFingerprint.fromDigest(binding.expectedDigest)

            val expectedDerivedId = dev.nilp0inter.subspace.dependency.InstalledProviderId.derive(binding.repositoryId)
            if (id != expectedDerivedId) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.InvalidId(id, "ID does not match derived identity: expected $expectedDerivedId")
                )
            }
            if (providerId != id) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.AgreementMismatch(id, "Provider descriptor ID does not match key")
                )
            }
            if (configId != id) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.AgreementMismatch(id, "Provider configuration ID does not match key")
                )
            }
            if (providerFingerprint != expectedFingerprint) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.AgreementMismatch(id, "Provider fingerprint does not match expected fingerprint")
                )
            }
            if (!uniqueProviders.add(provider)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.DuplicateValue(id)
                )
            }
            if (!uniqueRepoIds.add(binding.repositoryId)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.DuplicateValue(id)
                )
            }
            if (!allIds.add(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.DuplicateValue(id)
                )
            }

            validatedCandidates.add(ValidatedCandidateInfo(id, binding, expectedFingerprint))
        }

        // Validate unavailable entries: canonical, installed-namespace, disjoint from candidate,
        // implementationId agrees with the key, and no duplicate IDs.
        for ((id, error) in unavailable) {
            if (isBuiltInId(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.ReservedCollision(id)
                )
            }
            if (!dev.nilp0inter.subspace.dependency.InstalledProviderId.isInstalled(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.InvalidId(id, "Unavailable entry is not a canonical installed provider ID")
                )
            }
            if (error.implementationId != id) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.AgreementMismatch(id, "Unavailable error implementationId does not match key")
                )
            }
            if (!allIds.add(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.DuplicateValue(id)
                )
            }
        }

        return publishInternal(validatedCandidates, unavailable)
    }

    /**
     * Fail-closed publication: atomically replaces every installed binding and failure
     * entry with empty maps and sets an immutable global installed-store failure template.
     * The revision is incremented so observers see a new generation. Any canonical
     * github-repository ID that has no explicit entry will resolve to a [PackageUnavailable]
     * using the supplied category/detail.
     *
     * Normal [publishInstalledProviders] clears the global fallback.
     */
    fun publishFailClosed(
        category: ChannelProviderError.PackageUnavailableCategory,
        detail: ChannelProviderError.PackageUnavailableDetail,
    ): InstalledProvidersPublicationResult {
        if (state.revision == Long.MAX_VALUE) {
            return InstalledProvidersPublicationResult.Rejected(
                InstalledProvidersRejectionReason.RevisionOverflow("Monotonic revision overflowed")
            )
        }
        return publishFailClosedInternal(category, detail)
    }

    @Synchronized
    private fun publishFailClosedInternal(
        category: ChannelProviderError.PackageUnavailableCategory,
        detail: ChannelProviderError.PackageUnavailableDetail,
    ): InstalledProvidersPublicationResult {
        if (state.revision == Long.MAX_VALUE) {
            return InstalledProvidersPublicationResult.Rejected(
                InstalledProvidersRejectionReason.RevisionOverflow("Monotonic revision overflowed")
            )
        }

        val newRevision = state.revision + 1L
        val newResolutionMap = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()
        newResolutionMap.putAll(builtIns)

        state = RegistryState(
            revision = newRevision,
            resolutionMap = java.util.Collections.unmodifiableMap(newResolutionMap),
            installedBindings = emptyMap(),
            installedFailures = emptyMap(),
            globalFallback = InstalledProviderGlobalFallback(
                category = category,
                detail = detail,
            ),
        )
        return InstalledProvidersPublicationResult.Success(newRevision)
    }

    @Synchronized
    private fun publishInternal(
        validatedCandidates: List<ValidatedCandidateInfo>,
        unavailable: Map<ChannelImplementationId, ChannelProviderError.PackageUnavailable>,
    ): InstalledProvidersPublicationResult {
        if (state.revision == Long.MAX_VALUE) {
            return InstalledProvidersPublicationResult.Rejected(
                InstalledProvidersRejectionReason.RevisionOverflow("Monotonic revision overflowed")
            )
        }

        for (info in validatedCandidates) {
            if (isBuiltInId(info.id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.ReservedCollision(info.id)
                )
            }
        }
        for ((id, _) in unavailable) {
            if (isBuiltInId(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.ReservedCollision(id)
                )
            }
        }

        val newRevision = state.revision + 1L
        val newResolutionMap = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()
        newResolutionMap.putAll(builtIns)
        val newInstalledBindings = LinkedHashMap<ChannelImplementationId, InstalledProviderBinding>()
        for (info in validatedCandidates) {
            newResolutionMap[info.id] = info.binding.provider
            newInstalledBindings[info.id] = info.binding
        }
        val newInstalledFailures = java.util.Collections.unmodifiableMap(LinkedHashMap(unavailable))
        state = RegistryState(
            revision = newRevision,
            resolutionMap = java.util.Collections.unmodifiableMap(newResolutionMap),
            installedBindings = java.util.Collections.unmodifiableMap(newInstalledBindings),
            installedFailures = newInstalledFailures,
            globalFallback = null,
        )
        return InstalledProvidersPublicationResult.Success(newRevision)
    }

    private fun isBuiltInId(id: ChannelImplementationId): Boolean {
        return id == BuiltInChannelImplementationIds.JOURNAL ||
                id == BuiltInChannelImplementationIds.KEYBOARD ||
                id == BuiltInChannelImplementationIds.OPENAI_AGENT ||
                id.value.startsWith("builtin:")
    }

    private fun updateState() {
        val newResolutionMap = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()
        newResolutionMap.putAll(builtIns)
        for ((id, binding) in state.installedBindings) {
            newResolutionMap[id] = binding.provider
        }
        state = RegistryState(
            revision = state.revision,
            resolutionMap = java.util.Collections.unmodifiableMap(newResolutionMap),
            installedBindings = state.installedBindings,
            installedFailures = state.installedFailures,
            globalFallback = state.globalFallback,
        )
    }

    fun resolve(implementationId: ChannelImplementationId): ChannelProviderResolution =
        state.resolutionMap[implementationId]?.let(ChannelProviderResolution::Available)
            ?: state.installedFailures[implementationId]?.let(ChannelProviderResolution::Unavailable)
            ?: state.globalFallback?.let { fallback ->
                if (dev.nilp0inter.subspace.dependency.InstalledProviderId.isInstalled(implementationId)) {
                    ChannelProviderResolution.Unavailable(
                        ChannelProviderError.PackageUnavailable(implementationId, fallback.category, fallback.detail)
                    )
                } else null
            }
            ?: ChannelProviderResolution.Missing(ChannelProviderError.MissingProvider(implementationId))

    fun descriptors(): List<ChannelImplementationDescriptor> = state.resolutionMap.values.map { it.descriptor }

    override fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution =
        state.resolutionMap[implementationId]?.descriptor?.let(ChannelDescriptorResolution::Available)
            ?: ChannelDescriptorResolution.Missing(ChannelProviderError.MissingProvider(implementationId))
}

object BuiltInChannelImplementationIds {
    val JOURNAL = ChannelImplementationId("builtin:journal")
    val KEYBOARD = ChannelImplementationId("builtin:keyboard")
    val OPENAI_AGENT = ChannelImplementationId("builtin:openai-agent")
}

interface ChannelConfigurationCodec<T> {
    fun decode(payload: OpaqueJsonObject): Result<T>
    fun encode(value: T): OpaqueJsonObject
}

data class JournalProviderConfiguration(
    val baseDirectory: String?,
    val saveVoice: Boolean,
    val saveText: Boolean,
)

object JournalProviderConfigurationCodec : ChannelConfigurationCodec<JournalProviderConfiguration> {
    override fun decode(payload: OpaqueJsonObject): Result<JournalProviderConfiguration> = runCatching {
        val objectValue = payload.toJsonObject()
        val baseDirectory = objectValue.opt("baseDirectory").let { value ->
            when (value) {
                null, JSONObject.NULL -> null
                is String -> value
                else -> error("baseDirectory must be a string or null")
            }
        }
        val saveVoice = objectValue.requireBoolean("saveVoice")
        val saveText = objectValue.requireBoolean("saveText")
        JournalProviderConfiguration(baseDirectory, saveVoice, saveText)
    }

    override fun encode(value: JournalProviderConfiguration): OpaqueJsonObject =
        OpaqueJsonObject.fromJsonObject(JSONObject().apply {
            put("baseDirectory", value.baseDirectory ?: JSONObject.NULL)
            put("saveVoice", value.saveVoice)
            put("saveText", value.saveText)
        })
}

data class KeyboardProviderConfiguration(val hostProfileKey: String)

object KeyboardProviderConfigurationCodec : ChannelConfigurationCodec<KeyboardProviderConfiguration> {
    override fun decode(payload: OpaqueJsonObject): Result<KeyboardProviderConfiguration> = runCatching {
        val key = payload.toJsonObject().requireString("hostProfile")
        require(key.split(":").let { it.size >= 2 && it.none(String::isBlank) }) {
            "hostProfile must use hostOs:layout[:variant]"
        }
        KeyboardProviderConfiguration(key)
    }

    override fun encode(value: KeyboardProviderConfiguration): OpaqueJsonObject =
        OpaqueJsonObject.fromJsonObject(JSONObject().put("hostProfile", value.hostProfileKey))
}

/** Per-channel OpenAI Agent settings. Endpoint and bearer credentials remain profile-owned. */
data class OpenAiAgentProviderConfiguration(
    val connectionProfileId: String,
    val modelId: String,
    val systemPrompt: String,
    val keyboardEnabled: Boolean,
    val keyboardProfileId: String?,
)

object OpenAiAgentProviderConfigurationCodec : ChannelConfigurationCodec<OpenAiAgentProviderConfiguration> {
    override fun decode(payload: OpaqueJsonObject): Result<OpenAiAgentProviderConfiguration> = runCatching {
        val value = payload.toJsonObject()
        val keyboardEnabled = value.requireBoolean("keyboardEnabled")
        val keyboardProfileId = value.opt("keyboardProfileId").let { profile ->
            when (profile) {
                null, JSONObject.NULL -> null
                is String -> profile
                else -> error("keyboardProfileId must be a string or null")
            }
        }
        OpenAiAgentProviderConfiguration(
            connectionProfileId = value.requireString("connectionProfileId"),
            modelId = value.requireString("modelId"),
            systemPrompt = value.requireString("systemPrompt"),
            keyboardEnabled = keyboardEnabled,
            keyboardProfileId = keyboardProfileId,
        )
    }

    override fun encode(value: OpenAiAgentProviderConfiguration): OpaqueJsonObject =
        OpaqueJsonObject.fromJsonObject(JSONObject().apply {
            put("connectionProfileId", value.connectionProfileId)
            put("modelId", value.modelId)
            put("systemPrompt", value.systemPrompt)
            put("keyboardEnabled", value.keyboardEnabled)
            if (value.keyboardEnabled) put("keyboardProfileId", value.keyboardProfileId)
        })
}

private abstract class VersionOneConfigurationProvider<T>(
    final override val implementationId: ChannelImplementationId,
    private val codec: ChannelConfigurationCodec<T>,
    private val additionalValidation: (T) -> String? = { null },
) : ChannelConfigurationProvider {
    final override val currentSchemaVersion = 1

    abstract override fun defaultPayload(): OpaqueJsonObject

    final override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult {
        if (schemaVersion != currentSchemaVersion) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(implementationId, schemaVersion, currentSchemaVersion),
            )
        }
        val decoded = codec.decode(payload).getOrElse { error ->
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(
                    implementationId,
                    schemaVersion,
                    error.message ?: "Malformed configuration payload",
                ),
            )
        }
        additionalValidation(decoded)?.let { detail ->
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(implementationId, schemaVersion, detail),
            )
        }
        return ProviderConfigurationResult.Success(
            ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
        )
    }

    final override fun migrateStep(
        fromSchemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
        ChannelProviderError.UnsupportedSchemaVersion(implementationId, fromSchemaVersion, currentSchemaVersion),
    )
}

private object JournalConfigurationProvider : VersionOneConfigurationProvider<JournalProviderConfiguration>(
    BuiltInChannelImplementationIds.JOURNAL,
    JournalProviderConfigurationCodec,
    additionalValidation = { configuration ->
        if (configuration.saveVoice || configuration.saveText) null else "at least one output must be enabled"
    },
) {
    override fun defaultPayload(): OpaqueJsonObject =
        JournalProviderConfigurationCodec.encode(JournalProviderConfiguration(null, saveVoice = true, saveText = true))
}


private object KeyboardConfigurationProvider : VersionOneConfigurationProvider<KeyboardProviderConfiguration>(
    BuiltInChannelImplementationIds.KEYBOARD,
    KeyboardProviderConfigurationCodec,
) {
    override fun defaultPayload(): OpaqueJsonObject =
        KeyboardProviderConfigurationCodec.encode(KeyboardProviderConfiguration("linux:us"))
}

private object OpenAiAgentConfigurationProvider : VersionOneConfigurationProvider<OpenAiAgentProviderConfiguration>(
    BuiltInChannelImplementationIds.OPENAI_AGENT,
    OpenAiAgentProviderConfigurationCodec,
    additionalValidation = { configuration ->
        when {
            configuration.connectionProfileId.isBlank() -> "connectionProfileId must not be blank"
            configuration.modelId.isBlank() -> "modelId must not be blank"
            configuration.systemPrompt.toByteArray(Charsets.UTF_8).size > OPENAI_AGENT_MAXIMUM_SYSTEM_PROMPT_BYTES ->
                "systemPrompt exceeds $OPENAI_AGENT_MAXIMUM_SYSTEM_PROMPT_BYTES UTF-8 bytes"
            configuration.keyboardEnabled && configuration.keyboardProfileId.isNullOrBlank() ->
                "keyboardProfileId is required when keyboardEnabled"
            else -> null
        }
    },
) {
    override fun defaultPayload(): OpaqueJsonObject =
        OpenAiAgentProviderConfigurationCodec.encode(
            OpenAiAgentProviderConfiguration("", "", "", keyboardEnabled = false, keyboardProfileId = null),
        )
}

/** Built-in metadata is declarative; service composition supplies their runtime constructors. */
object BuiltInChannelDescriptors {
    val journal = ChannelImplementationDescriptor(
        implementationId = BuiltInChannelImplementationIds.JOURNAL,
        presentation = ChannelPresentationMetadata("Journal", "LOCAL LOG", "Requires valid journal configuration."),
        configuration = JournalConfigurationProvider,
        configurationFields = listOf(
            ChannelConfigurationField.DirectoryField("baseDirectory", "Storage directory", required = false),
            ChannelConfigurationField.BooleanField("saveVoice", "Save voice recording"),
            ChannelConfigurationField.BooleanField("saveText", "Save transcript"),
        ),
        requiredCapabilities = setOf(ChannelCapability.Journal),
        preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
    )

    val keyboard = ChannelImplementationDescriptor(
        implementationId = BuiltInChannelImplementationIds.KEYBOARD,
        presentation = ChannelPresentationMetadata("Keyboard Channel", "BLE HID", "Requires active text-output availability."),
        configuration = KeyboardConfigurationProvider,
        configurationFields = listOf(
            ChannelConfigurationField.ChoiceField(
                id = "hostProfile",
                label = "Host profile",
                choices = listOf(
                    ChannelConfigurationField.ChoiceField.Choice("linux:us", "us [linux]"),
                ),
            ),
        ),
        requiredCapabilities = setOf(ChannelCapability.Transcription, ChannelCapability.TextOutput),
        preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = true),
    )

    val openAiAgent = ChannelImplementationDescriptor(
        implementationId = BuiltInChannelImplementationIds.OPENAI_AGENT,
        presentation = ChannelPresentationMetadata(
            label = "OpenAI Agent",
            summary = "ASYNC AGENT",
            unavailableMessage = "Requires an available connection profile, discovered model, and agent capabilities.",
        ),
        configuration = OpenAiAgentConfigurationProvider,
        configurationFields = listOf(
            ChannelConfigurationField.DynamicChoiceField("connectionProfileId", "Connection profile", source = DynamicConfigurationChoiceSource.OPENAI_CONNECTION_PROFILES),
            ChannelConfigurationField.DynamicChoiceField("modelId", "Model", source = DynamicConfigurationChoiceSource.OPENAI_MODELS, dependsOnFieldId = "connectionProfileId"),
            ChannelConfigurationField.TextField("systemPrompt", "System prompt", multiline = true),
            ChannelConfigurationField.BooleanField("keyboardEnabled", "Enable Keyboard tools"),
            ChannelConfigurationField.DynamicChoiceField(
                id = "keyboardProfileId",
                label = "Keyboard profile",
                source = DynamicConfigurationChoiceSource.TEXT_OUTPUT_PROFILES,
                visibleWhenFieldId = "keyboardEnabled",
                visibleWhenValue = "true",
                required = false,
            ),
        ),
        requiredCapabilities = setOf(
            ChannelCapability.Transcription,
            ChannelCapability.Synthesis,
            ChannelCapability.OpenAiModelDiscovery,
            ChannelCapability.OpenAiCompletion,
            ChannelCapability.AsynchronousConversation,
            ChannelCapability.DelayedPlayback,
            ChannelCapability.TextOutput,
        ),
        preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = true),
    )

    val all: List<ChannelImplementationDescriptor> = listOf(journal, keyboard, openAiAgent)

    val configurationResolver: ChannelImplementationDescriptorResolver = object : ChannelImplementationDescriptorResolver {
        private val descriptors = all.associateBy { it.implementationId }

        override fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution =
            descriptors[implementationId]?.let(ChannelDescriptorResolution::Available)
                ?: ChannelDescriptorResolution.Missing(ChannelProviderError.MissingProvider(implementationId))
    }
}

sealed interface ChannelCatalogueProviderMigrationResult {
    data class Success(
        val snapshot: ChannelCatalogueSnapshot,
        val changed: Boolean,
    ) : ChannelCatalogueProviderMigrationResult

    /** The original snapshot is intentionally retained and must not be committed or published. */
    data class Failure(
        val definitionId: String,
        val error: ChannelProviderError,
    ) : ChannelCatalogueProviderMigrationResult
}

/**
 * Migrates every available provider as one in-memory transaction. Missing providers are left
 * byte-for-byte opaque at the payload boundary. On configuration incompatibility the definition
 * is preserved unchanged so the runtime layer projects a typed unavailable result; no automatic
 * successor, default, or migrated payload is substituted.
 */
object ChannelCatalogueProviderMigrator {
    fun migrate(
        snapshot: ChannelCatalogueSnapshot,
        resolver: ChannelImplementationDescriptorResolver,
    ): ChannelCatalogueProviderMigrationResult {
        var changed = false
        val migrated = snapshot.definitions.map { definition ->
            when (val resolution = resolver.resolveDescriptor(definition.implementationId)) {
                is ChannelDescriptorResolution.Missing -> definition
                is ChannelDescriptorResolution.Available -> when (
                    val result = resolution.descriptor.configuration.migrateAndValidate(
                        definition.configSchemaVersion,
                        definition.configPayload,
                    )
                ) {
                    // Preserve the definition unchanged on incompatibility;
                    // runtime reconciliation projects the typed unavailable state.
                    is ProviderConfigurationResult.Failure -> definition
                    is ProviderConfigurationResult.Success -> {
                        val configuration = result.configuration
                        if (configuration.schemaVersion != definition.configSchemaVersion ||
                            configuration.payload != definition.configPayload
                        ) {
                            changed = true
                            definition.copy(
                                configSchemaVersion = configuration.schemaVersion,
                                configPayload = configuration.payload,
                            )
                        } else {
                            definition
                        }
                    }
                }
            }
        }
        return ChannelCatalogueProviderMigrationResult.Success(
            snapshot.copy(definitions = migrated),
            changed,
        )
    }
}

private fun JSONObject.requireBoolean(name: String): Boolean =
    opt(name).takeIf { it is Boolean } as? Boolean ?: error("$name must be a boolean")

private fun JSONObject.requireString(name: String): String =
    opt(name).takeIf { it is String } as? String ?: error("$name must be a string")

private const val OPENAI_AGENT_MAXIMUM_SYSTEM_PROMPT_BYTES = 16 * 1024
