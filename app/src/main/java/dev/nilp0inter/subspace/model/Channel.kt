package dev.nilp0inter.subspace.model

sealed interface Channel {
    val id: String
    val name: String
    val isReady: Boolean
}

data class JournalChannel(
    override val id: String = ID,
    override val name: String = NAME,
    val baseDirectory: String? = null,
    val saveVoice: Boolean = true,
    val saveText: Boolean = true,
) : Channel {
    override val isReady: Boolean
        get() = baseDirectory != null && (saveVoice || saveText)

    init {
        require(saveVoice || saveText) { "Journal must save voice, text, or both" }
    }

    companion object {
        const val ID = "captains-log"
        const val NAME = "Journal"
    }
}

enum class DebugMode {
    ECHO, STT, TTS, STT_TTS
}

data class DebugChannel(
    override val id: String = ID,
    override val name: String = NAME,
    val mode: DebugMode = DebugMode.ECHO,
) : Channel {
    override val isReady: Boolean = true

    companion object {
        const val ID = "debug-channel"
        const val NAME = "Debug Channel"
    }
}

enum class WebhookVerb {
    GET, POST, PUT, PATCH, DELETE
}

data class WebhookHeader(
    val name: String,
    val value: String,
) {
    val isValid: Boolean
        get() = name.isValidHeaderName() && value.isValidHeaderValue()
}

data class WebhookChannel(
    override val id: String = ID,
    override val name: String = NAME,
    val url: String = "",
    val verb: WebhookVerb = WebhookVerb.POST,
    val headers: List<WebhookHeader> = emptyList(),
    val bodyTemplate: String = DEFAULT_BODY_TEMPLATE,
) : Channel {
    override val isReady: Boolean
        get() = url.isValidWebhookUrl() &&
            headers.all(WebhookHeader::isValid) &&
            bodyTemplate.contains(MESSAGE_PLACEHOLDER)

    companion object {
        const val ID = "webhook-channel"
        const val NAME = "Webhook Channel"
        const val MESSAGE_PLACEHOLDER = "{{message}}"
        const val DEFAULT_BODY_TEMPLATE = "{\"message\":\"{{message}}\"}"
    }
}

private fun String.isValidWebhookUrl(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("https://") || trimmed.startsWith("http://")
}

private fun String.isValidHeaderName(): Boolean =
    isNotBlank() && all { char -> char.code in 33..126 && char != ':' }

private fun String.isValidHeaderValue(): Boolean =
    isNotBlank() && none { char -> char == '\r' || char == '\n' }
