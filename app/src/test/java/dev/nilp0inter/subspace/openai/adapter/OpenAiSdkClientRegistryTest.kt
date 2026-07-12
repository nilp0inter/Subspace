package dev.nilp0inter.subspace.openai.adapter

import com.openai.client.OpenAIClient
import dev.nilp0inter.subspace.model.OpenAiAvailabilityReason
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiCredentialReference
import dev.nilp0inter.subspace.openai.OpenAiBearerCredentialStore
import dev.nilp0inter.subspace.openai.OpenAiCredentialStoreError
import dev.nilp0inter.subspace.openai.OpenAiCredentialStoreResult
import dev.nilp0inter.subspace.openai.OpenAiProfileMetadataStore
import dev.nilp0inter.subspace.openai.OpenAiProfileOperationResult
import dev.nilp0inter.subspace.openai.OpenAiProfileRepository
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSdkClientRegistryTest {
    @Test
    fun currentProfileRevisionReusesOneClientAndReplacingEndpointRetiresThePriorClient() = withTemporaryDirectory { directory ->
        val credentials = Credentials()
        val repository = repository(directory, credentials)
        val profile = requireSuccess(repository.create("Local", "https://one.invalid/v1", "credential"))
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val registry = OpenAiSdkClientRegistry(
            profiles = repository,
            credentials = credentials,
            clientFactory = { _, _, _ -> client(closed) { created.incrementAndGet() } },
        )

        assertEquals(OpenAiSdkClientRegistry.Execution.Current("first"), registry.execute(profile.id) { "first" })
        assertEquals(OpenAiSdkClientRegistry.Execution.Current("second"), registry.execute(profile.id) { "second" })
        assertEquals(1, created.get())

        assertEquals(
            dev.nilp0inter.subspace.openai.OpenAiProfileMutationResult.Success,
            repository.edit(profile.id, baseUrl = "https://two.invalid/v1"),
        )
        assertEquals(OpenAiSdkClientRegistry.Execution.Current("replacement"), registry.execute(profile.id) { "replacement" })
        assertEquals(2, created.get())
        assertEquals(1, closed.get())
        registry.close()
        assertEquals(2, closed.get())
    }

    @Test
    fun missingProfileOrCredentialNeverConstructsAnImplicitDefaultClient() = withTemporaryDirectory { directory ->
        val credentials = Credentials()
        val repository = repository(directory, credentials)
        val constructed = AtomicInteger()
        val registry = OpenAiSdkClientRegistry(repository, credentials, { _, _, _ ->
            constructed.incrementAndGet()
            client(AtomicInteger()) {}
        })
        val missing = OpenAiConnectionProfileId("missing")

        assertEquals(
            OpenAiSdkClientRegistry.Execution.Unavailable(OpenAiAvailabilityReason.PROFILE_MISSING),
            registry.execute(missing) { "must not execute" },
        )
        val profile = requireSuccess(repository.create("Local", "https://local.invalid/v1", "credential"))
        credentials.values.clear()
        assertEquals(
            OpenAiSdkClientRegistry.Execution.Unavailable(OpenAiAvailabilityReason.CREDENTIAL_MISSING),
            registry.execute(profile.id) { "must not execute" },
        )
        assertEquals(0, constructed.get())
        registry.close()
    }

    @Test
    fun invalidationRevokesAnInFlightRevisionBeforeItsResultCanPublish() = withTemporaryDirectory { directory ->
        val credentials = Credentials()
        val repository = repository(directory, credentials)
        val profile = requireSuccess(repository.create("Local", "https://local.invalid/v1", "credential"))
        val registry = OpenAiSdkClientRegistry(repository, credentials, { _, _, _ -> client(AtomicInteger()) {} })

        val result = registry.execute(profile.id) {
            registry.invalidate(profile.id)
            "late value"
        }

        assertEquals(OpenAiSdkClientRegistry.Execution.Stale, result)
        registry.close()
    }

    private fun client(closed: AtomicInteger, created: () -> Unit): OpenAIClient {
        created()
        return Proxy.newProxyInstance(OpenAIClient::class.java.classLoader, arrayOf(OpenAIClient::class.java)) { _, method, _ ->
            when (method.name) {
                "close" -> { closed.incrementAndGet(); null }
                "toString" -> "test-client"
                "hashCode" -> 0
                "equals" -> false
                else -> throw AssertionError("Unexpected SDK method: ${method.name}")
            }
        } as OpenAIClient
    }

    private fun repository(directory: File, credentials: Credentials) =
        OpenAiProfileRepository(OpenAiProfileMetadataStore(File(directory, "profiles.json")), credentials) { "profile-id" }

    private fun <T> requireSuccess(result: OpenAiProfileOperationResult<T>): T = when (result) {
        is OpenAiProfileOperationResult.Success -> result.value
        is OpenAiProfileOperationResult.Failure -> throw AssertionError("Expected success, got ${result.error}")
    }

    private class Credentials : OpenAiBearerCredentialStore {
        val values = mutableMapOf<OpenAiCredentialReference, String>()
        override fun replace(reference: OpenAiCredentialReference, bearerToken: CharSequence): OpenAiCredentialStoreResult<Unit> {
            values[reference] = bearerToken.toString()
            return OpenAiCredentialStoreResult.Success(Unit)
        }
        override fun delete(reference: OpenAiCredentialReference): OpenAiCredentialStoreResult<Unit> {
            values.remove(reference)
            return OpenAiCredentialStoreResult.Success(Unit)
        }
        override fun contains(reference: OpenAiCredentialReference) = reference in values
        override fun <T> use(reference: OpenAiCredentialReference, block: (CharSequence) -> T): OpenAiCredentialStoreResult<T> =
            values[reference]?.let { OpenAiCredentialStoreResult.Success(block(it)) }
                ?: OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.Missing)
    }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val directory = createTempDirectory("openai-sdk-registry-test-").toFile()
        return try { block(directory) } finally { directory.deleteRecursively() }
    }
}
