package dev.nilp0inter.subspace.openai

import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiCredentialReference
import java.io.File
import java.util.ArrayDeque
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProfileRepositoryTest {
    @Test
    fun v1MetadataMigratesWithoutChangingStableProfileOrCredentialReferences() = withTemporaryDirectory { directory ->
        val file = File(directory, "profiles.json")
        file.writeText(
            """
            {"version":1,"profiles":[{
              "id":"profile/tenant:eu",
              "name":"EU gateway",
              "baseUrl":"HTTPS://EU.EXAMPLE.TEST/v1/",
              "credentialReference":"credential/tenant:eu"
            }]}
            """.trimIndent(),
        )

        val repository = OpenAiProfileRepository(OpenAiProfileMetadataStore(file), InMemoryCredentials())
        val profile = repository.profiles.value.single()
        val loaded = OpenAiProfileMetadataStore(file).load()
        assertTrue(loaded is OpenAiProfileMetadataLoadResult.Success)
        val reloaded = loaded as OpenAiProfileMetadataLoadResult.Success

        assertEquals(OpenAiConnectionProfileId("profile/tenant:eu"), profile.id)
        assertEquals(OpenAiCredentialReference("credential/tenant:eu"), profile.credentialReference)
        assertEquals("https://eu.example.test/v1", profile.baseUrl)
        assertEquals(OpenAiProfileMetadataCodec.CURRENT_VERSION, reloaded.sourceVersion)
        assertEquals(listOf(profile), reloaded.snapshot.profiles)
    }

    @Test
    fun createRollsBackCredentialWhenMetadataCommitFails() = withTemporaryDirectory { directory ->
        val nonDirectory = File(directory, "metadata-parent").apply { writeText("not a directory") }
        val credentials = InMemoryCredentials()
        val repository = OpenAiProfileRepository(
            OpenAiProfileMetadataStore(File(nonDirectory, "profiles.json")),
            credentials,
            newId = ids("profile-1", "credential-1"),
        )

        val result = repository.create("Production", "https://api.example.test", "bearer-that-must-not-survive")

        assertTrue(result is OpenAiProfileOperationResult.Failure)
        assertTrue((result as OpenAiProfileOperationResult.Failure).error is OpenAiProfileRepositoryError.Metadata)
        assertTrue(repository.profiles.value.isEmpty())
        assertTrue(credentials.references.isEmpty())
    }

    @Test
    fun persistedMetadataNeverContainsBearerMaterial() = withTemporaryDirectory { directory ->
        val file = File(directory, "profiles.json")
        val repository = OpenAiProfileRepository(
            OpenAiProfileMetadataStore(file),
            InMemoryCredentials(),
            newId = ids("profile-1", "credential-1"),
        )
        val bearer = "sk-secret-isolation-value"

        val created = requireSuccess(repository.create("Private gateway", "https://api.example.test/v1", bearer))
        val persisted = file.readText()

        assertFalse(persisted.contains(bearer))
        assertEquals(OpenAiCredentialReference("openai-bearer-credential-1"), created.credentialReference)
        assertEquals(created, requireSuccess(repository.profileForOperation(created.id)))
    }

    @Test
    fun editsKeepProfileIdentityWhileReplacingCredentialReferenceAndNormalizingEndpoint() = withTemporaryDirectory { directory ->
        val credentials = InMemoryCredentials()
        val repository = OpenAiProfileRepository(
            OpenAiProfileMetadataStore(File(directory, "profiles.json")),
            credentials,
            newId = ids("profile/custom:stable", "credential-before", "credential-after"),
        )
        val created = requireSuccess(repository.create("Original", "https://api.example.test", "old-token"))

        val result = repository.edit(
            id = created.id,
            displayName = "Renamed",
            baseUrl = "HTTPS://API.EXAMPLE.TEST/v1///",
            replacementBearerToken = "new-token",
        )
        val updated = repository.profiles.value.single()

        assertEquals(OpenAiProfileMutationResult.Success, result)
        assertEquals(created.id, updated.id)
        assertEquals("Renamed", updated.displayName)
        assertEquals("https://api.example.test/v1", updated.baseUrl)
        assertEquals(OpenAiCredentialReference("openai-bearer-credential-after"), updated.credentialReference)
        assertFalse(credentials.contains(created.credentialReference))
        assertTrue(credentials.contains(updated.credentialReference))
    }

    @Test
    fun metadataCodecCanonicalizesAcceptedEndpointForms() {
        data class Case(val raw: String, val expected: String)
        val cases = listOf(
            Case(" https://API.EXAMPLE.TEST/ ", "https://api.example.test"),
            Case("https://api.example.test:8443/compatible/v1/", "https://api.example.test:8443/compatible/v1"),
        )

        cases.forEach { case ->
            assertEquals(case.expected, OpenAiProfileMetadataCodec.normalizeBaseUrl(case.raw))
        }
    }

    @Test
    fun deleteCommitsMetadataBeforeRetiringCredentialAndAllowsCleanupRetry() = withTemporaryDirectory { directory ->
        val credentials = InMemoryCredentials()
        val repository = OpenAiProfileRepository(
            OpenAiProfileMetadataStore(File(directory, "profiles.json")),
            credentials,
            newId = ids("profile-1", "credential-1"),
        )
        val created = requireSuccess(repository.create("Gateway", "https://api.example.test", "token"))
        credentials.failDeletes = true

        val deletion = repository.delete(created.id)

        assertTrue(deletion is OpenAiProfileMutationResult.CleanupPending)
        assertTrue(repository.profiles.value.isEmpty())
        assertTrue(credentials.contains(created.credentialReference))

        credentials.failDeletes = false
        val retry = repository.retryCredentialCleanup((deletion as OpenAiProfileMutationResult.CleanupPending).pending)

        assertEquals(OpenAiProfileMutationResult.Success, retry)
        assertFalse(credentials.contains(created.credentialReference))
    }

    @Test
    fun deletingReferencedProfileLeavesItsStableIdUnavailableInsteadOfRebindingToAnotherProfile() = withTemporaryDirectory { directory ->
        val repository = OpenAiProfileRepository(
            OpenAiProfileMetadataStore(File(directory, "profiles.json")),
            InMemoryCredentials(),
            newId = ids("profile-referenced", "credential-referenced", "profile-other", "credential-other"),
        )
        val referenced = requireSuccess(repository.create("Referenced", "https://referenced.example.test", "token-a"))
        val other = requireSuccess(repository.create("Other", "https://other.example.test", "token-b"))

        assertEquals(OpenAiProfileMutationResult.Success, repository.delete(referenced.id))
        val unavailable = repository.profileForOperation(referenced.id)

        assertTrue(unavailable is OpenAiProfileOperationResult.Failure)
        assertTrue((unavailable as OpenAiProfileOperationResult.Failure).error is OpenAiProfileRepositoryError.UnknownProfile)
        assertEquals(other, requireSuccess(repository.profileForOperation(other.id)))
    }

    private class InMemoryCredentials : OpenAiBearerCredentialStore {
        private val values = mutableMapOf<OpenAiCredentialReference, String>()
        var failDeletes = false

        val references: Set<OpenAiCredentialReference>
            get() = values.keys

        override fun replace(
            reference: OpenAiCredentialReference,
            bearerToken: CharSequence,
        ): OpenAiCredentialStoreResult<Unit> {
            if (bearerToken.isBlank()) return OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.InvalidCredential)
            values[reference] = bearerToken.toString()
            return OpenAiCredentialStoreResult.Success(Unit)
        }

        override fun delete(reference: OpenAiCredentialReference): OpenAiCredentialStoreResult<Unit> {
            if (failDeletes) return OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.ProtectedStorageUnavailable)
            values.remove(reference)
            return OpenAiCredentialStoreResult.Success(Unit)
        }

        override fun contains(reference: OpenAiCredentialReference): Boolean = reference in values

        override fun <T> use(
            reference: OpenAiCredentialReference,
            block: (CharSequence) -> T,
        ): OpenAiCredentialStoreResult<T> = values[reference]?.let { OpenAiCredentialStoreResult.Success(block(it)) }
            ?: OpenAiCredentialStoreResult.Failure(OpenAiCredentialStoreError.Missing)
    }

    private fun ids(vararg values: String): () -> String {
        val remaining = ArrayDeque(values.asList())
        return { remaining.removeFirst() }
    }

    private fun <T> requireSuccess(result: OpenAiProfileOperationResult<T>): T = when (result) {
        is OpenAiProfileOperationResult.Success -> result.value
        is OpenAiProfileOperationResult.Failure -> throw AssertionError("Expected success, got $result")
    }


    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val directory = createTempDirectory("openai-profile-test-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
