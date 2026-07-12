package dev.nilp0inter.subspace.openai.adapter

import com.openai.client.OpenAIClient
import dev.nilp0inter.subspace.model.OpenAiAvailabilityReason
import dev.nilp0inter.subspace.model.OpenAiConnectionProfile
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.openai.OpenAiBearerCredentialStore
import dev.nilp0inter.subspace.openai.OpenAiCredentialStoreError
import dev.nilp0inter.subspace.openai.OpenAiCredentialStoreResult
import dev.nilp0inter.subspace.openai.OpenAiProfileOperationResult
import dev.nilp0inter.subspace.openai.OpenAiProfileRepository
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns SDK clients for profile revisions. The SDK client never crosses this adapter package.
 *
 * A lease captures the endpoint and credential-reference generation that selected it. Every
 * result must be admitted through [Execution.Current], so an edit or deletion cannot publish a
 * response from its retired profile revision.
 */
internal class OpenAiSdkClientRegistry(
    private val profiles: OpenAiProfileRepository,
    private val credentials: OpenAiBearerCredentialStore,
    private val clientFactory: (bearerToken: String, baseUrl: String, timeout: Duration) -> OpenAIClient =
        OpenAiSdkClientFactory::create,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
) {
    sealed interface Execution<out T> {
        data class Current<T>(val value: T) : Execution<T>
        data class Unavailable(val reason: OpenAiAvailabilityReason) : Execution<Nothing>
        data object Stale : Execution<Nothing>
        data object Cancelled : Execution<Nothing>
        data class Failed(val throwable: Throwable) : Execution<Nothing>
    }

    private data class Revision(
        val profileId: OpenAiConnectionProfileId,
        val baseUrl: String,
        val credentialReference: String,
    )

    private data class Entry(
        val revision: Revision,
        val generation: Long,
        val client: OpenAIClient,
    )

    private val lock = Any()
    private val clients = mutableMapOf<OpenAiConnectionProfileId, Entry>()
    private var nextGeneration = 1L
    private var closed = false
    private val closer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openai-sdk-client-close").apply { isDaemon = true }
    }

    /**
     * Invokes [operation] while the selected profile revision is current. The bearer token is
     * materialized only inside the credential-store callback, immediately before SDK construction.
     */
    fun <T> execute(
        profileId: OpenAiConnectionProfileId,
        operation: (OpenAIClient) -> T,
    ): Execution<T> {
        if (synchronized(lock) { closed }) return Execution.Unavailable(OpenAiAvailabilityReason.HOST_NOT_READY)
        val profile = when (val resolved = profiles.profileForOperation(profileId)) {
            is OpenAiProfileOperationResult.Success -> resolved.value
            is OpenAiProfileOperationResult.Failure -> return Execution.Unavailable(OpenAiAvailabilityReason.PROFILE_MISSING)
        }
        val revision = profile.revision()
        val entry: Entry = when (val credential = credentials.use(profile.credentialReference) { bearer ->
            acquire(revision, bearer)
        }) {
            is OpenAiCredentialStoreResult.Success -> credential.value
                ?: return Execution.Unavailable(OpenAiAvailabilityReason.HOST_NOT_READY)
            is OpenAiCredentialStoreResult.Failure -> return Execution.Unavailable(credential.error.availabilityReason())
        }

        return try {
            val value = operation(entry.client)
            if (isCurrent(entry)) Execution.Current(value) else Execution.Stale
        } catch (error: Throwable) {
            if (error is CancellationException || Thread.currentThread().isInterrupted) Execution.Cancelled else Execution.Failed(error)
        }
    }

    /** Retires one profile immediately; callers invoke this after successful edit/delete commits. */
    fun invalidate(profileId: OpenAiConnectionProfileId) {
        val retired = synchronized(lock) { clients.remove(profileId) }
        retired?.let(::closeBounded)
    }

    /** Releases every retained client during host shutdown; no default client is retained. */
    fun close() {
        val retired = synchronized(lock) {
            closed = true
            val snapshot = clients.values.toList()
            clients.clear()
            snapshot
        }
        retired.forEach(::closeBounded)
        closer.shutdown()
        closer.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    private fun acquire(revision: Revision, bearerToken: CharSequence): Entry? {
        val retired: Entry?
        val selected: Entry
        synchronized(lock) {
            if (closed) return null
            clients[revision.profileId]?.takeIf { it.revision == revision }?.let { return it }
            selected = Entry(
                revision = revision,
                generation = nextGeneration++,
                client = clientFactory(bearerToken.toString(), revision.baseUrl, requestTimeout),
            )
            retired = clients.put(revision.profileId, selected)
        }
        retired?.let(::closeBounded)
        return selected
    }

    private fun isCurrent(entry: Entry): Boolean {
        val profile = when (val resolved = profiles.profileForOperation(entry.revision.profileId)) {
            is OpenAiProfileOperationResult.Success -> resolved.value
            is OpenAiProfileOperationResult.Failure -> return false
        }
        return profile.revision() == entry.revision && synchronized(lock) {
            clients[entry.revision.profileId]?.generation == entry.generation
        }
    }

    private fun closeBounded(entry: Entry) {
        val future = closer.submit { entry.client.close() }
        try {
            future.get(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
        }
    }

    private fun OpenAiConnectionProfile.revision() = Revision(id, baseUrl, credentialReference.value)

    private fun OpenAiCredentialStoreError.availabilityReason(): OpenAiAvailabilityReason = when (this) {
        OpenAiCredentialStoreError.Missing,
        OpenAiCredentialStoreError.CorruptCredential -> OpenAiAvailabilityReason.CREDENTIAL_MISSING
        OpenAiCredentialStoreError.InvalidCredential -> OpenAiAvailabilityReason.CREDENTIAL_MISSING
        OpenAiCredentialStoreError.ProtectedStorageUnavailable -> OpenAiAvailabilityReason.HOST_NOT_READY
    }

    private companion object {
        val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}
