package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.dependency.PackageMountAccess
import dev.nilp0inter.subspace.dependency.PackageMountDeclaration
import dev.nilp0inter.subspace.dependency.PackageMountKind
import dev.nilp0inter.subspace.dependency.PackageResourcesDeclaration
import dev.nilp0inter.subspace.model.BuiltInChannelDescriptors
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.ChannelConfigurationMigrationStep
import dev.nilp0inter.subspace.model.ChannelConfigurationProvider
import dev.nilp0inter.subspace.model.ChannelImplementationDescriptor
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelPreparationTraits
import dev.nilp0inter.subspace.model.ChannelPresentationMetadata
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ProviderConfigurationResult
import dev.nilp0inter.subspace.resource.MountAvailability
import dev.nilp0inter.subspace.resource.MountUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.8: Regression proving the built-in Journal `DirectoryField` raw-path picker
 * path remains distinct from and unchanged beside the new generic resource mount
 * editor path.
 *
 * The built-in Journal provider exposes a scalar [ChannelConfigurationField.DirectoryField]
 * (`baseDirectory`) resolved through the legacy raw-path picker (`pickDirectory`,
 * keyed by configuration owner + field ID) and declares NO resource mounts, so the
 * generic SAF mount editor path (`pickMount`, keyed by owner + implementation +
 * declaration ID) is never triggered for it. An external Lua-style provider declares
 * resource mounts and uses NO DirectoryField. The two paths never overlap.
 */
class BuiltInJournalPickerRegressionTest {

    @Test
    fun `built-in Journal exposes a scalar DirectoryField for baseDirectory`() {
        val journal = BuiltInChannelDescriptors.journal

        val directoryFields = journal.configurationFields
            .filterIsInstance<ChannelConfigurationField.DirectoryField>()

        assertEquals(1, directoryFields.size)
        assertEquals("baseDirectory", directoryFields[0].id)
        assertEquals("Storage directory", directoryFields[0].label)
        assertFalse(directoryFields[0].required)
    }

    @Test
    fun `built-in Journal declares no resource mounts so the mount editor path is never triggered`() {
        val journal = BuiltInChannelDescriptors.journal

        assertTrue(
            "built-in Journal must declare no resource mounts",
            journal.resourceDeclarations.mounts.isEmpty(),
        )

        // With no declarations, the generic mount editor projection yields no rows,
        // so ResourceMountEditorRow is never rendered for the built-in Journal.
        val entries = MountEditorProjection.entries(journal.resourceDeclarations.mounts) {
            MountAvailability.Unavailable(MountUnavailableReason.Undeclared)
        }
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `built-in Journal DirectoryField result stays in scalar config payload, never a mount binding`() {
        val journal = BuiltInChannelDescriptors.journal
        val initial = OpaqueJsonObject.parse(
            """{"baseDirectory":null,"saveVoice":true,"saveText":false}""",
        ).getOrThrow()

        // The legacy raw-path picker writes the resolved path string into the scalar
        // payload through payloadWithFieldValues; it never touches the binding store.
        val result = payloadWithFieldValues(
            initialPayload = initial,
            fields = journal.configurationFields,
            values = mapOf("baseDirectory" to "/storage/emulated/0/Journal"),
        ).toJsonObject()

        assertEquals("/storage/emulated/0/Journal", result.getString("baseDirectory"))
        assertTrue(result.getBoolean("saveVoice"))
        assertFalse(result.getBoolean("saveText"))
    }

    @Test
    fun `external mount-declaring provider uses the mount path and no DirectoryField`() {
        val external = externalMountDescriptor()

        // The external provider declares a resource mount...
        assertEquals(1, external.resourceDeclarations.mounts.size)
        assertEquals("output", external.resourceDeclarations.mounts[0].id)

        // ...and uses NO scalar DirectoryField, so the legacy raw-path picker is never
        // offered for its mount; the generic mount editor path owns selection.
        val directoryFields = external.configurationFields
            .filterIsInstance<ChannelConfigurationField.DirectoryField>()
        assertTrue(directoryFields.isEmpty())

        val entries = MountEditorProjection.entries(external.resourceDeclarations.mounts) {
            MountAvailability.Unavailable(MountUnavailableReason.Unbound)
        }
        assertEquals(1, entries.size)
        assertEquals("output", entries[0].declaration.declarationId)
        assertTrue(entries[0].isBlocking)
    }

    @Test
    fun `mount selection request is keyed by declaration ID, not a scalar field path`() {
        val external = externalMountDescriptor()
        val declaration = external.resourceDeclarations.mounts[0]

        val request = MountSelectionRequest(
            ownerInstanceId = "instance-1",
            implementationId = external.implementationId,
            declarationId = declaration.id,
        )

        // The request carries the declaration ID; it is never a DirectoryField ID and
        // never a configuration field path.
        assertEquals("output", request.declarationId)
        assertEquals(external.implementationId, request.implementationId)
        assertEquals("instance-1", request.ownerInstanceId)
        // Contrast: the legacy DirectoryField is addressed by (ownerId, fieldId).
        val journalDirectoryField = BuiltInChannelDescriptors.journal.configurationFields
            .filterIsInstance<ChannelConfigurationField.DirectoryField>().first()
        assertFalse(request.declarationId == journalDirectoryField.id)
    }

    private fun externalMountDescriptor(): ChannelImplementationDescriptor {
        val implementationId = ChannelImplementationId("github-repository:12345")
        return ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("External Journal", "LUA PACKAGE", "Requires output mount."),
            configuration = StubConfigurationProvider(implementationId),
            configurationFields = listOf(
                ChannelConfigurationField.ChoiceField(
                    id = "output_mode",
                    label = "Output mode",
                    choices = listOf(
                        ChannelConfigurationField.ChoiceField.Choice("voice", "Voice"),
                        ChannelConfigurationField.ChoiceField.Choice("text", "Text"),
                    ),
                ),
            ),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
            resourceDeclarations = PackageResourcesDeclaration(
                listOf(
                    PackageMountDeclaration(
                        id = "output",
                        kind = PackageMountKind.DIRECTORY_TREE,
                        access = PackageMountAccess.READ_WRITE,
                        required = true,
                        label = "Journal output directory",
                        help = "Where the external journal writes entries",
                    ),
                ),
            ),
        )
    }

    private class StubConfigurationProvider(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        override val currentSchemaVersion: Int = 1

        override fun defaultPayload(): OpaqueJsonObject =
            OpaqueJsonObject.parse("""{"output_mode":"voice"}""").getOrThrow()

        override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
            ProviderConfigurationResult.Success(
                dev.nilp0inter.subspace.model.ValidatedChannelConfiguration(
                    implementationId,
                    schemaVersion,
                    payload,
                ),
            )

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep =
            ChannelConfigurationMigrationStep.Success(payload)
    }
}
