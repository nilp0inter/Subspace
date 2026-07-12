package dev.nilp0inter.subspace.openai.adapter

import com.openai.client.OpenAIClient
import com.openai.errors.UnauthorizedException
import com.openai.models.models.Model
import com.openai.models.models.ModelListPage
import com.openai.services.blocking.ModelService
import com.openai.core.http.Headers
import dev.nilp0inter.subspace.model.OpenAiAvailabilityReason
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiCredentialReference
import dev.nilp0inter.subspace.model.OpenAiModelChoice
import dev.nilp0inter.subspace.model.OpenAiModelId
import dev.nilp0inter.subspace.model.OpenAiModelDiscoveryOutcome
import dev.nilp0inter.subspace.openai.OpenAiBearerCredentialStore
import dev.nilp0inter.subspace.openai.OpenAiCredentialStoreError
import dev.nilp0inter.subspace.openai.OpenAiCredentialStoreResult
import dev.nilp0inter.subspace.openai.OpenAiProfileMetadataStore
import dev.nilp0inter.subspace.openai.OpenAiProfileOperationResult
import dev.nilp0inter.subspace.openai.OpenAiProfileRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.ArrayDeque
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSdkModelDiscoveryServiceTest {

    @Test
    fun refreshPreservesArbitraryProfileScopedModelIdsWhileNormalizingDuplicatesAndBounds() = runTest {
        withTemporaryDirectory { directory ->
            val credentials = Credentials()
            val repository = repository(directory, credentials)
            val first = requireSuccess(repository.create("First", "https://gateway.invalid/v1", "first-token"))
            val second = requireSuccess(repository.create("Second", "https://gateway.invalid/v1", "second-token"))
            val service = OpenAiSdkModelDiscoveryService(
                clients(repository, credentials, available(" gateway/alpha ", "gateway/alpha", "", "vendor/beta@2026")),
                maxModels = 2,
            )

            val outcome = service.refresh(first.id)

            assertEquals(
                OpenAiModelDiscoveryOutcome.Available(
                    first.id,
                    listOf(
                        OpenAiModelChoice(OpenAiModelId("gateway/alpha"), "gateway/alpha"),
                        OpenAiModelChoice(OpenAiModelId("vendor/beta@2026"), "vendor/beta@2026"),
                    ),
                ),
                outcome,
            )
            assertEquals((outcome as OpenAiModelDiscoveryOutcome.Available).models, service.cachedChoices(first.id))
            assertTrue(service.cachedChoices(second.id).isEmpty())
            service.invalidate(first.id)
            assertTrue(service.cachedChoices(first.id).isEmpty())
        }
    }

    @Test
    fun failedRefreshPublishesUnavailableButDoesNotDiscardPreviouslyUsableDisplayCache() = runTest {
        withTemporaryDirectory { directory ->
            val credentials = Credentials()
            val repository = repository(directory, credentials)
            val profile = requireSuccess(repository.create("Local", "https://gateway.invalid/v1", "token"))
            val service = OpenAiSdkModelDiscoveryService(
                clients(repository, credentials, available("gateway/alpha"), failure(IOException("unavailable"))),
            )
            val available = service.refresh(profile.id) as OpenAiModelDiscoveryOutcome.Available

            val failed = service.refresh(profile.id)

            assertEquals(OpenAiModelDiscoveryOutcome.Unavailable(profile.id, OpenAiAvailabilityReason.UNREACHABLE), failed)
            assertEquals(available.models, service.cachedChoices(profile.id))
            assertEquals(failed, service.states.value[profile.id])
        }
    }

    @Test
    fun missingCredentialsAuthenticationAndEmptyModelListsHaveSpecificSafeAvailabilityResults() = runTest {
        withTemporaryDirectory { directory ->
            val credentials = Credentials()
            val repository = repository(directory, credentials)
            val profile = requireSuccess(repository.create("Local", "https://gateway.invalid/v1", "token"))
            val service = OpenAiSdkModelDiscoveryService(
                clients(repository, credentials, failure(unauthorized()), available()),
            )
            credentials.values.clear()
            assertEquals(
                OpenAiModelDiscoveryOutcome.Unavailable(profile.id, OpenAiAvailabilityReason.CREDENTIAL_MISSING),
                service.refresh(profile.id),
            )
            credentials.values[profile.credentialReference] = "token"
            assertEquals(
                OpenAiModelDiscoveryOutcome.Unavailable(profile.id, OpenAiAvailabilityReason.AUTHENTICATION_FAILED),
                service.refresh(profile.id),
            )
            assertEquals(
                OpenAiModelDiscoveryOutcome.Unavailable(profile.id, OpenAiAvailabilityReason.INVALID_RESPONSE),
                service.refresh(profile.id),
            )
        }
    }

    private sealed interface ModelListResult {
        data class Available(val models: List<Model>) : ModelListResult
        data class Failure(val error: Throwable) : ModelListResult
    }

    private fun available(vararg ids: String): ModelListResult.Available = ModelListResult.Available(ids.map(::model))

    private fun failure(error: Throwable): ModelListResult.Failure = ModelListResult.Failure(error)

    private fun unauthorized(): UnauthorizedException = UnauthorizedException.builder()
        .headers(Headers.builder().build())
        .build()

    private fun clients(
        repository: OpenAiProfileRepository,
        credentials: Credentials,
        vararg responses: ModelListResult,
    ): OpenAiSdkClientRegistry {
        val scripted = ArrayDeque(responses.toList())
        val modelService = mockk<ModelService>()
        every { modelService.list() } answers {
            when (val response = scripted.removeFirst()) {
                is ModelListResult.Available -> mockk<ModelListPage>().also { page ->
                    every { page.items() } returns response.models
                }
                is ModelListResult.Failure -> throw response.error
            }
        }
        val client = mockk<OpenAIClient>(relaxed = true)
        every { client.models() } returns modelService
        return OpenAiSdkClientRegistry(
            profiles = repository,
            credentials = credentials,
            clientFactory = { _, _, _ -> client },
        )
    }

    private fun model(id: String): Model = Model.builder().id(id).created(0).ownedBy("test").build()
    private fun repository(directory: File, credentials: Credentials): OpenAiProfileRepository {
        var nextId = 0
        return OpenAiProfileRepository(OpenAiProfileMetadataStore(File(directory, "profiles.json")), credentials) { "profile-${++nextId}" }
    }
    private fun <T> requireSuccess(result: OpenAiProfileOperationResult<T>): T = when (result) { is OpenAiProfileOperationResult.Success -> result.value; is OpenAiProfileOperationResult.Failure -> throw AssertionError("Expected success") }

    private class Credentials : OpenAiBearerCredentialStore {
        val values = mutableMapOf<OpenAiCredentialReference, String>()
        override fun replace(reference: OpenAiCredentialReference, bearerToken: CharSequence): OpenAiCredentialStoreResult<Unit> { values[reference] = bearerToken.toString(); return OpenAiCredentialStoreResult.Success(Unit) }
        override fun delete(reference: OpenAiCredentialReference): OpenAiCredentialStoreResult<Unit> { values.remove(reference); return OpenAiCredentialStoreResult.Success(Unit) }
        override fun contains(reference: OpenAiCredentialReference) = reference in values
        override fun <T> use(reference: OpenAiCredentialReference, block: (CharSequence) -> T): OpenAiCredentialStoreResult<T> = values[reference]?.let { OpenAiCredentialStoreResult.Success(block(it)) } ?: OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.Missing)
    }
    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T { val directory = createTempDirectory("openai-model-discovery-test-").toFile(); return try { block(directory) } finally { directory.deleteRecursively() } }
}
