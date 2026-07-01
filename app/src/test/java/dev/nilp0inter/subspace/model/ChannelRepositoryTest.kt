package dev.nilp0inter.subspace.model

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRepositoryTest {
    @Test
    fun freshInstallSeedsDefaultChannels() {
        val repository = ChannelRepository(FakeSharedPreferences())

        val config = repository.loadConfiguration()

        assertEquals(listOf(JournalChannel.ID, DebugChannel.ID), config.channels.map { it.id })
        assertEquals(JournalChannel.ID, config.activeChannelId)
    }

    @Test
    fun existingInstallMigratesLegacyJournalAndDebugConfig() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("journal_base_directory", "/storage/emulated/0/Subspace")
            .putBoolean("journal_save_voice", false)
            .putBoolean("journal_save_text", true)
            .putString("debug_channel_mode", DebugMode.STT.name)
            .putString("active_channel_id", DebugChannel.ID)
            .apply()
        val repository = ChannelRepository(prefs)

        val config = repository.loadConfiguration()

        val journal = config.channels.filterIsInstance<JournalChannel>().single()
        val debug = config.channels.filterIsInstance<DebugChannel>().single()
        assertEquals("/storage/emulated/0/Subspace", journal.baseDirectory)
        assertEquals(false, journal.saveVoice)
        assertEquals(true, journal.saveText)
        assertEquals(DebugMode.STT, debug.mode)
        assertEquals(debug.id, config.activeChannelId)
    }

    @Test
    fun channelListRoundTripPersistsDuplicateDebugInstances() {
        val repository = ChannelRepository(FakeSharedPreferences())
        val channels = listOf(
            JournalChannel(name = "Journal", position = 0),
            DebugChannel(id = "debug-a", name = "Echo", position = 1, mode = DebugMode.ECHO),
            DebugChannel(id = "debug-b", name = "Speech", position = 2, mode = DebugMode.STT),
        )

        repository.saveChannels(channels)

        val loaded = repository.loadChannels()
        assertEquals(channels, loaded)
    }

    @Test
    fun duplicatePositionsNormalizeByPositionThenId() {
        val repository = ChannelRepository(FakeSharedPreferences())
        repository.saveChannels(
            listOf(
                DebugChannel(id = "debug-b", position = 0),
                DebugChannel(id = "debug-a", position = 0),
                JournalChannel(position = 0),
            ),
        )

        val loaded = repository.loadChannels()

        assertEquals(listOf("captains-log", "debug-a", "debug-b"), loaded.map { it.id })
        assertEquals(listOf(0, 1, 2), loaded.map { it.position })
    }

    @Test
    fun missingActiveChannelRecoversToFirstConfiguredChannel() {
        val prefs = FakeSharedPreferences()
        val repository = ChannelRepository(prefs)
        repository.saveChannels(listOf(DebugChannel(id = "debug-a", position = 0)))
        repository.saveActiveChannelId("missing")

        val config = repository.loadConfiguration()

        assertEquals("debug-a", config.activeChannelId)
        assertEquals("debug-a", repository.loadActiveChannelId())
    }

    @Test
    fun addDebugChannelCreatesIndependentInstanceAtRequestedPosition() {
        val repository = ChannelRepository(FakeSharedPreferences())
        repository.loadConfiguration()

        val created = repository.addDebugChannel(name = "Second Debug", position = 1)
        val loaded = repository.loadChannels()

        assertTrue(created.id.startsWith("debug-"))
        assertEquals(listOf(JournalChannel.ID, created.id, DebugChannel.ID), loaded.map { it.id })
        assertEquals("Second Debug", loaded[1].name)
    }

    @Test
    fun journalRejectsBothOutputsDisabled() {
        assertThrows(IllegalArgumentException::class.java) {
            JournalChannel(saveVoice = false, saveText = false)
        }
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
