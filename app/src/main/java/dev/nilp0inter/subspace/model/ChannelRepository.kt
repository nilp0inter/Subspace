package dev.nilp0inter.subspace.model

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

    fun loadWebhookChannel(): WebhookChannel = WebhookChannel(
        url = prefs.getString(KEY_WEBHOOK_URL, "") ?: "",
        verb = WebhookVerb.valueOf(prefs.getString(KEY_WEBHOOK_VERB, WebhookVerb.POST.name) ?: WebhookVerb.POST.name),
        headers = decodeWebhookHeaders(prefs.getString(KEY_WEBHOOK_HEADERS, "") ?: ""),
        bodyTemplate = prefs.getString(KEY_WEBHOOK_BODY_TEMPLATE, WebhookChannel.DEFAULT_BODY_TEMPLATE)
            ?: WebhookChannel.DEFAULT_BODY_TEMPLATE,
    )

    fun saveWebhookChannel(channel: WebhookChannel) {
        prefs.edit()
            .putString(KEY_WEBHOOK_URL, channel.url)
            .putString(KEY_WEBHOOK_VERB, channel.verb.name)
            .putString(KEY_WEBHOOK_HEADERS, encodeWebhookHeaders(channel.headers))
            .putString(KEY_WEBHOOK_BODY_TEMPLATE, channel.bodyTemplate)
            .apply()
    }

    /**
     * The single source of truth for channel ordering across both surfaces
     * (phone dashboard + Android Auto browse tree). Order emanates solely from
     * this repository per `car-media-channel-browse` spec "Channel ordering is
     * stable across surfaces"; the ordering is [Channel.orderIndex] ascending
     * (JournalChannel at index 0, DebugChannel at index 1 today).
     */
    fun loadChannels(): List<Channel> =
        listOf(loadJournal(), loadWebhookChannel(), loadDebugChannel()).sortedBy { it.orderIndex }

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
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_VERB = "webhook_verb"
        private const val KEY_WEBHOOK_HEADERS = "webhook_headers"
        private const val KEY_WEBHOOK_BODY_TEMPLATE = "webhook_body_template"
        private const val KEY_ACTIVE_CHANNEL = "active_channel_id"

        private fun encodeWebhookHeaders(headers: List<WebhookHeader>): String =
            headers.joinToString("\n") { header -> "${header.name}: ${header.value}" }

        private fun decodeWebhookHeaders(encoded: String): List<WebhookHeader> =
            encoded.lineSequence()
                .filter(String::isNotBlank)
                .map { line ->
                    val separator = line.indexOf(':')
                    if (separator < 0) {
                        WebhookHeader(line, "")
                    } else {
                        WebhookHeader(
                            name = line.substring(0, separator).trim(),
                            value = line.substring(separator + 1).trim(),
                        )
                    }
                }
                .toList()
    }
}
