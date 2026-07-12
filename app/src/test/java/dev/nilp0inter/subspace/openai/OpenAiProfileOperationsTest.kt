package dev.nilp0inter.subspace.openai

import com.openai.client.OpenAIClient
import com.openai.models.models.Model
import com.openai.models.models.ModelListPage
import com.openai.services.blocking.ModelService
import dev.nilp0inter.subspace.model.OpenAiCredentialReference
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkClientRegistry
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkModelDiscoveryService
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProfileOperationsTest {

    @Test
    fun committedTransportEditRetiresOnlyThatProfilesModelCacheWhileRenameDoesNotDiscardIt() = runTest {
        withTemporaryDirectory { directory ->
            val credentials = Credentials()
            val repository = OpenAiProfileRepository(OpenAiProfileMetadataStore(File(directory, "profiles.json")), credentials) { "profile-id" }
            val clients = clients(repository, credentials, "vendor/custom-model")
            val models = OpenAiSdkModelDiscoveryService(clients)
            val operations = OpenAiProfileOperations(repository, clients, models)
            val profile = requireSuccess(operations.create("Local", "https://gateway.invalid/v1", "token"))

            operations.refreshModels(profile.id)
            assertFalse(models.cachedChoices(profile.id).isEmpty())
            assertEquals(OpenAiProfileMutationResult.Success, operations.rename(profile.id, "Renamed"))
            assertFalse(models.cachedChoices(profile.id).isEmpty())

            assertEquals(OpenAiProfileMutationResult.Success, operations.edit(profile.id, baseUrl = "https://replacement.invalid/v1"))
            assertTrue(models.cachedChoices(profile.id).isEmpty())
            assertFalse(models.states.value.containsKey(profile.id))
            operations.close()
        }
    }

    private fun clients(
        repository: OpenAiProfileRepository,
        credentials: Credentials,
        vararg modelIds: String,
    ): OpenAiSdkClientRegistry {
        val page = mockk<ModelListPage>()
        every { page.items() } returns modelIds.map(::model)
        val modelService = mockk<ModelService>()
        every { modelService.list() } returns page
        val client = mockk<OpenAIClient>(relaxed = true)
        every { client.models() } returns modelService
        return OpenAiSdkClientRegistry(
            profiles = repository,
            credentials = credentials,
            clientFactory = { _, _, _ -> client },
        )
    }

    private fun model(id: String): Model = Model.builder().id(id).created(0).ownedBy("test").build()

    private fun <T> requireSuccess(result: OpenAiProfileOperationResult<T>): T = when (result) {
        is OpenAiProfileOperationResult.Success -> result.value
        is OpenAiProfileOperationResult.Failure -> throw AssertionError("Expected success")
    }
    private class Credentials : OpenAiBearerCredentialStore {
        private val values = mutableMapOf<OpenAiCredentialReference, String>()
        override fun replace(reference: OpenAiCredentialReference, bearerToken: CharSequence): OpenAiCredentialStoreResult<Unit> { values[reference] = bearerToken.toString(); return OpenAiCredentialStoreResult.Success(Unit) }
        override fun delete(reference: OpenAiCredentialReference): OpenAiCredentialStoreResult<Unit> { values.remove(reference); return OpenAiCredentialStoreResult.Success(Unit) }
        override fun contains(reference: OpenAiCredentialReference) = reference in values
        override fun <T> use(reference: OpenAiCredentialReference, block: (CharSequence) -> T): OpenAiCredentialStoreResult<T> = values[reference]?.let { OpenAiCredentialStoreResult.Success(block(it)) } ?: OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.Missing)
    }
    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T { val directory = createTempDirectory("openai-profile-operations-test-").toFile(); return try { block(directory) } finally { directory.deleteRecursively() } }
}
