package dev.nilp0inter.subspace.openai

import dev.nilp0inter.subspace.model.OpenAiConnectionProfile
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiCredentialReference
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface OpenAiProfileRepositoryError {
    val message: String

    data class Metadata(val error: OpenAiProfileMetadataError) : OpenAiProfileRepositoryError {
        override val message = error.message
    }

    data class Credential(val error: OpenAiCredentialStoreError) : OpenAiProfileRepositoryError {
        override val message = error.message
    }

    data class UnknownProfile(val id: OpenAiConnectionProfileId) : OpenAiProfileRepositoryError {
        override val message = "OpenAI connection profile is unavailable"
    }

    data object InvalidDisplayName : OpenAiProfileRepositoryError {
        override val message = "OpenAI connection profile name must not be blank"
    }

    data object InvalidBaseUrl : OpenAiProfileRepositoryError {
        override val message = "OpenAI connection profile base URL is invalid"
    }
}

sealed interface OpenAiProfileOperationResult<out T> {
    data class Success<T>(val value: T) : OpenAiProfileOperationResult<T>
    data class Failure(val error: OpenAiProfileRepositoryError) : OpenAiProfileOperationResult<Nothing>
}

/** A completed metadata mutation whose retired non-secret credential reference needs a retry. */
data class OpenAiCredentialCleanupPending(
    val credentialReference: OpenAiCredentialReference,
)

sealed interface OpenAiProfileMutationResult {
    data object Success : OpenAiProfileMutationResult
    data class CleanupPending(val pending: OpenAiCredentialCleanupPending) : OpenAiProfileMutationResult
    data class Failure(val error: OpenAiProfileRepositoryError) : OpenAiProfileMutationResult
}

class OpenAiProfileRepositoryLoadException(
    val error: OpenAiProfileRepositoryError,
) : IllegalStateException(error.message)

/**
 * Host-only global profile repository. It never reads or mutates channel catalogue data, so
 * deleting a profile cannot delete, rewrite, or rebind channel definitions that reference it.
 */
class OpenAiProfileRepository(
    private val metadataStore: OpenAiProfileMetadataStore,
    private val credentialStore: OpenAiBearerCredentialStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutationLock = Any()
    private val _profiles: MutableStateFlow<List<OpenAiConnectionProfile>>
    val profiles: StateFlow<List<OpenAiConnectionProfile>>
        get() = _profiles.asStateFlow()

    init {
        val initial = when (val loaded = metadataStore.load()) {
            null -> OpenAiProfileMetadataSnapshot()
            is OpenAiProfileMetadataLoadResult.Failure -> throw OpenAiProfileRepositoryLoadException(
                OpenAiProfileRepositoryError.Metadata(loaded.error),
            )
            is OpenAiProfileMetadataLoadResult.Success -> loaded.snapshot.also { snapshot ->
                if (loaded.sourceVersion != OpenAiProfileMetadataCodec.CURRENT_VERSION) saveOrThrow(snapshot)
            }
        }
        _profiles = MutableStateFlow(initial.profiles)
    }

    fun create(
        displayName: String,
        baseUrl: String,
        bearerToken: CharSequence,
    ): OpenAiProfileOperationResult<OpenAiConnectionProfile> = synchronized(mutationLock) {
        val id = try {
            OpenAiConnectionProfileId(newId())
        } catch (_: IllegalArgumentException) {
            return@synchronized OpenAiProfileOperationResult.Failure(OpenAiProfileRepositoryError.InvalidDisplayName)
        }
        val profile = buildProfile(id, displayName, baseUrl, OpenAiCredentialReference("openai-bearer-${newId()}"))
            ?: return@synchronized OpenAiProfileOperationResult.Failure(validationError(displayName, baseUrl))
        when (val credential = credentialStore.replace(profile.credentialReference, bearerToken)) {
            is OpenAiCredentialStoreResult.Failure -> OpenAiProfileOperationResult.Failure(
                OpenAiProfileRepositoryError.Credential(credential.error),
            )
            is OpenAiCredentialStoreResult.Success -> when (val persisted = persist(_profiles.value + profile)) {
                is OpenAiProfileMutationResult.Failure -> {
                    credentialStore.delete(profile.credentialReference)
                    OpenAiProfileOperationResult.Failure(persisted.error)
                }
                else -> OpenAiProfileOperationResult.Success(profile)
            }
        }
    }

    fun rename(id: OpenAiConnectionProfileId, displayName: String): OpenAiProfileMutationResult =
        edit(id, displayName = displayName, baseUrl = null, replacementBearerToken = null)

    fun edit(
        id: OpenAiConnectionProfileId,
        displayName: String? = null,
        baseUrl: String? = null,
        replacementBearerToken: CharSequence? = null,
    ): OpenAiProfileMutationResult = synchronized(mutationLock) {
        val existing = _profiles.value.find { it.id == id }
            ?: return@synchronized OpenAiProfileMutationResult.Failure(OpenAiProfileRepositoryError.UnknownProfile(id))
        val updatedName = displayName?.trim() ?: existing.displayName
        val updatedUrl = baseUrl?.let { normalizeOrNull(it) } ?: existing.baseUrl
        if (updatedName.isBlank()) return@synchronized OpenAiProfileMutationResult.Failure(OpenAiProfileRepositoryError.InvalidDisplayName)
        if (updatedUrl == null) return@synchronized OpenAiProfileMutationResult.Failure(OpenAiProfileRepositoryError.InvalidBaseUrl)

        if (replacementBearerToken == null) {
            val replacement = existing.copy(displayName = updatedName, baseUrl = updatedUrl)
            return@synchronized persist(_profiles.value.map { if (it.id == id) replacement else it })
        }

        // A fresh reference makes metadata and credential replacement recoverable without reading
        // the prior bearer value. The old reference is retired only after metadata commits.
        val replacementReference = OpenAiCredentialReference("openai-bearer-${newId()}")
        when (val credential = credentialStore.replace(replacementReference, replacementBearerToken)) {
            is OpenAiCredentialStoreResult.Failure -> OpenAiProfileMutationResult.Failure(
                OpenAiProfileRepositoryError.Credential(credential.error),
            )
            is OpenAiCredentialStoreResult.Success -> {
                val replacement = existing.copy(
                    displayName = updatedName,
                    baseUrl = updatedUrl,
                    credentialReference = replacementReference,
                )
                when (val persisted = persist(_profiles.value.map { if (it.id == id) replacement else it })) {
                    is OpenAiProfileMutationResult.Failure -> {
                        credentialStore.delete(replacementReference)
                        persisted
                    }
                    else -> retire(existing.credentialReference)
                }
            }
        }
    }

    /** Removes profile metadata only; external channel definitions remain wholly untouched. */
    fun delete(id: OpenAiConnectionProfileId): OpenAiProfileMutationResult = synchronized(mutationLock) {
        val existing = _profiles.value.find { it.id == id }
            ?: return@synchronized OpenAiProfileMutationResult.Failure(OpenAiProfileRepositoryError.UnknownProfile(id))
        when (val persisted = persist(_profiles.value.filterNot { it.id == id })) {
            is OpenAiProfileMutationResult.Failure -> persisted
            else -> retire(existing.credentialReference)
        }
    }

    /** Allows host test/refresh adapters to resolve metadata without projecting bearer material. */
    fun profileForOperation(id: OpenAiConnectionProfileId): OpenAiProfileOperationResult<OpenAiConnectionProfile> =
        _profiles.value.find { it.id == id }?.let { OpenAiProfileOperationResult.Success<OpenAiConnectionProfile>(it) }
            ?: OpenAiProfileOperationResult.Failure(OpenAiProfileRepositoryError.UnknownProfile(id))

    fun retryCredentialCleanup(pending: OpenAiCredentialCleanupPending): OpenAiProfileMutationResult =
        retire(pending.credentialReference)

    private fun retire(reference: OpenAiCredentialReference): OpenAiProfileMutationResult = when (val deleted = credentialStore.delete(reference)) {
        is OpenAiCredentialStoreResult.Success -> OpenAiProfileMutationResult.Success
        is OpenAiCredentialStoreResult.Failure -> OpenAiProfileMutationResult.CleanupPending(
            OpenAiCredentialCleanupPending(reference),
        )
    }

    private fun persist(replacement: List<OpenAiConnectionProfile>): OpenAiProfileMutationResult {
        val snapshot = try {
            OpenAiProfileMetadataSnapshot(replacement)
        } catch (_: IllegalArgumentException) {
            return OpenAiProfileMutationResult.Failure(
                OpenAiProfileRepositoryError.Metadata(OpenAiProfileMetadataError.InvalidProfile("Invalid OpenAI connection profile metadata")),
            )
        }
        return when (val result = metadataStore.save(snapshot)) {
            is OpenAiProfileMetadataStoreResult.Success -> {
                _profiles.value = snapshot.profiles
                OpenAiProfileMutationResult.Success
            }
            is OpenAiProfileMetadataStoreResult.Failure -> OpenAiProfileMutationResult.Failure(
                OpenAiProfileRepositoryError.Metadata(result.error),
            )
        }
    }

    private fun saveOrThrow(snapshot: OpenAiProfileMetadataSnapshot) {
        if (metadataStore.save(snapshot) is OpenAiProfileMetadataStoreResult.Failure) {
            throw OpenAiProfileRepositoryLoadException(
                OpenAiProfileRepositoryError.Metadata(OpenAiProfileMetadataError.Storage("migrate")),
            )
        }
    }

    private fun buildProfile(
        id: OpenAiConnectionProfileId,
        displayName: String,
        baseUrl: String,
        credentialReference: OpenAiCredentialReference,
    ): OpenAiConnectionProfile? = try {
        OpenAiConnectionProfile(
            id = id,
            displayName = displayName.trim(),
            baseUrl = OpenAiProfileMetadataCodec.normalizeBaseUrl(baseUrl),
            credentialReference = credentialReference,
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun normalizeOrNull(raw: String): String? = try {
        OpenAiProfileMetadataCodec.normalizeBaseUrl(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun validationError(displayName: String, baseUrl: String): OpenAiProfileRepositoryError = when {
        displayName.isBlank() -> OpenAiProfileRepositoryError.InvalidDisplayName
        normalizeOrNull(baseUrl) == null -> OpenAiProfileRepositoryError.InvalidBaseUrl
        else -> OpenAiProfileRepositoryError.InvalidBaseUrl
    }
}
