package dev.nilp0inter.subspace.model

import io.sleepwalker.core.keymap.HostProfile

import android.content.Context
import android.content.SharedPreferences

class ChannelRepository(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun loadJournal(): JournalChannel = JournalChannel(
        baseDirectory = prefs.getString(KEY_BASE_DIRECTORY, null),
        saveVoice = prefs.getBoolean(KEY_SAVE_VOICE, true),
        saveText = prefs.getBoolean(KEY_SAVE_TEXT, true),
    )

    fun saveJournal(channel: JournalChannel) {
        prefs.edit()
            .putString(KEY_BASE_DIRECTORY, channel.baseDirectory)
            .putBoolean(KEY_SAVE_VOICE, channel.saveVoice)
            .putBoolean(KEY_SAVE_TEXT, channel.saveText)
            .apply()
    }

    fun loadDebugChannel(): DebugChannel = DebugChannel(
        mode = DebugMode.valueOf(prefs.getString(KEY_DEBUG_MODE, DebugMode.ECHO.name) ?: DebugMode.ECHO.name)
    )

    fun saveDebugChannel(channel: DebugChannel) {
        prefs.edit()
            .putString(KEY_DEBUG_MODE, channel.mode.name)
            .apply()
    }

    fun loadKeyboard(bridgeConnectedProvider: () -> Boolean = { false }): KeyboardChannel {
        val profileKey = prefs.getString(KEY_KEYBOARD_HOST_PROFILE, HostProfile.LINUX_US.key) ?: HostProfile.LINUX_US.key
        return KeyboardChannel(
            hostProfile = parseHostProfileKey(profileKey),
            bridgeConnectedProvider = bridgeConnectedProvider
        )
    }

    fun saveKeyboard(channel: KeyboardChannel) {
        prefs.edit()
            .putString(KEY_KEYBOARD_HOST_PROFILE, channel.hostProfile.key)
            .apply()
    }

    /**
     * Parse a [HostProfile] from its [HostProfile.key] string ("hostOs:layout[:variant]").
     * Falls back to [HostProfile.LINUX_US] if the key is malformed.
     */
    private fun parseHostProfileKey(key: String): HostProfile {
        val parts = key.split(":")
        if (parts.size < 2 || parts.any { it.isBlank() }) return HostProfile.LINUX_US
        return HostProfile(
            hostOs = parts[0],
            layout = parts[1],
            variant = if (parts.size >= 3) parts[2] else null,
        )
    }

    /**
     * The single source of truth for channel ordering across both surfaces
     * (phone dashboard + Android Auto browse tree). Order emanates solely from
     * this repository per `car-media-channel-browse` spec "Channel ordering is
     * stable across surfaces"; the ordering is [Channel.orderIndex] ascending
     * (JournalChannel at index 0, DebugChannel at index 1 today).
     */
    fun loadChannels(): List<Channel> =
        listOf(loadJournal(), loadDebugChannel(), loadKeyboard()).sortedBy { it.orderIndex }

    fun loadActiveChannelId(): String =
        prefs.getString(KEY_ACTIVE_CHANNEL, JournalChannel.ID) ?: JournalChannel.ID

    fun saveActiveChannelId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_CHANNEL, id).apply()
    }

    companion object {
        private const val PREFS_NAME = "channels"
        private const val KEY_BASE_DIRECTORY = "journal_base_directory"
        private const val KEY_SAVE_VOICE = "journal_save_voice"
        private const val KEY_SAVE_TEXT = "journal_save_text"
        private const val KEY_DEBUG_MODE = "debug_channel_mode"
        private const val KEY_ACTIVE_CHANNEL = "active_channel_id"
        private const val KEY_KEYBOARD_HOST_PROFILE = "keyboard_host_profile"
    }
}
