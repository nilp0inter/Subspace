package dev.nilp0inter.subspace.dependency

import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ProviderRevisionFingerprint
import dev.nilp0inter.subspace.lua.ImmutableProgramImage
import java.util.Collections
import java.util.LinkedHashMap
import java.util.ArrayList
import java.util.LinkedHashSet

// 1. Precompile regex and allocation-free checks to satisfy performance directives
private val CANONICAL_MODULE_REGEX = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*$")
private val FIELD_ID_REGEX = Regex("^[a-z][a-z0-9_]*$")

private fun isPositiveDecimal(value: String): Boolean {
    if (value.isEmpty()) return false
    if (value[0] == '0') return false
    for (i in 0 until value.length) {
        val c = value[i]
        if (c < '0' || c > '9') return false
    }
    return true
}

private fun isLowercaseSha256(value: String): Boolean {
    if (value.length != 64) return false
    for (i in 0 until 64) {
        val c = value[i]
        if (!((c in '0'..'9') || (c in 'a'..'f'))) return false
    }
    return true
}

private fun escapeJson(s: String): String {
    val sb = java.lang.StringBuilder()
    for (i in 0 until s.length) {
        val c = s[i]
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code in 0x00..0x1F) {
                    sb.append(String.format("\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

private fun calculateDefaultPayloadSize(fields: List<ConfigurationFieldDeclaration>): Int {
    val sortedFields = fields.sortedBy { it.id }
    val sb = java.lang.StringBuilder()
    sb.append("{")
    for (i in sortedFields.indices) {
        if (i > 0) {
            sb.append(",")
        }
        val f = sortedFields[i]
        sb.append("\"").append(escapeJson(f.id)).append("\":")
        when (f) {
            is ConfigurationFieldDeclaration.BooleanField -> {
                sb.append(if (f.default) "true" else "false")
            }
            is ConfigurationFieldDeclaration.IntegerField -> {
                sb.append(f.default.toString())
            }
            is ConfigurationFieldDeclaration.StringField -> {
                sb.append("\"").append(escapeJson(f.default)).append("\"")
            }
        }
    }
    sb.append("}")
    return sb.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8).size
}

/**
 * 1.3: Host-domain types for durable GitHub repository identity.
 * Losslessly represents durable repository database IDs as canonical positive decimal strings.
 * Invalid/noncanonical IDs cannot be instantiated (throws IllegalArgumentException).
 */
@JvmInline
public value class GitHubRepositoryIdentity(val value: String) {
    init {
        require(isPositiveDecimal(value)) {
            "Repository ID must be a canonical positive decimal string without leading zeros: $value"
        }
    }
}

/**
 * 1.3: Host-domain coordinates representing the owner and repository.
 */
public data class GitHubRepositoryCoordinates(
    val owner: String,
    val repository: String
) {
    init {
        require(owner.isNotBlank() && !owner.contains("/")) {
            "Owner coordinates must be a nonblank string without slashes: $owner"
        }
        require(repository.isNotBlank() && !repository.contains("/")) {
            "Repository coordinates must be a nonblank string without slashes: $repository"
        }
    }
}

/**
 * 1.3: Host-domain type representing the exact release identity, tag, and prerelease.
 */
public data class GitHubReleaseIdentity(
    val releaseId: String,
    val tag: String,
    val isPrerelease: Boolean
) {
    init {
        require(isPositiveDecimal(releaseId)) {
            "Release ID must be a canonical positive decimal string: $releaseId"
        }
        require(tag.isNotBlank()) {
            "Release tag must not be blank: $tag"
        }
    }
}

/**
 * 1.3: Host-domain type representing the exact asset identity and asset name.
 */
public data class GitHubAssetIdentity(
    val assetId: String,
    val name: String
) {
    init {
        require(isPositiveDecimal(assetId)) {
            "Asset ID must be a canonical positive decimal string: $assetId"
        }
        require(name.isNotBlank()) {
            "Asset name must not be blank: $name"
        }
    }
}

/**
 * 1.4: Canonical installed-provider ID derivation and parsing/validation.
 * Installed IDs are exactly github-repository:<repositoryId>.
 * Rejects built-in, internal, test, and other host-reserved namespaces.
 */
public object InstalledProviderId {
    private const val PREFIX = "github-repository:"

    public fun derive(repositoryId: GitHubRepositoryIdentity): ChannelImplementationId {
        return ChannelImplementationId("$PREFIX${repositoryId.value}")
    }

    public fun parse(value: String): ChannelImplementationId {
        require(value.startsWith(PREFIX)) {
            "Installed provider ID must start with '$PREFIX': $value"
        }
        val repositoryIdStr = value.substring(PREFIX.length)
        val repositoryId = GitHubRepositoryIdentity(repositoryIdStr)
        return derive(repositoryId)
    }

    public fun isInstalled(id: ChannelImplementationId): Boolean {
        return id.value.startsWith(PREFIX) && runCatching {
            GitHubRepositoryIdentity(id.value.substring(PREFIX.length))
        }.isSuccess
    }
}

/**
 * 1.5: Immutable artifact digest value.
 * Validates that the digest is a lowercase SHA-256 hex string.
 */
@JvmInline
public value class ArtifactDigest(val value: String) {
    init {
        require(isLowercaseSha256(value)) {
            "Artifact digest must be a lowercase SHA-256 hex string: $value"
        }
    }
}


/**
 * 1.5: Immutable presentation values.
 */
public data class PackagePresentation(
    val label: String,
    val summary: String
) {
    init {
        require(label.isNotBlank()) { "Label must not be blank" }
        require(summary.isNotBlank()) { "Summary must not be blank" }
    }
}

/**
 * 1.5: Immutable runtime requirements.
 */
public data class RuntimeRequirements(
    val luaVersion: String,
    val apiVersion: String
) {
    init {
        require(luaVersion.isNotBlank()) { "Lua version must not be blank" }
        require(apiVersion.isNotBlank()) { "API version must not be blank" }
    }
}

/**
 * 1.5: Immutable source record combining repository, coordinates, release, and asset.
 */
public data class PackageSourceRecord(
    val repositoryId: GitHubRepositoryIdentity,
    val coordinates: GitHubRepositoryCoordinates,
    val release: GitHubReleaseIdentity,
    val asset: GitHubAssetIdentity,
    val ownerId: String
) {
    init {
        require(isPositiveDecimal(ownerId)) {
            "Publisher ID must be a canonical positive decimal string: $ownerId"
        }
    }
}

/**
 * 1.5: Immutable package manifest.
 */
public data class PackageManifest(
    val manifestVersion: Int,
    val repositoryId: GitHubRepositoryIdentity,
    val packageVersion: String,
    val entryModule: String,
    val presentation: PackagePresentation,
    val runtime: RuntimeRequirements,
    val configuration: PackageConfigurationDeclaration,
    val resources: PackageResourcesDeclaration,
    val capabilities: Set<String>
) {
    init {
        require(manifestVersion == 1) {
            "Manifest version must be exactly 1: $manifestVersion"
        }
        require(packageVersion.isNotBlank()) {
            "Package version must not be blank: $packageVersion"
        }
        require(entryModule.isNotBlank()) {
            "Entry module must not be blank: $entryModule"
        }
        require(entryModule.matches(CANONICAL_MODULE_REGEX)) {
            "Entry module name is invalid: $entryModule"
        }
        require(PackageCapability.ALL.containsAll(capabilities)) {
            "Capabilities must only contain known values from PackageCapability.ALL. Unknown capabilities: ${capabilities - PackageCapability.ALL}"
        }
    }
}

/**
 * 1.5: Immutable validated package revision.
 * Defensive copy is applied to sourceMap during construction.
 * Enforces fingerprint == digest-derived fingerprint.
 */
public class ValidatedPackageRevision(
    val digest: ArtifactDigest,
    val manifest: PackageManifest,
    val sourceRecord: PackageSourceRecord,
    sourceMap: Map<String, String>,
    val programImage: ImmutableProgramImage,
    val fingerprint: ProviderRevisionFingerprint
) {
    public val sourceMap: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(sourceMap))

    init {
        require(this.sourceMap.isNotEmpty()) { "Source map must not be empty" }
        require(fingerprint == ProviderRevisionFingerprint.fromDigest(digest)) {
            "Fingerprint $fingerprint must match digest-derived fingerprint for installed package revision: ${digest.value}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ValidatedPackageRevision) return false
        return digest == other.digest &&
                manifest == other.manifest &&
                sourceRecord == other.sourceRecord &&
                sourceMap == other.sourceMap &&
                programImage == other.programImage &&
                fingerprint == other.fingerprint
    }

    override fun hashCode(): Int {
        var result = digest.hashCode()
        result = 31 * result + manifest.hashCode()
        result = 31 * result + sourceRecord.hashCode()
        result = 31 * result + sourceMap.hashCode()
        result = 31 * result + programImage.hashCode()
        result = 31 * result + fingerprint.hashCode()
        return result
    }

    override fun toString(): String {
        return "ValidatedPackageRevision(digest=$digest, manifest=$manifest, sourceRecord=$sourceRecord, fingerprint=$fingerprint)"
    }
}

/**
 * 1.6: Sealed typed outcome hierarchy covering package failures.
 * Covers every category in the contract without raw error message/source payload leakage.
 */
public sealed interface PackageOutcome<out T> {
    public data class Success<out T>(val value: T) : PackageOutcome<T>
    public data class Failure(val error: PackageFailure) : PackageOutcome<Nothing>
}

/**
 * 1.6: Sealed package failure types.
 */
public sealed interface PackageFailure {
    public val category: FailureCategory
    public val implementationId: ChannelImplementationId?

    public enum class FailureCategory {
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
        CAPABILITY
    }

    public data class Format(
        val detail: FormatDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.FORMAT
    }

    public enum class FormatDetail {
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
        BOUNDS_EXCEEDED
    }

    public data class Capability(
        val detail: CapabilityDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.CAPABILITY
    }

    public enum class CapabilityDetail {
        UNKNOWN_CAPABILITY_ID,
        DUPLICATE_CAPABILITY_ID
    }

    public data class Identity(
        val detail: IdentityDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.IDENTITY
    }

    public enum class IdentityDetail {
        REPOSITORY_ID_MISMATCH,
        RESERVED_NAMESPACE_CLAIM
    }

    public data class Compatibility(
        val detail: CompatibilityDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.COMPATIBILITY
    }

    public enum class CompatibilityDetail {
        UNSUPPORTED_MANIFEST_VERSION,
        LUA_VERSION_INCOMPATIBLE,
        API_VERSION_INCOMPATIBLE
    }

    public data class Integrity(
        val detail: IntegrityDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.INTEGRITY
    }

    public enum class IntegrityDetail {
        DIGEST_MISMATCH,
        CORRUPTED_ARCHIVE,
        HASH_COMPUTATION_FAILED
    }

    public data class Storage(
        val detail: StorageDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.STORAGE
    }

    public enum class StorageDetail {
        WRITE_FAILED,
        COMMIT_FAILED,
        INSUFFICIENT_SPACE
    }

    public data class Recovery(
        val detail: RecoveryDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.RECOVERY
    }

    public enum class RecoveryDetail {
        INDEX_CORRUPT,
        RECOVERY_INDEX_INVALID,
        ORPHAN_CLEANUP_FAILED,
        COMMIT_STATE_AMBIGUOUS
    }

    public data class Mutation(
        val detail: MutationDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.MUTATION
    }

    public enum class MutationDetail {
        SERIALIZATION_VIOLATION,
        CONCURRENT_MUTATION,
        STAGE_FAILED,
        NOT_INSTALLED
    }

    public data class Rollback(
        val detail: RollbackDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.ROLLBACK
    }

    public enum class RollbackDetail {
        NO_ROLLBACK_REVISION,
        ROLLBACK_VALIDATION_FAILED
    }

    public data class Loading(
        val detail: LoadingDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.LOADING
    }

    public enum class LoadingDetail {
        LOAD_CANCELLED,
        STALE_PUBLICATION,
        LOAD_TIMEOUT,
        RECONCILIATION_FAILED,
        PUBLICATION_REJECTED
    }

    public data class Shutdown(
        val detail: ShutdownDetail,
        override val implementationId: ChannelImplementationId? = null
    ) : PackageFailure {
        override val category = FailureCategory.SHUTDOWN
    }

    public enum class ShutdownDetail {
        SHUTDOWN_IN_PROGRESS,
        TRANSACTION_ABORTED
    }
}

/**
 * 1.7: Finite host-configured validation bounds with positive defaults.
 * Rejects NaN/infinity and requires total-source >= per-module.
 */
public data class PackageValidationBounds(
    val maxArtifactBytes: Long,
    val maxEntryCount: Int,
    val maxManifestBytes: Int,
    val maxPathBytes: Int,
    val maxPerModuleBytes: Int,
    val maxTotalSourceBytes: Int,
    val maxExpansionRatio: Double
) {
    init {
        require(maxArtifactBytes > 0) { "maxArtifactBytes must be positive" }
        require(maxEntryCount > 0) { "maxEntryCount must be positive" }
        require(maxManifestBytes > 0) { "maxManifestBytes must be positive" }
        require(maxPathBytes > 0) { "maxPathBytes must be positive" }
        require(maxPerModuleBytes > 0) { "maxPerModuleBytes must be positive" }
        require(maxTotalSourceBytes > 0) { "maxTotalSourceBytes must be positive" }
        require(maxTotalSourceBytes >= maxPerModuleBytes) {
            "maxTotalSourceBytes ($maxTotalSourceBytes) must be >= maxPerModuleBytes ($maxPerModuleBytes)"
        }
        require(maxExpansionRatio > 0.0 && !maxExpansionRatio.isNaN() && !maxExpansionRatio.isInfinite()) {
            "maxExpansionRatio must be a positive finite number: $maxExpansionRatio"
        }
    }

    public companion object {
        public val DEFAULT = PackageValidationBounds(
            maxArtifactBytes = 4 * 1024 * 1024L, // 4 MB
            maxEntryCount = 256,
            maxManifestBytes = 64 * 1024, // 64 KB
            maxPathBytes = 256,
            maxPerModuleBytes = 512 * 1024, // 512 KB
            maxTotalSourceBytes = 2 * 1024 * 1024, // 2 MB
            maxExpansionRatio = 10.0
        )
    }
}

/**
 * 1.1: Bounded exact-key configuration limits.
 */
public object PackageConfigurationLimits {
    public const val MAX_FIELDS: Int = 32
    public const val MAX_FIELD_ID_BYTES: Int = 64
    public const val MAX_LABEL_BYTES: Int = 128
    public const val MAX_HELP_BYTES: Int = 512
    public const val MAX_CHOICES: Int = 256
    public const val MAX_STRING_VALUE_BYTES: Int = 16384 // 16 KiB
    public const val MAX_PAYLOAD_BYTES: Int = 65536 // 64 KiB
}

/**
 * Finite manifest-v1 resource declaration limits.
 */
public object PackageResourceLimits {
    public const val MAX_MOUNTS: Int = 8
    public const val MAX_MOUNT_ID_BYTES: Int = 64
    public const val MAX_LABEL_BYTES: Int = 128
    public const val MAX_HELP_BYTES: Int = 512
}

/**
 * Portable resource kinds accepted by manifest v1.
 */
public enum class PackageMountKind(public val value: String) {
    DIRECTORY_TREE("directory-tree")
}

/**
 * Portable mount access modes accepted by manifest v1.
 */
public enum class PackageMountAccess(public val value: String) {
    READ_WRITE("read-write")
}

/**
 * One immutable package-declared mount.
 */
public data class PackageMountDeclaration(
    val id: String,
    val kind: PackageMountKind,
    val access: PackageMountAccess,
    val required: Boolean,
    val label: String,
    val help: String?
) {
    init {
        require(id.matches(FIELD_ID_REGEX)) {
            "Mount ID does not match pattern ^[a-z][a-z0-9_]*$: $id"
        }
        require(
            id.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <=
                PackageResourceLimits.MAX_MOUNT_ID_BYTES
        ) {
            "Mount ID exceeds ${PackageResourceLimits.MAX_MOUNT_ID_BYTES} bytes: $id"
        }
        require(required) { "Manifest v1 mounts must be required" }
        require(label.isNotBlank()) { "Mount label must not be blank" }
        require(
            label.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <=
                PackageResourceLimits.MAX_LABEL_BYTES
        ) {
            "Mount label exceeds ${PackageResourceLimits.MAX_LABEL_BYTES} bytes"
        }
        if (help != null) {
            require(help.isNotBlank()) { "Mount help must not be blank" }
            require(
                help.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <=
                    PackageResourceLimits.MAX_HELP_BYTES
            ) {
                "Mount help exceeds ${PackageResourceLimits.MAX_HELP_BYTES} bytes"
            }
        }
    }
}

/**
 * Exact immutable `resources` declaration from manifest v1.
 */
public class PackageResourcesDeclaration(mounts: List<PackageMountDeclaration>) {
    public val mounts: List<PackageMountDeclaration> =
        Collections.unmodifiableList(ArrayList(mounts))

    init {
        require(this.mounts.size <= PackageResourceLimits.MAX_MOUNTS) {
            "Mount count exceeds ${PackageResourceLimits.MAX_MOUNTS}: ${this.mounts.size}"
        }
        val ids = HashSet<String>(this.mounts.size)
        for (mount in this.mounts) {
            require(ids.add(mount.id)) { "Duplicate mount ID: ${mount.id}" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackageResourcesDeclaration) return false
        return mounts == other.mounts
    }

    override fun hashCode(): Int = mounts.hashCode()

    override fun toString(): String = "PackageResourcesDeclaration(mounts=$mounts)"
}

/**
 * 1.1: Stable public capability identifiers.
 */
public object PackageCapability {
    public const val AUDIO_TRANSCRIPTION: String = "audio.transcription"
    public const val AUDIO_SYNTHESIS: String = "audio.synthesis"
    public const val AUDIO_PLAYBACK: String = "audio.playback"
    public const val STORAGE_FILES: String = "storage.files"
    public const val AUDIO_FILES: String = "audio.files"
    public const val KEYBOARD_OUTPUT: String = "keyboard.output"

    public val ALL: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(
            listOf(
                AUDIO_TRANSCRIPTION,
                AUDIO_SYNTHESIS,
                AUDIO_PLAYBACK,
                STORAGE_FILES,
                AUDIO_FILES,
                KEYBOARD_OUTPUT,
            )
        )
    )
}

/**
 * 2.3: Stable public dynamic-choice source identifiers accepted by manifest v1.
 * Static validation accepts only these bounded public source IDs; it never
 * resolves them, executes Lua, or touches host profile objects.
 */
public object DynamicChoiceSource {
    public const val KEYBOARD_OUTPUT_PLATFORMS: String = "keyboard-output-platforms"
    public const val KEYBOARD_OUTPUT_LAYOUTS: String = "keyboard-output-layouts"
    public const val KEYBOARD_OUTPUT_PROFILES: String = "keyboard-output-profiles"

    public val ALL: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(
            listOf(
                KEYBOARD_OUTPUT_PLATFORMS,
                KEYBOARD_OUTPUT_LAYOUTS,
                KEYBOARD_OUTPUT_PROFILES,
            )
        )
    )
}

/**
 * 1.1: Sealed class representing configuration field declarations.
 */
public sealed class ConfigurationFieldDeclaration {
    public abstract val id: String
    public abstract val type: ConfigurationFieldType
    public abstract val default: Any

    public class StringField(
        override val id: String,
        override val default: String,
        allowedValues: List<String>?
    ) : ConfigurationFieldDeclaration() {
        override val type: ConfigurationFieldType = ConfigurationFieldType.STRING
        public val allowedValues: List<String>? = allowedValues?.let { Collections.unmodifiableList(ArrayList(it)) }

        init {
            require(id.matches(FIELD_ID_REGEX)) {
                "Field ID does not match pattern ^[a-z][a-z0-9_]*$: $id"
            }
            require(id.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_FIELD_ID_BYTES) {
                "Field ID length must not exceed ${PackageConfigurationLimits.MAX_FIELD_ID_BYTES} bytes: $id"
            }
            require(default.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_STRING_VALUE_BYTES) {
                "Default value size must not exceed ${PackageConfigurationLimits.MAX_STRING_VALUE_BYTES} bytes: $default"
            }
            if (this.allowedValues != null) {
                require(this.allowedValues.isNotEmpty()) {
                    "Allowed values must not be empty if present"
                }
                val uniqueValues = mutableSetOf<String>()
                for (v in this.allowedValues) {
                    require(v.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_STRING_VALUE_BYTES) {
                        "Allowed value size must not exceed ${PackageConfigurationLimits.MAX_STRING_VALUE_BYTES} bytes: $v"
                    }
                    require(uniqueValues.add(v)) {
                        "Duplicate allowed value: $v"
                    }
                }
                require(default in uniqueValues) {
                    "Default value '$default' must be in allowed values: ${this.allowedValues}"
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StringField) return false
            return id == other.id && default == other.default && allowedValues == other.allowedValues
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + default.hashCode()
            result = 31 * result + (allowedValues?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String = "StringField(id='$id', default='$default', allowedValues=$allowedValues)"
    }

    public class BooleanField(
        override val id: String,
        override val default: Boolean
    ) : ConfigurationFieldDeclaration() {
        override val type: ConfigurationFieldType = ConfigurationFieldType.BOOLEAN

        init {
            require(id.matches(FIELD_ID_REGEX)) {
                "Field ID does not match pattern ^[a-z][a-z0-9_]*$: $id"
            }
            require(id.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_FIELD_ID_BYTES) {
                "Field ID length must not exceed ${PackageConfigurationLimits.MAX_FIELD_ID_BYTES} bytes: $id"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BooleanField) return false
            return id == other.id && default == other.default
        }

        override fun hashCode(): Int = 31 * id.hashCode() + default.hashCode()

        override fun toString(): String = "BooleanField(id='$id', default=$default)"
    }

    public class IntegerField(
        override val id: String,
        override val default: Long,
        val minimum: Long?,
        val maximum: Long?
    ) : ConfigurationFieldDeclaration() {
        override val type: ConfigurationFieldType = ConfigurationFieldType.INTEGER

        init {
            require(id.matches(FIELD_ID_REGEX)) {
                "Field ID does not match pattern ^[a-z][a-z0-9_]*$: $id"
            }
            require(id.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_FIELD_ID_BYTES) {
                "Field ID length must not exceed ${PackageConfigurationLimits.MAX_FIELD_ID_BYTES} bytes: $id"
            }
            if (minimum != null && maximum != null) {
                require(minimum <= maximum) {
                    "minimum ($minimum) must be <= maximum ($maximum)"
                }
            }
            if (minimum != null) {
                require(default >= minimum) {
                    "default ($default) must be >= minimum ($minimum)"
                }
            }
            if (maximum != null) {
                require(default <= maximum) {
                    "default ($default) must be <= maximum ($maximum)"
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IntegerField) return false
            return id == other.id && default == other.default && minimum == other.minimum && maximum == other.maximum
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + default.hashCode()
            result = 31 * result + (minimum?.hashCode() ?: 0)
            result = 31 * result + (maximum?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String = "IntegerField(id='$id', default=$default, minimum=$minimum, maximum=$maximum)"
    }
}

/**
 * 1.1: Configuration field type enum.
 */
public enum class ConfigurationFieldType {
    STRING,
    BOOLEAN,
    INTEGER
}

/**
 * 1.1: Configuration data declaration.
 */
public class ConfigurationDataDeclaration(
    fields: List<ConfigurationFieldDeclaration>
) {
    public val fields: List<ConfigurationFieldDeclaration> = Collections.unmodifiableList(ArrayList(fields))

    init {
        require(this.fields.size <= PackageConfigurationLimits.MAX_FIELDS) {
            "Configuration fields count must not exceed ${PackageConfigurationLimits.MAX_FIELDS}: ${this.fields.size}"
        }
        val ids = mutableSetOf<String>()
        for (f in this.fields) {
            require(ids.add(f.id)) {
                "Duplicate field ID: ${f.id}"
            }
        }
        val payloadSize = calculateDefaultPayloadSize(this.fields)
        require(payloadSize <= PackageConfigurationLimits.MAX_PAYLOAD_BYTES) {
            "Default configuration payload size of $payloadSize bytes exceeds the limit of ${PackageConfigurationLimits.MAX_PAYLOAD_BYTES} bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfigurationDataDeclaration) return false
        return fields == other.fields
    }

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String = "ConfigurationDataDeclaration(fields=$fields)"
}

/**
 * 1.1: UI control enum.
 */
public enum class UiControl(val value: String) {
    TEXT("text"),
    TOGGLE("toggle"),
    NUMBER("number"),
    CHOICE("choice"),
    DYNAMIC_CHOICE("dynamic-choice")
}

/**
 * 1.1: UI choice representation.
 */
public class UiChoice(
    val value: String,
    val label: String
) {
    init {
        require(value.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_STRING_VALUE_BYTES) {
            "Choice value size must not exceed ${PackageConfigurationLimits.MAX_STRING_VALUE_BYTES} bytes"
        }
        require(label.isNotBlank()) {
            "Choice label must not be blank"
        }
        require(label.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_LABEL_BYTES) {
            "Choice label size must not exceed ${PackageConfigurationLimits.MAX_LABEL_BYTES} bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UiChoice) return false
        return value == other.value && label == other.label
    }

    override fun hashCode(): Int = 31 * value.hashCode() + label.hashCode()

    override fun toString(): String = "UiChoice(value='$value', label='$label')"
}

/**
 * 1.1: UI field declaration.
 */
public class UiFieldDeclaration(
    val field: String,
    val control: UiControl,
    val label: String,
    val help: String?,
    choices: List<UiChoice>?,
    val source: String? = null,
    val dependsOnFieldId: String? = null,
) {
    public val choices: List<UiChoice>? = choices?.let { Collections.unmodifiableList(ArrayList(it)) }

    init {
        require(field.matches(FIELD_ID_REGEX)) {
            "Field ID does not match pattern ^[a-z][a-z0-9_]*$: $field"
        }
        require(field.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_FIELD_ID_BYTES) {
            "Field ID length must not exceed ${PackageConfigurationLimits.MAX_FIELD_ID_BYTES} bytes: $field"
        }
        require(label.isNotBlank()) {
            "Label must not be blank"
        }
        require(label.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_LABEL_BYTES) {
            "Label length must not exceed ${PackageConfigurationLimits.MAX_LABEL_BYTES} bytes: $label"
        }
        if (help != null) {
            require(help.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_HELP_BYTES) {
                "Help string length must not exceed ${PackageConfigurationLimits.MAX_HELP_BYTES} bytes"
            }
        }
        if (control == UiControl.CHOICE) {
            require(this.choices != null) {
                "Choices must not be null for CHOICE control"
            }
            require(this.choices.isNotEmpty() && this.choices.size <= PackageConfigurationLimits.MAX_CHOICES) {
                "Choices count must be between 1 and ${PackageConfigurationLimits.MAX_CHOICES} for CHOICE control: ${this.choices.size}"
            }
            val uniqueValues = mutableSetOf<String>()
            val uniqueLabels = mutableSetOf<String>()
            for (choice in this.choices) {
                require(uniqueValues.add(choice.value)) {
                    "Duplicate choice value: ${choice.value}"
                }
                require(uniqueLabels.add(choice.label)) {
                    "Duplicate choice label: ${choice.label}"
                }
            }
            require(this.source == null) {
                "Source must be null for CHOICE control"
            }
        } else if (control == UiControl.DYNAMIC_CHOICE) {
            require(this.choices == null) {
                "Choices must be null for DYNAMIC_CHOICE control"
            }
            require(this.source != null) {
                "Source must not be null for DYNAMIC_CHOICE control"
            }
            require(DynamicChoiceSource.ALL.contains(this.source)) {
                "Unknown dynamic-choice source: ${this.source}"
            }
        } else {
            require(this.choices == null) {
                "Choices must be null for non-CHOICE control: $control"
            }
            require(this.source == null) {
                "Source must be null for non-dynamic-choice control: $control"
            }
        }
        if (dependsOnFieldId != null) {
            require(control == UiControl.DYNAMIC_CHOICE) {
                "dependsOnFieldId is only permitted for DYNAMIC_CHOICE control: $control"
            }
            require(dependsOnFieldId.matches(FIELD_ID_REGEX)) {
                "Dependency field ID does not match pattern ^[a-z][a-z0-9_]*$: $dependsOnFieldId"
            }
            require(dependsOnFieldId.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size <= PackageConfigurationLimits.MAX_FIELD_ID_BYTES) {
                "Dependency field ID length must not exceed ${PackageConfigurationLimits.MAX_FIELD_ID_BYTES} bytes: $dependsOnFieldId"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UiFieldDeclaration) return false
        return field == other.field &&
                control == other.control &&
                label == other.label &&
                help == other.help &&
                choices == other.choices &&
                source == other.source &&
                dependsOnFieldId == other.dependsOnFieldId
    }

    override fun hashCode(): Int {
        var result = field.hashCode()
        result = 31 * result + control.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + (help?.hashCode() ?: 0)
        result = 31 * result + (choices?.hashCode() ?: 0)
        result = 31 * result + (source?.hashCode() ?: 0)
        result = 31 * result + (dependsOnFieldId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "UiFieldDeclaration(field='$field', control=$control, label='$label', help=$help, choices=$choices, source=$source, dependsOnFieldId=$dependsOnFieldId)"
}

/**
 * 1.1: Configuration UI declaration.
 */
public class ConfigurationUiDeclaration(
    fields: List<UiFieldDeclaration>
) {
    public val fields: List<UiFieldDeclaration> = Collections.unmodifiableList(ArrayList(fields))

    init {
        require(this.fields.size <= PackageConfigurationLimits.MAX_FIELDS) {
            "UI fields count must not exceed ${PackageConfigurationLimits.MAX_FIELDS}: ${this.fields.size}"
        }
        val fieldNames = mutableSetOf<String>()
        for (f in this.fields) {
            require(fieldNames.add(f.field)) {
                "Duplicate UI field reference: ${f.field}"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfigurationUiDeclaration) return false
        return fields == other.fields
    }

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String = "ConfigurationUiDeclaration(fields=$fields)"
}

/**
 * 1.1: Package configuration declaration.
 */
public class PackageConfigurationDeclaration(
    val data: ConfigurationDataDeclaration,
    val ui: ConfigurationUiDeclaration
) {
    init {
        val dataFieldIds = data.fields.map { it.id }.toSet()
        val uiFieldIds = ui.fields.map { it.field }.toSet()
        require(dataFieldIds == uiFieldIds) {
            "Configuration data field IDs ($dataFieldIds) must exactly match UI field references ($uiFieldIds)"
        }

        val dataMap = data.fields.associateBy { it.id }
        for (uiField in ui.fields) {
            val dataField = dataMap[uiField.field]!!
            when (dataField) {
                is ConfigurationFieldDeclaration.StringField -> {
                    if (dataField.allowedValues == null) {
                        require(uiField.control == UiControl.TEXT || uiField.control == UiControl.DYNAMIC_CHOICE) {
                            "String field without allowedValues must use TEXT or DYNAMIC_CHOICE control, got: ${uiField.control}"
                        }
                    } else {
                        require(uiField.control == UiControl.CHOICE) {
                            "String field with allowedValues must use CHOICE control, got: ${uiField.control}"
                        }
                        val allowedSet = dataField.allowedValues.toSet()
                        val choiceSet = uiField.choices?.map { it.value }?.toSet() ?: emptySet()
                        require(allowedSet == choiceSet) {
                            "UI choices ($choiceSet) must exactly match allowed values ($allowedSet) for field ${dataField.id}"
                        }
                    }
                }
                is ConfigurationFieldDeclaration.BooleanField -> {
                    require(uiField.control == UiControl.TOGGLE) {
                        "Boolean field must use TOGGLE control, got: ${uiField.control}"
                    }
                }
                is ConfigurationFieldDeclaration.IntegerField -> {
                    require(uiField.control == UiControl.NUMBER) {
                        "Integer field must use NUMBER control, got: ${uiField.control}"
                    }
                }
            }
        }
        for ((index, uiField) in ui.fields.withIndex()) {
            val dependencyId = uiField.dependsOnFieldId ?: continue
            val dependencyIndex = ui.fields.indexOfFirst { it.field == dependencyId }
            require(dependencyIndex >= 0) {
                "dependsOn references an unknown field: $dependencyId"
            }
            require(dependencyIndex < index) {
                "dependsOn must reference an earlier UI field, not self or later: $dependencyId"
            }
            val dependencyData = dataMap[dependencyId]
            require(dependencyData is ConfigurationFieldDeclaration.StringField && dependencyData.allowedValues == null) {
                "dependsOn must reference an unconstrained string data field: $dependencyId"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackageConfigurationDeclaration) return false
        return data == other.data && ui == other.ui
    }

    override fun hashCode(): Int = 31 * data.hashCode() + ui.hashCode()

    override fun toString(): String = "PackageConfigurationDeclaration(data=$data, ui=$ui)"
}

