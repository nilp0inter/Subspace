package dev.nilp0inter.subspace.openai.adapter

import com.openai.errors.PermissionDeniedException
import com.openai.errors.UnauthorizedException
import dev.nilp0inter.subspace.channel.capability.OpenAiModelDiscoveryCapability
import dev.nilp0inter.subspace.model.OpenAiAvailabilityReason
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiModelChoice
import dev.nilp0inter.subspace.model.OpenAiModelDiscoveryOutcome
import dev.nilp0inter.subspace.model.OpenAiModelId
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Profile-scoped, bounded `/models` discovery. A failed refresh keeps a private stale cache for
 * diagnostic/UI recovery but publishes Unavailable: cached choices never make a profile ready.
 */
internal class OpenAiSdkModelDiscoveryService(
    private val clients: OpenAiSdkClientRegistry,
    private val maxModels: Int = DEFAULT_MAX_MODELS,
) : OpenAiModelDiscoveryCapability {
    init {
        require(maxModels > 0) { "maxModels must be positive" }
    }

    private val lock = Any()
    private val cache = mutableMapOf<OpenAiConnectionProfileId, List<OpenAiModelChoice>>()
    private val generations = mutableMapOf<OpenAiConnectionProfileId, Long>()
    private val _states = MutableStateFlow<Map<OpenAiConnectionProfileId, OpenAiModelDiscoveryOutcome>>(emptyMap())
    val states: StateFlow<Map<OpenAiConnectionProfileId, OpenAiModelDiscoveryOutcome>> = _states.asStateFlow()

    override suspend fun discover(profileId: OpenAiConnectionProfileId): OpenAiModelDiscoveryOutcome = refresh(profileId)

    suspend fun refresh(profileId: OpenAiConnectionProfileId): OpenAiModelDiscoveryOutcome {
        val generation = begin(profileId)
        val outcome = withContext(Dispatchers.IO) {
            when (val execution = clients.execute(profileId) { client ->
                client.models().list().items()
                    .asSequence()
                    .map { model -> model.id().trim() }
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(maxModels)
                    .map { id -> OpenAiModelChoice(OpenAiModelId(id), id) }
                    .toList()
            }) {
                is OpenAiSdkClientRegistry.Execution.Current -> {
                    if (execution.value.isEmpty()) {
                        OpenAiModelDiscoveryOutcome.Unavailable(profileId, OpenAiAvailabilityReason.INVALID_RESPONSE)
                    } else {
                        OpenAiModelDiscoveryOutcome.Available(profileId, execution.value)
                    }
                }
                is OpenAiSdkClientRegistry.Execution.Unavailable ->
                    OpenAiModelDiscoveryOutcome.Unavailable(profileId, execution.reason)
                OpenAiSdkClientRegistry.Execution.Stale ->
                    OpenAiModelDiscoveryOutcome.Unavailable(profileId, OpenAiAvailabilityReason.CANCELLED)
                OpenAiSdkClientRegistry.Execution.Cancelled ->
                    OpenAiModelDiscoveryOutcome.Unavailable(profileId, OpenAiAvailabilityReason.CANCELLED)
                is OpenAiSdkClientRegistry.Execution.Failed ->
                    OpenAiModelDiscoveryOutcome.Unavailable(profileId, execution.throwable.discoveryReason())
            }
        }
        publishIfCurrent(profileId, generation, outcome)
        return outcome
    }

    /** Non-authoritative cache for display only; callers must use [discover] for readiness. */
    fun cachedChoices(profileId: OpenAiConnectionProfileId): List<OpenAiModelChoice> =
        synchronized(lock) { cache[profileId].orEmpty() }

    /** Removes model state when its profile is deleted, preventing a future identity reuse fallback. */
    fun invalidate(profileId: OpenAiConnectionProfileId) = synchronized(lock) {
        cache.remove(profileId)
        generations[profileId] = (generations[profileId] ?: 0L) + 1L
        _states.value = _states.value - profileId
    }

    private fun begin(profileId: OpenAiConnectionProfileId): Long = synchronized(lock) {
        val generation = (generations[profileId] ?: 0L) + 1L
        generations[profileId] = generation
        _states.value = _states.value + (profileId to OpenAiModelDiscoveryOutcome.Loading(profileId))
        generation
    }

    private fun publishIfCurrent(
        profileId: OpenAiConnectionProfileId,
        generation: Long,
        outcome: OpenAiModelDiscoveryOutcome,
    ) = synchronized(lock) {
        if (generations[profileId] == generation) {
            if (outcome is OpenAiModelDiscoveryOutcome.Available) cache[profileId] = outcome.models
            _states.value = _states.value + (profileId to outcome)
        }
    }

    private fun Throwable.discoveryReason(): OpenAiAvailabilityReason = when (this) {
        is UnauthorizedException,
        is PermissionDeniedException -> OpenAiAvailabilityReason.AUTHENTICATION_FAILED
        is SocketTimeoutException,
        is TimeoutException,
        is ConnectException,
        is IOException -> OpenAiAvailabilityReason.UNREACHABLE
        else -> when (javaClass.simpleName) {
            "AuthenticationException" -> OpenAiAvailabilityReason.AUTHENTICATION_FAILED
            "BadRequestException", "NotFoundException", "UnprocessableEntityException", "OpenAIInvalidDataException" ->
                OpenAiAvailabilityReason.INVALID_RESPONSE
            else -> OpenAiAvailabilityReason.UNREACHABLE
        }
    }

    private companion object {
        const val DEFAULT_MAX_MODELS = 128
    }
}
