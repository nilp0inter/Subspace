package dev.nilp0inter.subspace.dependency

import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ProviderRevisionFingerprint
import dev.nilp0inter.subspace.lua.ImmutableProgramImage
import java.util.Collections
import java.util.LinkedHashMap

// 1. Precompile regex and allocation-free checks to satisfy performance directives
private val CANONICAL_MODULE_REGEX = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*$")

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
    val runtime: RuntimeRequirements
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
        SHUTDOWN
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
