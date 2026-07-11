package dev.nilp0inter.subspace.model

import io.sleepwalker.core.keymap.HostProfile
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ChannelCatalogueTest {

    @Test
    fun codecRoundTripPreservesAllFields() {
        val journalConfig = JournalConfig(
            baseDirectory = "/test/dir",
            saveVoice = true,
            saveText = false
        )
        val journalDef = ChannelDefinition(
            id = "c1",
            name = "Journal 1",
            kind = ChannelKind.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            config = journalConfig
        )
        val debugDef = ChannelDefinition(
            id = "c2",
            name = "Debug 1",
            kind = ChannelKind.DEBUG,
            enabled = false,
            configSchemaVersion = 1,
            config = DebugConfig(DebugMode.TTS)
        )
        val keyboardDef = ChannelDefinition(
            id = "c3",
            name = "Keyboard 1",
            kind = ChannelKind.KEYBOARD,
            enabled = true,
            configSchemaVersion = 1,
            config = KeyboardConfig(HostProfile.LINUX_US)
        )

        val original = ChannelCatalogueSnapshot(
            definitions = listOf(journalDef, debugDef, keyboardDef),
            activeChannelId = "c1"
        )

        val json = ChannelCatalogueCodec.toJson(original)
        val parsed = ChannelCatalogueCodec.fromJson(json)

        assertEquals(original.activeChannelId, parsed.activeChannelId)
        assertEquals(original.definitions.size, parsed.definitions.size)
        
        val pJournal = parsed.definitions[0]
        assertEquals("c1", pJournal.id)
        assertEquals("Journal 1", pJournal.name)
        assertEquals(ChannelKind.JOURNAL, pJournal.kind)
        assertTrue(pJournal.enabled)
        val pJournalConfig = pJournal.config as JournalConfig
        assertEquals("/test/dir", pJournalConfig.baseDirectory)
        assertTrue(pJournalConfig.saveVoice)
        assertFalse(pJournalConfig.saveText)

        val pDebug = parsed.definitions[1]
        assertEquals("c2", pDebug.id)
        assertEquals("Debug 1", pDebug.name)
        assertEquals(ChannelKind.DEBUG, pDebug.kind)
        assertFalse(pDebug.enabled)
        val pDebugConfig = pDebug.config as DebugConfig
        assertEquals(DebugMode.TTS, pDebugConfig.mode)

        val pKeyboard = parsed.definitions[2]
        assertEquals("c3", pKeyboard.id)
        assertEquals("Keyboard 1", pKeyboard.name)
        assertEquals(ChannelKind.KEYBOARD, pKeyboard.kind)
        assertTrue(pKeyboard.enabled)
        val pKeyboardConfig = pKeyboard.config as KeyboardConfig
        assertEquals(HostProfile.LINUX_US.key, pKeyboardConfig.hostProfile.key)
    }

    @Test
    fun codecRejectsInvalidDocumentVersion() {
        val badJson = """
            {
              "version": 99,
              "activeChannelId": "c1",
              "definitions": []
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            ChannelCatalogueCodec.fromJson(badJson)
        }
    }

    @Test
    fun validatorRejectsEmptyDefinitions() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelCatalogueSnapshot(definitions = emptyList(), activeChannelId = "c1")
        }
    }

    @Test
    fun validatorRejectsDuplicateIds() {
        val def = ChannelDefinition(
            id = "c1",
            name = "J1",
            kind = ChannelKind.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            config = JournalConfig(null, true, true)
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChannelCatalogueSnapshot(definitions = listOf(def, def), activeChannelId = "c1")
        }
    }

    @Test
    fun validatorRejectsBlankIds() {
        val def = ChannelDefinition(
            id = "  ",
            name = "J1",
            kind = ChannelKind.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            config = JournalConfig(null, true, true)
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChannelCatalogueSnapshot(definitions = listOf(def), activeChannelId = "  ")
        }
    }

    @Test
    fun validatorRejectsActiveIdOutsideDefinitions() {
        val def = ChannelDefinition(
            id = "c1",
            name = "J1",
            kind = ChannelKind.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            config = JournalConfig(null, true, true)
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChannelCatalogueSnapshot(definitions = listOf(def), activeChannelId = "c2")
        }
    }

    @Test
    fun validatorRejectsInvalidJournalConfig() {
        val def = ChannelDefinition(
            id = "c1",
            name = "J1",
            kind = ChannelKind.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            config = JournalConfig(null, false, false) // Invalid: must save voice or text
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChannelCatalogueSnapshot(definitions = listOf(def), activeChannelId = "c1")
        }
    }

    @Test
    fun mutationsMaintainInvariants() {
        val d1 = ChannelDefinition("c1", "J1", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        val d2 = ChannelDefinition("c2", "D1", ChannelKind.DEBUG, true, 1, DebugConfig(DebugMode.ECHO))
        val d3 = ChannelDefinition("c3", "K1", ChannelKind.KEYBOARD, true, 1, KeyboardConfig(HostProfile.LINUX_US))

        val start = ChannelCatalogueSnapshot(listOf(d1, d2, d3), "c1")

        // 1. Select
        val selected = start.selectChannel("c2")
        assertEquals("c2", selected.activeChannelId)
        assertThrows(IllegalArgumentException::class.java) {
            start.selectChannel("unknown")
        }

        // 2. Add
        val d4 = ChannelDefinition("c4", "New", ChannelKind.DEBUG, true, 1, DebugConfig(DebugMode.ECHO))
        val added = start.addChannel(d4)
        assertEquals(4, added.definitions.size)
        assertEquals(d4, added.definitions.last())
        assertThrows(IllegalArgumentException::class.java) {
            start.addChannel(d1)
        }

        // 3. Update
        val updated = start.updateChannel("c2") { it.copy(name = "Updated Debug") }
        assertEquals("Updated Debug", updated.definitions[1].name)
        assertThrows(IllegalArgumentException::class.java) {
            start.updateChannel("unknown") { it }
        }

        // 4. Move
        val moved = start.moveChannel("c1", 2)
        assertEquals("c2", moved.definitions[0].id)
        assertEquals("c3", moved.definitions[1].id)
        assertEquals("c1", moved.definitions[2].id)
        assertEquals("c1", moved.activeChannelId) // active ID preserved
        assertThrows(IllegalArgumentException::class.java) {
            start.moveChannel("c1", 99)
        }

        // 5. Remove & Repair
        // Case A: Remove inactive
        val removedInactive = start.removeChannel("c2")
        assertEquals(2, removedInactive.definitions.size)
        assertEquals("c1", removedInactive.activeChannelId)

        // Case B: Remove active with successor (c1 removed, c2 is active)
        val removedActiveSuccessor = start.removeChannel("c1")
        assertEquals(2, removedActiveSuccessor.definitions.size)
        assertEquals("c2", removedActiveSuccessor.activeChannelId)

        // Case C: Remove active with only predecessor (c3 removed from [c1, c2, c3], active is c3)
        val activeAtEnd = start.selectChannel("c3")
        val removedActivePredecessor = activeAtEnd.removeChannel("c3")
        assertEquals(2, removedActivePredecessor.definitions.size)
        assertEquals("c2", removedActivePredecessor.activeChannelId)

        // Case D: Try to remove final channel
        val oneChannel = ChannelCatalogueSnapshot(listOf(d1), "c1")
        assertThrows(IllegalArgumentException::class.java) {
            oneChannel.removeChannel("c1")
        }
    }

    @Test
    fun fileStoreAtomicCommitsAndHandlesCorruption() {
        val tempFile = File.createTempFile("store_test", ".json").apply { deleteOnExit() }
        val store = ChannelCatalogueFileStore(tempFile)

        val d1 = ChannelDefinition("c1", "J1", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        val snapshot = ChannelCatalogueSnapshot(listOf(d1), "c1")

        // Save and Load
        store.save(snapshot)
        val loaded = store.load()
        assertEquals(snapshot.activeChannelId, loaded?.activeChannelId)

        // Corrupt file
        tempFile.writeText("invalid json content")
        assertThrows(IOException::class.java) {
            store.load()
        }
    }

    @Test
    fun repositoryUnchangedAfterCommitFailure() {
        val readOnlyFile = File.createTempFile("readonly_test", ".json").apply { deleteOnExit() }
        val prefs = FakeSharedPreferences()
        
        // Setup initial repo with defaults
        val repository = ChannelRepository(prefs, readOnlyFile)
        val initialSnapshot = repository.catalogueState.value

        // Make the file directory/file write-fail by deleting and creating as a directory, or just making write fail.
        // On Java/OS, making it a directory prevents file writeText.
        readOnlyFile.delete()
        readOnlyFile.mkdirs()

        // Mutation should throw IOException and NOT update state
        val d4 = ChannelDefinition("c4", "New", ChannelKind.DEBUG, true, 1, DebugConfig(DebugMode.ECHO))
        assertThrows(IOException::class.java) {
            repository.addChannel(d4)
        }

        // StateFlow remains unchanged
        assertEquals(initialSnapshot, repository.catalogueState.value)
    }

    @Test
    fun legacyMigrationPreservesValues() {
        val tempFile = File.createTempFile("migration_test", ".json").apply { deleteOnExit() }
        tempFile.delete() // Ensure it does not exist yet

        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("journal_base_directory", "/legacy/dir")
            .putBoolean("journal_save_voice", false)
            .putBoolean("journal_save_text", true)
            .putString("debug_channel_mode", "STT")
            .putString("keyboard_host_profile", "linux:us")
            .putString("active_channel_id", "debug-channel")
            .commit()

        val repository = ChannelRepository(prefs, tempFile)
        assertTrue(tempFile.exists())

        val snapshot = repository.catalogueState.value
        assertEquals("debug-channel", snapshot.activeChannelId)
        assertEquals(3, snapshot.definitions.size)

        val jDef = snapshot.definitions[0]
        assertEquals("captains-log", jDef.id)
        val jConfig = jDef.config as JournalConfig
        assertEquals("/legacy/dir", jConfig.baseDirectory)
        assertFalse(jConfig.saveVoice)
        assertTrue(jConfig.saveText)

        val dDef = snapshot.definitions[1]
        assertEquals("debug-channel", dDef.id)
        val dConfig = dDef.config as DebugConfig
        assertEquals(DebugMode.STT, dConfig.mode)

        val kDef = snapshot.definitions[2]
        assertEquals("keyboard-channel", kDef.id)
        val kConfig = kDef.config as KeyboardConfig
        assertEquals("linux:us", kConfig.hostProfile.key)
    }

    @Test
    fun updateChannelIsolatesSameKindInstances() {
        // Two Debug-kind definitions with distinct IDs and distinct configs.
        // Production seeds "debug-channel" (DebugMode.ECHO); we add a sibling
        // Debug instance "dbg-2" (DebugMode.STT) and then drive the same
        // updateChannel mutation path used in production to change only the
        // sibling's DebugMode. The seed Debug instance must stay byte-for-byte
        // unchanged — defending the same-kind isolation invariant: updating
        // one channel by ID must not touch any other definition, including a
        // same-kind sibling.
        val prefs = FakeSharedPreferences()
        val tempFile = File.createTempFile("iso_test", ".json").apply { deleteOnExit() }
        val repository = ChannelRepository(prefs, tempFile)

        val siblingId = "dbg-2"
        val siblingDef = ChannelDefinition(
            id = siblingId,
            name = "Debug Sibling",
            kind = ChannelKind.DEBUG,
            enabled = true,
            configSchemaVersion = 1,
            config = DebugConfig(DebugMode.STT)
        )
        repository.addChannel(siblingDef)

        val before = repository.catalogueState.value
        val idsBefore = before.definitions.map { it.id }
        val seedDebugBefore = before.definitions.first { it.id == DebugChannel.ID }
        val seedDebugConfigBefore = seedDebugBefore.config

        // Production mutation path: update only the sibling by ID.
        repository.updateChannel(siblingId) { def ->
            def.copy(config = (def.config as DebugConfig).copy(mode = DebugMode.TTS))
        }

        val after = repository.catalogueState.value

        // Order and active channel are untouched.
        assertEquals(idsBefore, after.definitions.map { it.id })
        assertEquals(before.activeChannelId, after.activeChannelId)

        // The seed Debug instance is unchanged — same reference, same equality,
        // same DebugMode. A bug that updates by kind or replaces the wrong
        // index would fail here.
        val seedDebugAfter = after.definitions.first { it.id == DebugChannel.ID }
        assertSame(seedDebugConfigBefore, seedDebugAfter.config)
        assertEquals(seedDebugBefore, seedDebugAfter)
        assertEquals(DebugMode.ECHO, (seedDebugAfter.config as DebugConfig).mode)

        // Only the sibling's DebugMode changed.
        val siblingAfter = after.definitions.first { it.id == siblingId }
        assertEquals(DebugMode.TTS, (siblingAfter.config as DebugConfig).mode)
        assertEquals(siblingDef.copy(config = DebugConfig(DebugMode.TTS)), siblingAfter)
    }

    @Test
    fun keyboardInstancesAreRepeatableAndRecreatable() {
        // Keyboard is a repeatable built-in kind, not a singleton: distinct
        // instance IDs must permit multiple KEYBOARD definitions, and removing
        // the migrated default must not prevent adding a replacement. Defends
        // the repeatability/recreation invariant of the catalogue independent
        // of any UI kind selector that may clip the KEYBOARD entry on narrow
        // phone widths.
        val prefs = FakeSharedPreferences()
        val tempFile = File.createTempFile("kb_repeat_test", ".json").apply { deleteOnExit() }
        val repository = ChannelRepository(prefs, tempFile)

        // Begin with the migrated/default catalogue.
        val start = repository.catalogueState.value
        assertEquals(KeyboardChannel.ID, start.definitions.last().id)
        assertTrue(start.activeChannelId in start.definitions.map { it.id })

        // 1. Add a second KEYBOARD definition with a distinct ID.
        val secondId = "kb-2"
        repository.addChannel(ChannelDefinition(
            id = secondId,
            name = "Keyboard Sibling",
            kind = ChannelKind.KEYBOARD,
            enabled = true,
            configSchemaVersion = 1,
            config = KeyboardConfig(HostProfile.LINUX_US)
        ))

        val afterAdd = repository.catalogueState.value
        assertTrue(afterAdd.definitions.isNotEmpty())
        assertTrue(afterAdd.activeChannelId in afterAdd.definitions.map { it.id })
        // Both keyboard instances coexist — Keyboard is repeatable, not a singleton.
        assertEquals(
            listOf(KeyboardChannel.ID, secondId),
            afterAdd.definitions.filter { it.kind == ChannelKind.KEYBOARD }.map { it.id }
        )

        // 2. Remove the original migrated default keyboard-channel.
        repository.removeChannel(KeyboardChannel.ID)

        val afterRemove = repository.catalogueState.value
        assertTrue(afterRemove.definitions.isNotEmpty())
        assertTrue(afterRemove.activeChannelId in afterRemove.definitions.map { it.id })
        // The seed keyboard is gone, but the kind is not orphaned.
        assertFalse(afterRemove.definitions.any { it.id == KeyboardChannel.ID })
        assertEquals(
            listOf(secondId),
            afterRemove.definitions.filter { it.kind == ChannelKind.KEYBOARD }.map { it.id }
        )

        // 3. Add a third replacement KEYBOARD definition with another distinct ID.
        val replacementId = "kb-3"
        repository.addChannel(ChannelDefinition(
            id = replacementId,
            name = "Keyboard Replacement",
            kind = ChannelKind.KEYBOARD,
            enabled = true,
            configSchemaVersion = 1,
            config = KeyboardConfig(HostProfile.LINUX_US)
        ))

        val afterReplace = repository.catalogueState.value
        assertTrue(afterReplace.definitions.isNotEmpty())
        assertTrue(afterReplace.activeChannelId in afterReplace.definitions.map { it.id })
        // The remaining keyboard IDs are the second and replacement IDs, in catalogue order.
        assertEquals(
            listOf(secondId, replacementId),
            afterReplace.definitions.filter { it.kind == ChannelKind.KEYBOARD }.map { it.id }
        )
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
            }
        }
    }
}
