package dev.nilp0inter.subspace.model

import android.content.Context
import android.content.SharedPreferences

class ChannelRepository(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun loadCaptainsLog(): CaptainsLogChannel = CaptainsLogChannel(
        baseDirectory = prefs.getString(KEY_BASE_DIRECTORY, null),
        saveVoice = prefs.getBoolean(KEY_SAVE_VOICE, true),
        saveText = prefs.getBoolean(KEY_SAVE_TEXT, true),
    )

    fun saveCaptainsLog(channel: CaptainsLogChannel) {
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

    fun loadActiveChannelId(): String =
        prefs.getString(KEY_ACTIVE_CHANNEL, CaptainsLogChannel.ID) ?: CaptainsLogChannel.ID

    fun saveActiveChannelId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_CHANNEL, id).apply()
    }

    companion object {
        private const val PREFS_NAME = "channels"
        private const val KEY_BASE_DIRECTORY = "captains_log_base_directory"
        private const val KEY_SAVE_VOICE = "captains_log_save_voice"
        private const val KEY_SAVE_TEXT = "captains_log_save_text"
        private const val KEY_DEBUG_MODE = "debug_channel_mode"
        private const val KEY_ACTIVE_CHANNEL = "active_channel_id"
    }
}
