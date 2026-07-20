package dev.nilp0inter.subspace.model

import android.content.SharedPreferences
import dev.nilp0inter.subspace.channel.JournalBuiltInProvider
import dev.nilp0inter.subspace.channel.KeyboardBuiltInProvider
import dev.nilp0inter.subspace.dependency.ArtifactDigest
import dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the post-removal catalogue state: fresh non-Debug seed, no
 * debug_channel_mode reads, active fallback, commit failure/retry, existing-catalogue
 * no merge, byte-exact builtin:debug record round-trip/unavailable/non-executable,
 * external Debug install does not rebind/alias/copy/select/mutate/delete the old
 * record, and unrelated definitions are preserved.
 *
 * No Debug* types are referenced — the removed built-in is addressed by the string
 * literal `builtin:debug` and raw JSON payloads.
 */
class LegacyDebugCatalogueTest {

    // ──────────────────────────────────────────────────────────────
    //  Spec: Legacy channel settings migrate once
    //  Scenario: First start with legacy settings
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `fresh seed produces only Journal and Keyboard definitions in built-in order`() {
        val prefs = mutablePrefs()
        val resolver = builtInDescriptorResolver()
        val catalogueFile = tempFile()

        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val snapshot = repository.catalogueState.value

        assertEquals(2, snapshot.definitions.size)
        assertEquals("captains-log", snapshot.definitions[0].id)
        assertEquals("Journal", snapshot.definitions[0].name)
        assertEquals("builtin:journal", snapshot.definitions[0].implementationId.value)
        assertEquals("keyboard-channel", snapshot.definitions[1].id)
        assertEquals("Keyboard Channel", snapshot.definitions[1].name)
        assertEquals("builtin:keyboard", snapshot.definitions[1].implementationId.value)
    }

    @Test
    fun `fresh seed does not create a Debug definition`() {
        val prefs = mutablePrefs()
        val resolver = builtInDescriptorResolver()
        val catalogueFile = tempFile()

        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val snapshot = repository.catalogueState.value

        assertFalse("Seed must not contain a Debug definition",
            snapshot.definitions.any { it.implementationId.value == "builtin:debug" })
        assertFalse("Seed must not contain a debug-channel instance ID",
            snapshot.definitions.any { it.id == "debug-channel" })
    }

    @Test
    fun `fresh seed does not read debug_channel_mode preference`() {
        val trackingPrefs = TrackingSharedPreferences()
        // Set a value for debug_channel_mode — the seed must not read it
        trackingPrefs.putString("debug_channel_mode", "STT")
        // Also set an active channel that is valid (journal) to avoid fallback path
        trackingPrefs.putString("active_channel_id", "captains-log")

        val resolver = builtInDescriptorResolver()
        val catalogueFile = tempFile()

        val repository = ChannelRepository(trackingPrefs, catalogueFile, resolver)
        val snapshot = repository.catalogueState.value

        // The seed must not have read debug_channel_mode
        assertFalse("debug_channel_mode must not be read during seeding",
            trackingPrefs.readKeys.contains("debug_channel_mode"))
        // Only Journal + Keyboard are seeded
        assertEquals(2, snapshot.definitions.size)
    }

    // ──────────────────────────────────────────────────────────────
    //  Scenario: First start with legacy active channel set to Debug
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `fresh seed with legacy active set to debug-channel falls back to a valid enabled instance`() {
        val prefs = mutablePrefs()
        prefs.putString("active_channel_id", "debug-channel")

        val resolver = builtInDescriptorResolver()
        val catalogueFile = tempFile()

        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val snapshot = repository.catalogueState.value

        assertFalse("Seed must not contain Debug", snapshot.definitions.any { it.id == "debug-channel" })
        val active = snapshot.definitions.find { it.id == snapshot.activeChannelId }
        assertNotNull("Active channel ID must reference a seeded definition", active)
        assertTrue("Fallback active channel must be enabled", active!!.enabled)
        assertEquals("captains-log", snapshot.activeChannelId)
    }

    @Test
    fun `fresh seed with legacy active set to journal preserves journal as active`() {
        val prefs = mutablePrefs()
        prefs.putString("active_channel_id", "captains-log")

        val resolver = builtInDescriptorResolver()
        val catalogueFile = tempFile()

        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val snapshot = repository.catalogueState.value

        assertEquals("captains-log", snapshot.activeChannelId)
    }

    @Test
    fun `fresh seed with legacy active set to keyboard preserves keyboard as active`() {
        val prefs = mutablePrefs()
        prefs.putString("active_channel_id", "keyboard-channel")

        val resolver = builtInDescriptorResolver()
        val catalogueFile = tempFile()

        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val snapshot = repository.catalogueState.value

        assertEquals("keyboard-channel", snapshot.activeChannelId)
    }

    // ──────────────────────────────────────────────────────────────
    //  Scenario: Migration commit fails
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `seed commit failure throws and does not write a catalogue file`() {
        val prefs = mutablePrefs()
        val resolver = builtInDescriptorResolver()
        // Point to a file in a non-creatable directory to force IOException
        val catalogueFile = File("/dev/null/cannot-create-dir/channels_catalogue.json")

        assertThrows(ChannelRepositoryLoadException::class.java) {
            ChannelRepository(prefs, catalogueFile, resolver)
        }
        assertFalse("No partial catalogue file should be written", catalogueFile.exists())
    }

    @Test
    fun `seed commit failure retains legacy preferences for retry on subsequent start`() {
        val prefs = mutablePrefs()
        prefs.putString("active_channel_id", "captains-log")
        prefs.putBoolean("journal_save_voice", false)
        prefs.putBoolean("journal_save_text", true)

        val resolver = builtInDescriptorResolver()
        val badFile = File("/dev/null/cannot-create-dir/channels_catalogue.json")

        // First start fails
        assertThrows(ChannelRepositoryLoadException::class.java) {
            ChannelRepository(prefs, badFile, resolver)
        }

        // Legacy preferences are still present (the SharedPreferences instance is unchanged)
        assertEquals("captains-log", prefs.getString("active_channel_id", null))
        assertEquals(false, prefs.getBoolean("journal_save_voice", true))
        assertEquals(true, prefs.getBoolean("journal_save_text", false))

        // Retry with a valid file succeeds — seeding runs again from the same prefs
        val goodFile = tempFile()
        val repository = ChannelRepository(prefs, goodFile, resolver)
        val snapshot = repository.catalogueState.value

        assertEquals(2, snapshot.definitions.size)
        assertEquals("captains-log", snapshot.activeChannelId)
        // Journal config reflects the retained prefs
        val journalPayload = JournalProviderConfigurationCodec.decode(
            snapshot.definitions[0].configPayload,
        ).getOrThrow()
        assertTrue(journalPayload.saveText)
    }

    // ──────────────────────────────────────────────────────────────
    //  Scenario: Catalogue already exists
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `existing valid catalogue is loaded without merging legacy preferences`() {
        val existingDefinitions = listOf(
            ChannelDefinition(
                id = "custom-journal",
                name = "My Journal",
                implementationId = BuiltInChannelImplementationIds.JOURNAL,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("""{"baseDirectory":"/custom","saveVoice":true,"saveText":true}""").getOrThrow(),
            ),
            ChannelDefinition(
                id = "custom-keyboard",
                name = "My Keyboard",
                implementationId = BuiltInChannelImplementationIds.KEYBOARD,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("""{"hostProfile":"linux:de"}""").getOrThrow(),
            ),
        )
        val existingSnapshot = ChannelCatalogueSnapshot(existingDefinitions, "custom-journal")
        val catalogueFile = tempFile()
        catalogueFile.writeText(ChannelCatalogueCodec.toJson(existingSnapshot))

        // Legacy prefs with different values — must be ignored
        val prefs = mutablePrefs()
        prefs.putString("active_channel_id", "captains-log")
        prefs.putBoolean("journal_save_voice", false)

        val resolver = builtInDescriptorResolver()
        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val snapshot = repository.catalogueState.value

        assertEquals("Existing catalogue definitions count must be preserved", 2, snapshot.definitions.size)
        assertEquals("custom-journal", snapshot.definitions[0].id)
        assertEquals("My Journal", snapshot.definitions[0].name)
        assertEquals("custom-keyboard", snapshot.definitions[1].id)
        assertEquals("custom-journal", snapshot.activeChannelId)
        val decodedJournal = JournalProviderConfigurationCodec.decode(
            snapshot.definitions[0].configPayload,
        ).getOrThrow()
        assertEquals("/custom", decodedJournal.baseDirectory)
        assertTrue(decodedJournal.saveVoice)
        assertTrue(decodedJournal.saveText)
    }

    @Test
    fun `existing catalogue with builtin debug definition loads without merging legacy preferences`() {
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"STT"}""").getOrThrow()
        val existingDefinitions = listOf(
            ChannelDefinition(
                id = "captains-log",
                name = "Journal",
                implementationId = BuiltInChannelImplementationIds.JOURNAL,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("""{"baseDirectory":null,"saveVoice":true,"saveText":true}""").getOrThrow(),
            ),
            ChannelDefinition(
                id = "debug-channel",
                name = "Debug Channel",
                implementationId = ChannelImplementationId("builtin:debug"),
                enabled = true,
                configSchemaVersion = 1,
                configPayload = debugPayload,
            ),
        )
        val existingSnapshot = ChannelCatalogueSnapshot(existingDefinitions, "captains-log")
        val catalogueFile = tempFile()
        catalogueFile.writeText(ChannelCatalogueCodec.toJson(existingSnapshot))

        val prefs = mutablePrefs()
        val resolver = builtInDescriptorResolver()
        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val snapshot = repository.catalogueState.value

        // The existing catalogue is loaded as-is; no merge from legacy prefs
        assertEquals(2, snapshot.definitions.size)
        assertEquals("captains-log", snapshot.definitions[0].id)
        assertEquals("debug-channel", snapshot.definitions[1].id)
        assertEquals("builtin:debug", snapshot.definitions[1].implementationId.value)
        assertEquals("captains-log", snapshot.activeChannelId)
    }

    // ──────────────────────────────────────────────────────────────
    //  Spec: Channel catalogue is the authoritative ordered source
    //  Scenario: Persisted legacy Debug definitions are preserved but unavailable
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `codec round-trips builtin debug definition byte-exact`() {
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"STT_TTS"}""").getOrThrow()
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition(
                    id = "captains-log",
                    name = "Journal",
                    implementationId = BuiltInChannelImplementationIds.JOURNAL,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.parse("""{"baseDirectory":null,"saveVoice":true,"saveText":true}""").getOrThrow(),
                ),
                ChannelDefinition(
                    id = "debug-channel",
                    name = "Debug Channel",
                    implementationId = ChannelImplementationId("builtin:debug"),
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = debugPayload,
                ),
            ),
            "captains-log",
        )

        val json = ChannelCatalogueCodec.toJson(snapshot)
        val decoded = (ChannelCatalogueCodec.decode(json) as ChannelCatalogueDecodeResult.Success).document.snapshot

        assertEquals(2, decoded.definitions.size)
        val debugDef = decoded.definitions[1]
        assertEquals("debug-channel", debugDef.id)
        assertEquals("Debug Channel", debugDef.name)
        assertEquals("builtin:debug", debugDef.implementationId.value)
        assertTrue(debugDef.enabled)
        assertEquals(1, debugDef.configSchemaVersion)
        assertEquals("""{"mode":"STT_TTS"}""", debugDef.configPayload.toJsonString())
    }

    @Test
    fun `builtin debug definition is preserved but unavailable through missing-provider path`() {
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"ECHO"}""").getOrThrow()
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition(
                    id = "journal-instance",
                    name = "Journal",
                    implementationId = BuiltInChannelImplementationIds.JOURNAL,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.parse("""{"baseDirectory":null,"saveVoice":true,"saveText":true}""").getOrThrow(),
                ),
                ChannelDefinition(
                    id = "debug-channel",
                    name = "Debug Channel",
                    implementationId = ChannelImplementationId("builtin:debug"),
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = debugPayload,
                ),
            ),
            "journal-instance",
        )

        // Provider migrator encounters missing provider for builtin:debug
        val resolver = builtInDescriptorResolver() // journal + keyboard only — no debug
        val migration = ChannelCatalogueProviderMigrator.migrate(snapshot, resolver)

        assertTrue("Migration must succeed even with missing providers", migration is ChannelCatalogueProviderMigrationResult.Success)
        val migrated = (migration as ChannelCatalogueProviderMigrationResult.Success).snapshot

        // The debug definition is preserved unchanged
        val debugDef = migrated.definitions[1]
        assertEquals("debug-channel", debugDef.id)
        assertEquals("Debug Channel", debugDef.name)
        assertEquals("builtin:debug", debugDef.implementationId.value)
        assertTrue(debugDef.enabled)
        assertEquals(1, debugDef.configSchemaVersion)
        assertEquals("""{"mode":"ECHO"}""", debugDef.configPayload.toJsonString())

        // The resolver reports the provider as missing
        val resolution = resolver.resolveDescriptor(ChannelImplementationId("builtin:debug"))
        assertTrue("builtin:debug must resolve as Missing", resolution is ChannelDescriptorResolution.Missing)
    }

    @Test
    fun `builtin debug definition is not executable through provider registry`() {
        val registry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }

        val resolution = registry.resolve(ChannelImplementationId("builtin:debug"))
        assertTrue("builtin:debug must not resolve to Available", resolution is ChannelProviderResolution.Missing)
    }

    @Test
    fun `builtin debug definition round-trips with extra unknown payload fields preserved`() {
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"TTS","extra":"future-field","nested":{"key":42}}""").getOrThrow()
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition(
                    id = "journal",
                    name = "J",
                    implementationId = BuiltInChannelImplementationIds.JOURNAL,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.parse("""{"baseDirectory":null,"saveVoice":true,"saveText":true}""").getOrThrow(),
                ),
                ChannelDefinition(
                    id = "debug",
                    name = "Debug",
                    implementationId = ChannelImplementationId("builtin:debug"),
                    enabled = false,
                    configSchemaVersion = 3,
                    configPayload = debugPayload,
                ),
            ),
            "journal",
        )

        val json = ChannelCatalogueCodec.toJson(snapshot)
        val decoded = (ChannelCatalogueCodec.decode(json) as ChannelCatalogueDecodeResult.Success).document.snapshot

        val debugDef = decoded.definitions[1]
        assertEquals("debug", debugDef.id)
        assertEquals("Debug", debugDef.name)
        assertEquals("builtin:debug", debugDef.implementationId.value)
        assertFalse(debugDef.enabled)
        assertEquals(3, debugDef.configSchemaVersion)
        // Payload is byte-exact — all fields including unknown ones preserved
        assertEquals(debugPayload.toJsonString(), debugDef.configPayload.toJsonString())
    }

    // ──────────────────────────────────────────────────────────────
    //  Scenario: Installing external Debug package does not migrate legacy instances
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `installing external debug provider does not rebind legacy builtin debug definition`() {
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"ECHO"}""").getOrThrow()
        val legacyDebugDef = ChannelDefinition(
            id = "debug-channel",
            name = "Debug Channel",
            implementationId = ChannelImplementationId("builtin:debug"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = debugPayload,
        )
        val journalDef = ChannelDefinition(
            id = "captains-log",
            name = "Journal",
            implementationId = BuiltInChannelImplementationIds.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse("""{"baseDirectory":null,"saveVoice":true,"saveText":true}""").getOrThrow(),
        )
        val snapshot = ChannelCatalogueSnapshot(
            listOf(journalDef, legacyDebugDef),
            "captains-log",
        )

        // Simulate external Debug installed with github-repository ID
        val externalDebugId = ChannelImplementationId("github-repository:123456")
        val registry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }

        // Publish the external Debug provider
        val repoId = GitHubRepositoryIdentity("123456")
        val digest = ArtifactDigest("a".repeat(64))
        val externalProvider = StubProvider(externalDebugId, fingerprintValue = digest.value)
        val result = registry.publishInstalledProviders(
            mapOf(externalDebugId to InstalledProviderBinding(repoId, digest, externalProvider)),
        )
        assertTrue("External Debug must publish successfully", result is InstalledProvidersPublicationResult.Success)

        // Migrate the catalogue with the external provider available
        val migration = ChannelCatalogueProviderMigrator.migrate(snapshot, registry)
        assertTrue(migration is ChannelCatalogueProviderMigrationResult.Success)
        val migrated = (migration as ChannelCatalogueProviderMigrationResult.Success).snapshot

        // Legacy builtin:debug definition is NOT rebound
        val legacyDef = migrated.definitions.find { it.id == "debug-channel" }!!
        assertEquals("builtin:debug", legacyDef.implementationId.value)
        assertEquals("Legacy definition must not be rebound to external provider",
            "builtin:debug", legacyDef.implementationId.value)
        assertEquals("""{"mode":"ECHO"}""", legacyDef.configPayload.toJsonString())

        // The external provider resolves under its own ID
        assertTrue(registry.resolve(externalDebugId) is ChannelProviderResolution.Available)
        // The legacy builtin:debug still resolves as Missing (not Available, not mapped to external)
        val legacyResolution = registry.resolve(ChannelImplementationId("builtin:debug"))
        assertTrue("builtin:debug must remain Missing after external install", legacyResolution is ChannelProviderResolution.Missing)
    }

    @Test
    fun `installing external debug provider does not alias copy select mutate or delete old record`() {
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"STT"}""").getOrThrow()
        val legacyDebugDef = ChannelDefinition(
            id = "debug-channel",
            name = "Debug Channel",
            implementationId = ChannelImplementationId("builtin:debug"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = debugPayload,
        )
        val keyboardDef = ChannelDefinition(
            id = "keyboard-channel",
            name = "Keyboard Channel",
            implementationId = BuiltInChannelImplementationIds.KEYBOARD,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse("""{"hostProfile":"linux:us"}""").getOrThrow(),
        )
        val snapshot = ChannelCatalogueSnapshot(
            listOf(legacyDebugDef, keyboardDef),
            "keyboard-channel",
        )

        val externalDebugId = ChannelImplementationId("github-repository:789012")
        val registry = ChannelImplementationProviderRegistry().apply {
            register(KeyboardBuiltInProvider())
            register(JournalBuiltInProvider())
        }

        val repoId = GitHubRepositoryIdentity("789012")
        val digest = ArtifactDigest("b".repeat(64))
        val externalProvider = StubProvider(externalDebugId, fingerprintValue = digest.value)
        registry.publishInstalledProviders(
            mapOf(externalDebugId to InstalledProviderBinding(repoId, digest, externalProvider)),
        )

        val migration = ChannelCatalogueProviderMigrator.migrate(snapshot, registry)
        val migrated = (migration as ChannelCatalogueProviderMigrationResult.Success).snapshot

        // NO ALIAS: the external provider does not appear under builtin:debug
        assertEquals("builtin:debug", migrated.definitions[0].implementationId.value)

        // NO COPY: the debug definition config payload is unchanged
        assertEquals("""{"mode":"STT"}""", migrated.definitions[0].configPayload.toJsonString())

        // NO SELECT: the active channel is not changed to the external provider
        assertEquals("keyboard-channel", migrated.activeChannelId)
        assertFalse(migrated.activeChannelId == "debug-channel")

        // NO MUTATE: enabled state, schema version, name, instance ID all preserved
        assertEquals("debug-channel", migrated.definitions[0].id)
        assertEquals("Debug Channel", migrated.definitions[0].name)
        assertTrue(migrated.definitions[0].enabled)
        assertEquals(1, migrated.definitions[0].configSchemaVersion)

        // NO DELETE: the legacy definition still exists
        assertEquals(2, migrated.definitions.size)

        // External provider is available under its own ID only — requires manual instance creation
        assertTrue(registry.resolve(externalDebugId) is ChannelProviderResolution.Available)
        // Legacy definition cannot be resolved as the external provider
        assertFalse(registry.resolve(ChannelImplementationId("builtin:debug")) is ChannelProviderResolution.Available)
    }

    @Test
    fun `external debug provider does not get a catalogue instance automatically`() {
        val externalDebugId = ChannelImplementationId("github-repository:999999")
        val registry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }

        val repoId = GitHubRepositoryIdentity("999999")
        val digest = ArtifactDigest("c".repeat(64))
        val externalProvider = StubProvider(externalDebugId, fingerprintValue = digest.value)
        registry.publishInstalledProviders(
            mapOf(externalDebugId to InstalledProviderBinding(repoId, digest, externalProvider)),
        )

        // Create a catalogue with only journal + keyboard (no external debug instance)
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition(
                    id = "captains-log",
                    name = "Journal",
                    implementationId = BuiltInChannelImplementationIds.JOURNAL,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.parse("""{"baseDirectory":null,"saveVoice":true,"saveText":true}""").getOrThrow(),
                ),
                ChannelDefinition(
                    id = "keyboard-channel",
                    name = "Keyboard Channel",
                    implementationId = BuiltInChannelImplementationIds.KEYBOARD,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.parse("""{"hostProfile":"linux:us"}""").getOrThrow(),
                ),
            ),
            "captains-log",
        )

        val migration = ChannelCatalogueProviderMigrator.migrate(snapshot, registry)
        val migrated = (migration as ChannelCatalogueProviderMigrationResult.Success).snapshot

        // No instance of the external provider is created automatically
        assertFalse("No automatic instance creation for external Debug",
            migrated.definitions.any { it.implementationId == externalDebugId })
        assertEquals(2, migrated.definitions.size)
    }

    // ──────────────────────────────────────────────────────────────
    //  Unrelated definitions preserved
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `unrelated definitions are preserved alongside legacy debug definition`() {
        val journalPayload = OpaqueJsonObject.parse("""{"baseDirectory":"/data","saveVoice":true,"saveText":false}""").getOrThrow()
        val keyboardPayload = OpaqueJsonObject.parse("""{"hostProfile":"linux:de"}""").getOrThrow()
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"TTS"}""").getOrThrow()

        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition("captains-log", "Journal", BuiltInChannelImplementationIds.JOURNAL, true, 1, journalPayload),
                ChannelDefinition("debug-channel", "Debug Channel", ChannelImplementationId("builtin:debug"), true, 1, debugPayload),
                ChannelDefinition("keyboard-channel", "Keyboard Channel", BuiltInChannelImplementationIds.KEYBOARD, true, 1, keyboardPayload),
            ),
            "captains-log",
        )

        val resolver = builtInDescriptorResolver()
        val migration = ChannelCatalogueProviderMigrator.migrate(snapshot, resolver)
        val migrated = (migration as ChannelCatalogueProviderMigrationResult.Success).snapshot

        // All three definitions preserved
        assertEquals(3, migrated.definitions.size)
        assertEquals("captains-log", migrated.definitions[0].id)
        assertEquals("debug-channel", migrated.definitions[1].id)
        assertEquals("keyboard-channel", migrated.definitions[2].id)

        // Journal payload unchanged (provider migrates it but it should be the same)
        assertEquals(journalPayload.toJsonString(), migrated.definitions[0].configPayload.toJsonString())
        // Debug payload unchanged (missing provider → preserved byte-exact)
        assertEquals(debugPayload.toJsonString(), migrated.definitions[1].configPayload.toJsonString())
        // Keyboard payload unchanged
        assertEquals(keyboardPayload.toJsonString(), migrated.definitions[2].configPayload.toJsonString())
    }

    @Test
    fun `unrelated definitions preserved through codec round-trip with legacy debug`() {
        val journalPayload = OpaqueJsonObject.parse("""{"baseDirectory":"/data","saveVoice":false,"saveText":true}""").getOrThrow()
        val keyboardPayload = OpaqueJsonObject.parse("""{"hostProfile":"linux:us"}""").getOrThrow()
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"DELAYED_ECHO"}""").getOrThrow()

        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition("journal-1", "Journal One", BuiltInChannelImplementationIds.JOURNAL, true, 1, journalPayload),
                ChannelDefinition("debug-1", "Debug One", ChannelImplementationId("builtin:debug"), true, 1, debugPayload),
                ChannelDefinition("keyboard-1", "Keyboard One", BuiltInChannelImplementationIds.KEYBOARD, true, 1, keyboardPayload),
            ),
            "journal-1",
        )

        val json = ChannelCatalogueCodec.toJson(snapshot)
        val decoded = (ChannelCatalogueCodec.decode(json) as ChannelCatalogueDecodeResult.Success).document.snapshot

        assertEquals(3, decoded.definitions.size)
        assertEquals("journal-1", decoded.definitions[0].id)
        assertEquals("Journal One", decoded.definitions[0].name)
        assertEquals(journalPayload.toJsonString(), decoded.definitions[0].configPayload.toJsonString())

        assertEquals("debug-1", decoded.definitions[1].id)
        assertEquals("Debug One", decoded.definitions[1].name)
        assertEquals("builtin:debug", decoded.definitions[1].implementationId.value)
        assertEquals(debugPayload.toJsonString(), decoded.definitions[1].configPayload.toJsonString())

        assertEquals("keyboard-1", decoded.definitions[2].id)
        assertEquals(keyboardPayload.toJsonString(), decoded.definitions[2].configPayload.toJsonString())
    }

    // ──────────────────────────────────────────────────────────────
    //  Active fallback with legacy debug as active through full repository load
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `repository load with persisted legacy debug as active preserves it but provider is missing`() {
        val debugPayload = OpaqueJsonObject.parse("""{"mode":"STT"}""").getOrThrow()
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition(
                    id = "captains-log",
                    name = "Journal",
                    implementationId = BuiltInChannelImplementationIds.JOURNAL,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.parse("""{"baseDirectory":null,"saveVoice":true,"saveText":true}""").getOrThrow(),
                ),
                ChannelDefinition(
                    id = "debug-channel",
                    name = "Debug Channel",
                    implementationId = ChannelImplementationId("builtin:debug"),
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = debugPayload,
                ),
            ),
            // Active is debug-channel — the catalogue is valid (active ID exists in definitions)
            // even though the provider is missing
            "debug-channel",
        )

        val catalogueFile = tempFile()
        catalogueFile.writeText(ChannelCatalogueCodec.toJson(snapshot))

        val prefs = mutablePrefs()
        val resolver = builtInDescriptorResolver()
        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val loaded = repository.catalogueState.value

        // The debug definition is preserved
        assertEquals(2, loaded.definitions.size)
        assertEquals("debug-channel", loaded.definitions[1].id)
        assertEquals("builtin:debug", loaded.definitions[1].implementationId.value)
        assertEquals("""{"mode":"STT"}""", loaded.definitions[1].configPayload.toJsonString())
        // Active ID is preserved as-is (the catalogue invariants only require it exists in definitions)
        assertEquals("debug-channel", loaded.activeChannelId)
    }

    // ──────────────────────────────────────────────────────────────
    //  v1 legacy decode still maps DEBUG kind to builtin:debug literal
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `v1 catalogue with DEBUG kind decodes to builtin debug literal`() {
        val v1Json = """
            {
              "version": 1,
              "activeChannelId": "captains-log",
              "definitions": [
                {
                  "id": "captains-log",
                  "name": "Journal",
                  "kind": "JOURNAL",
                  "enabled": true,
                  "configSchemaVersion": 1,
                  "config": {"baseDirectory": null, "saveVoice": true, "saveText": true}
                },
                {
                  "id": "debug-channel",
                  "name": "Debug Channel",
                  "kind": "DEBUG",
                  "enabled": true,
                  "configSchemaVersion": 1,
                  "config": {"mode": "ECHO"}
                }
              ]
            }
        """.trimIndent()

        val result = ChannelCatalogueCodec.decode(v1Json)
        assertTrue("v1 decode must succeed", result is ChannelCatalogueDecodeResult.Success)
        val decoded = (result as ChannelCatalogueDecodeResult.Success).document
        assertEquals(1, decoded.sourceDocumentVersion)
        assertEquals(2, decoded.snapshot.definitions.size)

        val debugDef = decoded.snapshot.definitions[1]
        assertEquals("debug-channel", debugDef.id)
        assertEquals("builtin:debug", debugDef.implementationId.value)
        assertEquals("""{"mode":"ECHO"}""", debugDef.configPayload.toJsonString())
    }

    @Test
    fun `v1 catalogue with DEBUG kind loads through repository and triggers v2 migration write`() {
        val v1Json = """
            {
              "version": 1,
              "activeChannelId": "captains-log",
              "definitions": [
                {
                  "id": "captains-log",
                  "name": "Journal",
                  "kind": "JOURNAL",
                  "enabled": true,
                  "configSchemaVersion": 1,
                  "config": {"baseDirectory": null, "saveVoice": true, "saveText": true}
                },
                {
                  "id": "debug-channel",
                  "name": "Debug Channel",
                  "kind": "DEBUG",
                  "enabled": true,
                  "configSchemaVersion": 1,
                  "config": {"mode": "TTS"}
                }
              ]
            }
        """.trimIndent()

        val catalogueFile = tempFile()
        catalogueFile.writeText(v1Json)

        val prefs = mutablePrefs()
        val resolver = builtInDescriptorResolver()
        val repository = ChannelRepository(prefs, catalogueFile, resolver)
        val loaded = repository.catalogueState.value

        // Debug definition preserved with builtin:debug ID
        assertEquals(2, loaded.definitions.size)
        assertEquals("builtin:debug", loaded.definitions[1].implementationId.value)
        assertEquals("""{"mode":"TTS"}""", loaded.definitions[1].configPayload.toJsonString())

        // The file should now be v2
        val rewritten = catalogueFile.readText()
        val redecoded = ChannelCatalogueCodec.decode(rewritten)
        assertTrue(redecoded is ChannelCatalogueDecodeResult.Success)
        assertEquals(2, (redecoded as ChannelCatalogueDecodeResult.Success).document.sourceDocumentVersion)

        // A v1 backup should exist
        val backup = File(catalogueFile.parentFile, "${catalogueFile.name}.v1.bak")
        assertTrue("v1 backup must exist after migration", backup.exists())
    }

    // ──────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────

    private fun builtInDescriptorResolver(): ChannelImplementationDescriptorResolver {
        val registry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }
        return registry
    }

    private fun tempFile(): File {
        val file = File.createTempFile("legacy-debug-catalogue-test", ".json")
        file.delete() // ChannelRepository expects the file not to exist for fresh seed
        file.deleteOnExit()
        return file
    }

    private fun mutablePrefs(): MutableSharedPreferences = MutableSharedPreferences()

    /** SharedPreferences that tracks which keys were read. */
    private class TrackingSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        val readKeys = mutableSetOf<String>()

        override fun getAll(): MutableMap<String, *> { readKeys += values.keys; return values.toMutableMap() }
        override fun getString(key: String?, defValue: String?): String? {
            key?.let { readKeys += it }
            return values[key] as? String ?: defValue
        }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            key?.let { readKeys += it }
            return values[key] as? MutableSet<String> ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int { key?.let { readKeys += it }; return values[key] as? Int ?: defValue }
        override fun getLong(key: String?, defValue: Long): Long { key?.let { readKeys += it }; return values[key] as? Long ?: defValue }
        override fun getFloat(key: String?, defValue: Float): Float { key?.let { readKeys += it }; return values[key] as? Float ?: defValue }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean { key?.let { readKeys += it }; return values[key] as? Boolean ?: defValue }
        override fun contains(key: String?): Boolean { key?.let { readKeys += it }; return values.containsKey(key) }
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
            }
        }

        fun putString(key: String, value: String): TrackingSharedPreferences {
            edit().putString(key, value).apply()
            return this
        }

        fun putBoolean(key: String, value: Boolean): TrackingSharedPreferences {
            edit().putBoolean(key, value).apply()
            return this
        }
    }

    private class MutableSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
            }
        }

        fun putString(key: String, value: String): MutableSharedPreferences {
            edit().putString(key, value).apply()
            return this
        }

        fun putBoolean(key: String, value: Boolean): MutableSharedPreferences {
            edit().putBoolean(key, value).apply()
            return this
        }
    }

    /** Minimal stub provider for installed/external providers in tests. */
    private class StubProvider(
        private val implId: ChannelImplementationId,
        private val fingerprintValue: String = "stub-fingerprint",
    ) : ChannelImplementationProvider {
        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implId,
            presentation = ChannelPresentationMetadata("Stub", "STUB", "Stub unavailable"),
            configuration = object : ChannelConfigurationProvider {
                override val implementationId = implId
                override val currentSchemaVersion = 1
                override fun defaultPayload(): OpaqueJsonObject =
                    OpaqueJsonObject.parse("""{"default":true}""").getOrThrow()
                override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
                    ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implId, schemaVersion, payload))
                override fun migrateStep(fromSchemaVersion: Int, payload: OpaqueJsonObject): ChannelConfigurationMigrationStep =
                    ChannelConfigurationMigrationStep.Failure(
                        ChannelProviderError.UnsupportedSchemaVersion(implId, fromSchemaVersion, 1),
                    )
            },
            configurationFields = listOf(ChannelConfigurationField.TextField("mode", "Mode")),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )
        override val fingerprint = ProviderRevisionFingerprint(fingerprintValue)

        override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult =
            ChannelRuntimeConstructionResult.Failure(
                ChannelProviderError.RuntimeConstructionFailed(implId, "Stub does not construct runtimes"),
            )
    }
}