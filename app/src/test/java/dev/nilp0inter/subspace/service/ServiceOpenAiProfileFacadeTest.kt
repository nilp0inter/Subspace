package dev.nilp0inter.subspace.service

import com.openai.client.OpenAIClient
import com.openai.models.models.Model
import com.openai.models.models.ModelListPage
import com.openai.services.blocking.ModelService
import dev.nilp0inter.subspace.dependency.DynamicChoiceSource
import dev.nilp0inter.subspace.dependency.PackageConfigurationLimits
import dev.nilp0inter.subspace.model.DynamicConfigurationChoice
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceRequest
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolution
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceSourceId
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceSourceRegistry
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason
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
import io.sleepwalker.core.keymap.HostProfile
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk
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
                keyboardChoices = KeyboardOutputChoiceHierarchy(
                    listOf(HostProfile("linux", "us"), HostProfile("linux", "us", "dvorak")),
                ),
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
                        DynamicConfigurationChoiceSourceId.OPENAI_MODELS,
                        dependencyValue = created.id,
                    ),
                ),
            )
            assertEquals(
                DynamicConfigurationChoiceResolution.Available(
                    listOf(DynamicConfigurationChoice("profile-stable-id", "Renamed gateway")),
                ),
                facade.dynamicChoiceResolver.resolve(
                    DynamicConfigurationChoiceRequest(DynamicConfigurationChoiceSourceId.OPENAI_CONNECTION_PROFILES),
                ),
            )
            // Keyboard hierarchy is reachable through the shared facade registry: the
            // default final profile resolves once its platform:layout dependency is supplied.
            assertEquals(
                DynamicConfigurationChoiceResolution.Available(
                    listOf(
                        DynamicConfigurationChoice("linux:us", "Default"),
                        DynamicConfigurationChoice("linux:us:dvorak", "dvorak"),
                    ),
                ),
                facade.dynamicChoiceResolver.resolve(
                    DynamicConfigurationChoiceRequest(
                        DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
                        dependencyValue = "linux:us",
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
                        DynamicConfigurationChoiceSourceId.OPENAI_MODELS,
                        dependencyValue = created.id,
                    ),
                ),
            )
            operations.close()
        }
    }

    @Test
    fun `keyboard hierarchy partitions the complete corpus into platform layout and profile stages`() {
        val hierarchy = KeyboardOutputChoiceHierarchy(hierarchyCorpus())

        assertEquals(
            listOf("linux", "macos", "windows"),
            hierarchy.resolvePlatforms().availableIds(),
        )
        assertEquals(
            listOf("linux:de", "linux:us"),
            hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "linux")).availableIds(),
        )
        assertEquals(
            listOf("windows:us"),
            hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "windows")).availableIds(),
        )
        assertEquals(
            listOf("linux:us", "linux:us:dvorak", "linux:us:intl"),
            hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "linux:us")).availableIds(),
        )
        assertEquals(
            listOf("linux:de"),
            hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "linux:de")).availableIds(),
        )

        // Complete partition: the union of every layout's profile stage reproduces
        // the full distinct key set exactly, with no loss and no duplication.
        val layouts = hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "linux")).availableIds() +
            hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "windows")).availableIds() +
            hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "macos")).availableIds()
        val partitioned = layouts.flatMap {
            hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, it)).availableIds()
        }
        assertEquals(hierarchyCorpus().map { it.key }.distinct().sorted(), partitioned.sorted())
        assertEquals(partitioned.size, partitioned.toSet().size)
    }

    @Test
    fun `keyboard hierarchy publishes deterministic ids and labels regardless of corpus order`() {
        val corpus = hierarchyCorpus()
        val forward = KeyboardOutputChoiceHierarchy(corpus)
        val reverse = KeyboardOutputChoiceHierarchy(corpus.reversed())

        assertEquals(forward.resolvePlatforms(), reverse.resolvePlatforms())
        for (platform in forward.resolvePlatforms().availableIds()) {
            assertEquals(
                forward.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, platform)),
                reverse.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, platform)),
            )
        }
        for (layout in forward.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "linux")).availableIds()) {
            val forwardProfiles = forward.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, layout))
            val reverseProfiles = reverse.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, layout))
            assertEquals(forwardProfiles, reverseProfiles)
            val choices = forwardProfiles.availableChoices()
            assertEquals("ids unique", choices.size, choices.map { it.id }.toSet().size)
            assertEquals("labels unique", choices.size, choices.map { it.label }.toSet().size)
            assertTrue(choices.all { it.id.isNotBlank() && it.label.isNotBlank() })
        }
    }

    @Test
    fun `keyboard hierarchy keeps every published stage within the choice bound`() {
        val hierarchy = KeyboardOutputChoiceHierarchy(hierarchyCorpus())

        assertTrue(hierarchy.resolvePlatforms().availableChoices().size <= PackageConfigurationLimits.MAX_CHOICES)
        for (platform in hierarchy.resolvePlatforms().availableIds()) {
            val layouts = hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, platform)).availableChoices()
            assertTrue(layouts.size <= PackageConfigurationLimits.MAX_CHOICES)
            for (layout in layouts.map { it.id }) {
                val profiles = hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, layout)).availableChoices()
                assertTrue(profiles.size <= PackageConfigurationLimits.MAX_CHOICES)
            }
        }
    }

    @Test
    fun `keyboard hierarchy keeps the default linux us profile reachable from an empty corpus`() {
        val hierarchy = KeyboardOutputChoiceHierarchy(emptyList())

        assertEquals(listOf("linux"), hierarchy.resolvePlatforms().availableIds())
        assertEquals(listOf("linux:us"), hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "linux")).availableIds())
        assertEquals(
            listOf(DynamicConfigurationChoice("linux:us", "Default")),
            hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "linux:us")).availableChoices(),
        )
    }

    @Test
    fun `keyboard hierarchy resolves dependent stages with missing or invalid dependencies`() {
        val hierarchy = KeyboardOutputChoiceHierarchy(hierarchyCorpus())
        val missing = DynamicConfigurationChoiceResolution.Unavailable(
            DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
        )

        assertEquals(missing, hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, null)))
        assertEquals(missing, hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "  ")))
        assertEquals(missing, hierarchy.resolveLayouts(request(KEYBOARD_OUTPUT_LAYOUTS, "beos")))

        assertEquals(missing, hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, null)))
        assertEquals(missing, hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "  ")))
        assertEquals(missing, hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "linux")))
        assertEquals(missing, hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, ":us")))
        assertEquals(missing, hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "linux:")))
        assertEquals(missing, hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "beos:us")))
        assertEquals(missing, hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "linux:fr")))
    }

    @Test
    fun `keyboard hierarchy never truncates and lets the registry reject an over-bound stage all-or-nothing`() = runTest {
        // One platform:layout pair carries more profiles than the bound; its siblings stay small.
        val oversized = (0..PackageConfigurationLimits.MAX_CHOICES).map { index ->
            HostProfile("linux", "us", "variant-$index")
        }
        val hierarchy = KeyboardOutputChoiceHierarchy(oversized + HostProfile("linux", "de"))

        // The resolver publishes the COMPLETE slice: one past the bound, never truncated.
        val raw = hierarchy.resolveProfiles(request(KEYBOARD_OUTPUT_PROFILES, "linux:us")).availableChoices()
        assertEquals(PackageConfigurationLimits.MAX_CHOICES + 1, raw.size)

        val registry = registryOf(hierarchy)
        // The over-bound stage is rejected all-or-nothing, never a partial list.
        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ),
            registry.resolve(request(KEYBOARD_OUTPUT_PROFILES, "linux:us")),
        )
        // Sibling stages remain fully available.
        assertEquals(
            listOf("linux:de"),
            (registry.resolve(request(KEYBOARD_OUTPUT_PROFILES, "linux:de")) as
                DynamicConfigurationChoiceResolution.Available).choices.map { it.id },
        )
        assertEquals(
            listOf("linux:de", "linux:us"),
            (registry.resolve(request(KEYBOARD_OUTPUT_LAYOUTS, "linux")) as
                DynamicConfigurationChoiceResolution.Available).choices.map { it.id },
        )
    }

    private fun hierarchyCorpus(): List<HostProfile> = listOf(
        HostProfile("linux", "us"),
        HostProfile("linux", "us", "intl"),
        HostProfile("linux", "us", "dvorak"),
        HostProfile("linux", "de"),
        HostProfile("windows", "us"),
        HostProfile("macos", "us"),
    )

    private fun request(
        source: DynamicConfigurationChoiceSourceId,
        dependencyValue: String? = null,
    ): DynamicConfigurationChoiceRequest = DynamicConfigurationChoiceRequest(source, dependencyValue)

    private fun registryOf(hierarchy: KeyboardOutputChoiceHierarchy): DynamicConfigurationChoiceSourceRegistry =
        DynamicConfigurationChoiceSourceRegistry().apply {
            register(KEYBOARD_OUTPUT_PLATFORMS, 5.seconds) { hierarchy.resolvePlatforms() }
            register(KEYBOARD_OUTPUT_LAYOUTS, 5.seconds) { request -> hierarchy.resolveLayouts(request) }
            register(KEYBOARD_OUTPUT_PROFILES, 5.seconds) { request -> hierarchy.resolveProfiles(request) }
        }

    private fun DynamicConfigurationChoiceResolution.availableChoices(): List<DynamicConfigurationChoice> =
        (this as DynamicConfigurationChoiceResolution.Available).choices

    private fun DynamicConfigurationChoiceResolution.availableIds(): List<String> = availableChoices().map { it.id }

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

    private companion object {
        val KEYBOARD_OUTPUT_PLATFORMS = DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS)
        val KEYBOARD_OUTPUT_LAYOUTS = DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS)
        val KEYBOARD_OUTPUT_PROFILES = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES
    }
}
