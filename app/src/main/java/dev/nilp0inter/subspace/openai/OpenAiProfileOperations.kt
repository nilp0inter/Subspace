package dev.nilp0inter.subspace.openai

import dev.nilp0inter.subspace.model.OpenAiConnectionProfile
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiModelDiscoveryOutcome
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkClientRegistry
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkModelDiscoveryService

/**
 * Host lifecycle boundary for profile management. It keeps profile persistence independent from
 * channel definitions while retiring transport and discovery generations after committed changes.
 */
internal class OpenAiProfileOperations(
    private val repository: OpenAiProfileRepository,
    private val clients: OpenAiSdkClientRegistry,
    private val models: OpenAiSdkModelDiscoveryService,
) {
    fun create(
        displayName: String,
        baseUrl: String,
        bearerToken: CharSequence,
    ): OpenAiProfileOperationResult<OpenAiConnectionProfile> =
        repository.create(displayName, baseUrl, bearerToken)

    fun rename(
        id: OpenAiConnectionProfileId,
        displayName: String,
    ): OpenAiProfileMutationResult = repository.rename(id, displayName)

    fun edit(
        id: OpenAiConnectionProfileId,
        displayName: String? = null,
        baseUrl: String? = null,
        replacementBearerToken: CharSequence? = null,
    ): OpenAiProfileMutationResult {
        val previous = repository.profileForOperation(id).profileOrNull()
        return repository.edit(id, displayName, baseUrl, replacementBearerToken).also { result ->
            val current = repository.profileForOperation(id).profileOrNull()
            val changedEndpointOrCredential = previous?.let {
                it.baseUrl != current?.baseUrl || it.credentialReference != current?.credentialReference
            } == true
            if (result.committed() && changedEndpointOrCredential) {
                retire(id)
            }
        }
    }

    fun delete(id: OpenAiConnectionProfileId): OpenAiProfileMutationResult = repository.delete(id).also { result ->
        if (result.committed()) retire(id)
    }

    /** Tests connectivity through the same profile/client/credential path as model refresh. */
    suspend fun test(id: OpenAiConnectionProfileId): OpenAiModelDiscoveryOutcome = models.refresh(id)

    suspend fun refreshModels(id: OpenAiConnectionProfileId): OpenAiModelDiscoveryOutcome = models.refresh(id)

    fun close() = clients.close()

    private fun retire(id: OpenAiConnectionProfileId) {
        models.invalidate(id)
        clients.invalidate(id)
    }

    private fun OpenAiProfileMutationResult.committed(): Boolean = when (this) {
        OpenAiProfileMutationResult.Success,
        is OpenAiProfileMutationResult.CleanupPending -> true
        is OpenAiProfileMutationResult.Failure -> false
    }

    private fun OpenAiProfileOperationResult<OpenAiConnectionProfile>.profileOrNull(): OpenAiConnectionProfile? = when (this) {
        is OpenAiProfileOperationResult.Success -> value
        is OpenAiProfileOperationResult.Failure -> null
    }
}
