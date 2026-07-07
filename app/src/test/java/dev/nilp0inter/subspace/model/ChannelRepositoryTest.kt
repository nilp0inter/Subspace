package dev.nilp0inter.subspace.model

import android.content.Context
import android.content.SharedPreferences
import io.sleepwalker.core.keymap.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRepositoryTest {
    @Test
    fun journalRoundTripPersistsConfiguration() {
        val repository = ChannelRepository(FakeSharedPreferences())
        val channel = JournalChannel(
            baseDirectory = "/storage/emulated/0/Subspace",
            saveVoice = false,
            saveText = true,
        )

        repository.saveJournal(channel)

        assertEquals(channel, repository.loadJournal())
    }

    @Test
    fun journalRejectsBothOutputsDisabled() {
        assertThrows(IllegalArgumentException::class.java) {
            JournalChannel(saveVoice = false, saveText = false)
        }
    }

    @Test
    fun keyboardChannelIsReadyDependsOnBridgeConnectionState() {
        val channelConnected = KeyboardChannel(
            hostProfile = HostProfile.LINUX_US,
            bridgeConnectedProvider = { true }
        )
        assertTrue(channelConnected.isReady)

        val channelDisconnected = KeyboardChannel(
            hostProfile = HostProfile.LINUX_US,
            bridgeConnectedProvider = { false }
        )
        assertFalse(channelDisconnected.isReady)
    }

    @Test
    fun keyboardRoundTripPersistsHostProfile() {
        val prefs = FakeSharedPreferences()
        val repository = ChannelRepository(prefs)
        val channel = KeyboardChannel(
            hostProfile = HostProfile.LINUX_US
        )

        repository.saveKeyboard(channel)

        assertEquals("linux:us", prefs.getString("keyboard_host_profile", null))

        var bridgeConnectedCalled = false
        val loaded = repository.loadKeyboard(bridgeConnectedProvider = {
            bridgeConnectedCalled = true
            true
        })

        assertEquals(HostProfile.LINUX_US, loaded.hostProfile)
        assertTrue(loaded.isReady)
        assertTrue(bridgeConnectedCalled)
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
