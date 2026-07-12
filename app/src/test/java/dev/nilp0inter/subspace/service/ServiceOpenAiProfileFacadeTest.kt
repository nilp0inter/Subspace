package dev.nilp0inter.subspace.service

import com.openai.client.OpenAIClient
import com.openai.models.models.Model
import com.openai.models.models.ModelListPage
import com.openai.services.blocking.ModelService
import dev.nilp0inter.subspace.model.DynamicConfigurationChoice
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceRequest
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolution
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceSource
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.openai.OpenAiBearerCredentialStore
import dev.nilp0inter.subspace.openai.OpenAiCredentialStoreError
import dev.nilp0inter.subspace.openai.OpenAiCredentialStoreResult
import dev.nilp0inter.subspace.openai.OpenAiProfileMetadataStore
import dev.nilp0inter.subspace.openai.OpenAiProfileOperations
import dev.nilp0inter.subspace.openai.OpenAiProfileRepository
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkClientRegistry
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkModelDiscoveryService
import dev.nilp0inter.subspace.ui.OpenAiProfileEditRequest
import dev.nilp0inter.subspace.ui.OpenAiProfileModelUiState
import dev.nilp0inter.subspace.ui.OpenAiProfileUiError
import dev.nilp0inter.subspace.ui.OpenAiProfileUiMutationResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceOpenAiProfileFacadeTest {

    @Test
    fun `profile facade retains stable references redacts credentials and exposes model refresh state to configuration`() = runTest {
        withTemporaryDirectory { directory ->
            val credentials = Credentials()
            val repository = OpenAiProfileRepository(
                OpenAiProfileMetadataStore(File(directory, "profiles.json")),
                credentials,
            ) { "profile-stable-id" }
            val clients = clients(repository, credentials, available("vendor/agent-v1"), failure(IOException("unavailable")))
            val models = OpenAiSdkModelDiscoveryService(clients)
            val operations = OpenAiProfileOperations(repository, clients, models)
            val facade = ServiceOpenAiProfileFacade(
                scope = backgroundScope,
                repository = repository,
                credentials = credentials,
                operations = operations,
                models = models,
                textOutputProfiles = {
                    listOf(DynamicConfigurationChoice("keyboard-office", "Office keyboard"))
                },
            )
            val bearer = "secret-bearer-value"

            assertEquals(
                OpenAiProfileUiMutationResult.Success,
                facade.create(
                    OpenAiProfileEditRequest(
                        id = null,
                        displayName = "Local gateway",
                        baseUrl = "https://gateway.invalid/v1",
                        replacementBearerToken = bearer,
                    ),
                ),
            )
            runCurrent()
            val created = facade.profileUiState.value.single()
            assertEquals("profile-stable-id", created.id)
            assertEquals("Local gateway", created.displayName)
            assertTrue(created.credentialConfigured)
            assertFalse(created.toString().contains(bearer))

            assertEquals(
                OpenAiProfileUiMutationResult.Success,
                facade.update(
                    OpenAiProfileEditRequest(
                        id = created.id,
                        displayName = "Renamed gateway",
                        baseUrl = "https://gateway.invalid/v1",
                        replacementBearerToken = null,
                    ),
                ),
            )
            runCurrent()
            assertEquals("profile-stable-id", facade.profileUiState.value.single().id)
            assertEquals("Renamed gateway", facade.profileUiState.value.single().displayName)

            assertEquals(
                DynamicConfigurationChoiceResolution.Available(
                    listOf(DynamicConfigurationChoice("vendor/agent-v1", "vendor/agent-v1")),
                ),
                facade.dynamicChoiceResolver.resolve(
                    DynamicConfigurationChoiceRequest(
                        DynamicConfigurationChoiceSource.OPENAI_MODELS,
                        dependencyValue = created.id,
                    ),
                ),
            )
            runCurrent()
            assertEquals(
                OpenAiProfileModelUiState.Available(1),
                facade.profileUiState.value.single().modelState,
            )

            facade.refresh(created.id)
            runCurrent()
            assertEquals(
                OpenAiProfileModelUiState.Unavailable(OpenAiProfileUiError.ConnectionFailed),
                facade.profileUiState.value.single().modelState,
            )

            assertEquals(OpenAiProfileUiMutationResult.Success, facade.delete(created.id))
            runCurrent()
            assertTrue(facade.profileUiState.value.isEmpty())
            assertEquals(
                DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                ),
                facade.dynamicChoiceResolver.resolve(
                    DynamicConfigurationChoiceRequest(
                        DynamicConfigurationChoiceSource.OPENAI_MODELS,
                        dependencyValue = created.id,
                    ),
                ),
            )
            operations.close()
        }
    }

    private sealed interface ModelListResult {
        data class Available(val models: List<Model>) : ModelListResult
        data class Failure(val error: Throwable) : ModelListResult
    }

    private fun available(vararg ids: String): ModelListResult.Available = ModelListResult.Available(ids.map(::model))

    private fun failure(error: Throwable): ModelListResult.Failure = ModelListResult.Failure(error)

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

    private class Credentials : OpenAiBearerCredentialStore {
        private val values = mutableMapOf<dev.nilp0inter.subspace.model.OpenAiCredentialReference, String>()

        override fun replace(
            reference: dev.nilp0inter.subspace.model.OpenAiCredentialReference,
            bearerToken: CharSequence,
        ): OpenAiCredentialStoreResult<Unit> {
            values[reference] = bearerToken.toString()
            return OpenAiCredentialStoreResult.Success(Unit)
        }

        override fun delete(
            reference: dev.nilp0inter.subspace.model.OpenAiCredentialReference,
        ): OpenAiCredentialStoreResult<Unit> {
            values.remove(reference)
            return OpenAiCredentialStoreResult.Success(Unit)
        }

        override fun contains(reference: dev.nilp0inter.subspace.model.OpenAiCredentialReference): Boolean = reference in values

        override fun <T> use(
            reference: dev.nilp0inter.subspace.model.OpenAiCredentialReference,
            block: (CharSequence) -> T,
        ): OpenAiCredentialStoreResult<T> = values[reference]?.let {
            OpenAiCredentialStoreResult.Success(block(it))
        } ?: OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.Missing)
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("service-openai-profile-facade-test-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
