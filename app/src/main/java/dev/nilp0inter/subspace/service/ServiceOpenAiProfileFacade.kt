package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.DynamicConfigurationChoice
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceRequest
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolution
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolver
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason
import dev.nilp0inter.subspace.model.OpenAiAvailabilityReason
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiModelDiscoveryOutcome
import dev.nilp0inter.subspace.openai.OpenAiBearerCredentialStore
import dev.nilp0inter.subspace.openai.OpenAiProfileMutationResult
import dev.nilp0inter.subspace.openai.OpenAiProfileOperationResult
import dev.nilp0inter.subspace.openai.OpenAiProfileOperations
import dev.nilp0inter.subspace.openai.OpenAiProfileRepository
import dev.nilp0inter.subspace.openai.OpenAiProfileRepositoryError
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkModelDiscoveryService
import dev.nilp0inter.subspace.ui.OpenAiProfileEditRequest
import dev.nilp0inter.subspace.ui.OpenAiProfileModelUiState
import dev.nilp0inter.subspace.ui.OpenAiProfileUiError
import dev.nilp0inter.subspace.ui.OpenAiProfileUiItem
import dev.nilp0inter.subspace.ui.OpenAiProfileUiMutationResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceSourceId
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceSourceRegistry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import dev.nilp0inter.subspace.dependency.DynamicChoiceSource
internal class ServiceOpenAiProfileFacade(
    scope: kotlinx.coroutines.CoroutineScope,
    private val repository: OpenAiProfileRepository,
    private val credentials: OpenAiBearerCredentialStore,
    private val operations: OpenAiProfileOperations,
    private val models: OpenAiSdkModelDiscoveryService,
    keyboardChoices: KeyboardOutputChoiceHierarchy,
) {
    val profileUiState: StateFlow<List<OpenAiProfileUiItem>> = combine(repository.profiles, models.states) { profiles, states ->
        profiles.map { profile ->
            OpenAiProfileUiItem(
                id = profile.id.value,
                displayName = profile.displayName,
                baseUrl = profile.baseUrl,
                credentialConfigured = credentials.contains(profile.credentialReference),
                modelState = states[profile.id].toUiState(),
            )
        }
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    val dynamicChoiceResolver: DynamicConfigurationChoiceResolver = DynamicConfigurationChoiceSourceRegistry().apply {
        register(
            DynamicConfigurationChoiceSourceId.OPENAI_CONNECTION_PROFILES,
            PROFILE_CHOICE_DEADLINE,
        ) { _ ->
            DynamicConfigurationChoiceResolution.Available(
                repository.profiles.value.map { DynamicConfigurationChoice(it.id.value, it.displayName) },
            )
        }
        register(
            DynamicConfigurationChoiceSourceId.OPENAI_MODELS,
            MODEL_DISCOVERY_DEADLINE,
        ) { request ->
            val profileId = request.dependencyValue?.takeIf(String::isNotBlank)
                ?: return@register DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                )
            when (val outcome = models.discover(OpenAiConnectionProfileId(profileId))) {
                is OpenAiModelDiscoveryOutcome.Available -> DynamicConfigurationChoiceResolution.Available(
                    outcome.models.map { DynamicConfigurationChoice(it.id.value, it.label) },
                )
                is OpenAiModelDiscoveryOutcome.Loading -> DynamicConfigurationChoiceResolution.Loading
                is OpenAiModelDiscoveryOutcome.Unavailable -> DynamicConfigurationChoiceResolution.Unavailable(
                    outcome.reason.toChoiceUnavailableReason(),
                )
            }
        }
        register(
            DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS),
            PROFILE_CHOICE_DEADLINE,
        ) { _ ->
            keyboardChoices.resolvePlatforms()
        }
        register(
            DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS),
            PROFILE_CHOICE_DEADLINE,
        ) { request ->
            keyboardChoices.resolveLayouts(request)
        }
        register(
            DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
            PROFILE_CHOICE_DEADLINE,
        ) { request ->
            keyboardChoices.resolveProfiles(request)
        }
    }

    fun create(request: OpenAiProfileEditRequest): OpenAiProfileUiMutationResult {
        if (request.replacementBearerToken.isNullOrBlank()) return OpenAiProfileUiMutationResult.Failure(OpenAiProfileUiError.InvalidCredential)
        return operations.create(request.displayName, request.baseUrl, request.replacementBearerToken).toUiMutation()
    }

    fun update(request: OpenAiProfileEditRequest): OpenAiProfileUiMutationResult {
        val id = request.id?.takeIf(String::isNotBlank)
            ?: return OpenAiProfileUiMutationResult.Failure(OpenAiProfileUiError.ProfileUnavailable)
        return operations.edit(
            id = OpenAiConnectionProfileId(id),
            displayName = request.displayName,
            baseUrl = request.baseUrl,
            replacementBearerToken = request.replacementBearerToken,
        ).toUiMutation()
    }

    fun delete(id: String): OpenAiProfileUiMutationResult = runCatching {
        operations.delete(OpenAiConnectionProfileId(id)).toUiMutation()
    }.getOrElse { OpenAiProfileUiMutationResult.Failure(OpenAiProfileUiError.ProfileUnavailable) }

    suspend fun test(id: String) = refresh(id)

    suspend fun refresh(id: String) = runCatching {
        operations.refreshModels(OpenAiConnectionProfileId(id))
    }.getOrElse {
        OpenAiModelDiscoveryOutcome.Unavailable(OpenAiConnectionProfileId(id), OpenAiAvailabilityReason.HOST_NOT_READY)
    }

    private fun OpenAiModelDiscoveryOutcome?.toUiState(): OpenAiProfileModelUiState = when (this) {
        null -> OpenAiProfileModelUiState.NotLoaded
        is OpenAiModelDiscoveryOutcome.Loading -> OpenAiProfileModelUiState.Loading
        is OpenAiModelDiscoveryOutcome.Available -> OpenAiProfileModelUiState.Available(models.size)
        is OpenAiModelDiscoveryOutcome.Unavailable -> OpenAiProfileModelUiState.Unavailable(reason.toUiError())
    }

    private fun OpenAiProfileOperationResult<*>.toUiMutation(): OpenAiProfileUiMutationResult = when (this) {
        is OpenAiProfileOperationResult.Success -> OpenAiProfileUiMutationResult.Success
        is OpenAiProfileOperationResult.Failure -> OpenAiProfileUiMutationResult.Failure(error.toUiError())
    }

    private fun OpenAiProfileMutationResult.toUiMutation(): OpenAiProfileUiMutationResult = when (this) {
        OpenAiProfileMutationResult.Success, is OpenAiProfileMutationResult.CleanupPending -> OpenAiProfileUiMutationResult.Success
        is OpenAiProfileMutationResult.Failure -> OpenAiProfileUiMutationResult.Failure(error.toUiError())
    }

    private fun OpenAiProfileRepositoryError.toUiError(): OpenAiProfileUiError = when (this) {
        OpenAiProfileRepositoryError.InvalidDisplayName -> OpenAiProfileUiError.InvalidName
        OpenAiProfileRepositoryError.InvalidBaseUrl -> OpenAiProfileUiError.InvalidBaseUrl
        is OpenAiProfileRepositoryError.UnknownProfile -> OpenAiProfileUiError.ProfileUnavailable
        is OpenAiProfileRepositoryError.Credential -> OpenAiProfileUiError.CredentialUnavailable
        is OpenAiProfileRepositoryError.Metadata -> OpenAiProfileUiError.StorageFailed
    }

    private fun OpenAiAvailabilityReason.toUiError(): OpenAiProfileUiError = when (this) {
        OpenAiAvailabilityReason.AUTHENTICATION_FAILED -> OpenAiProfileUiError.AuthenticationFailed
        OpenAiAvailabilityReason.UNREACHABLE -> OpenAiProfileUiError.ConnectionFailed
        OpenAiAvailabilityReason.CANCELLED -> OpenAiProfileUiError.TimedOut
        else -> OpenAiProfileUiError.DiscoveryFailed
    }

    private fun OpenAiAvailabilityReason.toChoiceUnavailableReason(): DynamicConfigurationChoiceUnavailableReason = when (this) {
        OpenAiAvailabilityReason.PROFILE_MISSING,
        OpenAiAvailabilityReason.PROFILE_DISABLED,
        OpenAiAvailabilityReason.CREDENTIAL_MISSING -> DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING
        OpenAiAvailabilityReason.HOST_NOT_READY -> DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY
        else -> DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED
    }
}

/** In-memory profile and keymap choice reads; the deadline only guards a wedged source. */
private val PROFILE_CHOICE_DEADLINE: Duration = 5.seconds

/** Model discovery crosses the SDK network client, matching the 15s host invocation deadline. */
private val MODEL_DISCOVERY_DEADLINE: Duration = 15.seconds
