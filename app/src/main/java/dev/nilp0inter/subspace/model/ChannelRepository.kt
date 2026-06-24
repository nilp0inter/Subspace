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
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        baseDirectory = prefs.getString(KEY_BASE_DIRECTORY, null),
        saveVoice = prefs.getBoolean(KEY_SAVE_VOICE, true),
        saveText = prefs.getBoolean(KEY_SAVE_TEXT, true),
    )

    fun saveCaptainsLog(channel: CaptainsLogChannel) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, channel.enabled)
            .putString(KEY_BASE_DIRECTORY, channel.baseDirectory)
            .putBoolean(KEY_SAVE_VOICE, channel.saveVoice)
            .putBoolean(KEY_SAVE_TEXT, channel.saveText)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "channels"
        private const val KEY_ENABLED = "captains_log_enabled"
        private const val KEY_BASE_DIRECTORY = "captains_log_base_directory"
        private const val KEY_SAVE_VOICE = "captains_log_save_voice"
        private const val KEY_SAVE_TEXT = "captains_log_save_text"
    }
}
