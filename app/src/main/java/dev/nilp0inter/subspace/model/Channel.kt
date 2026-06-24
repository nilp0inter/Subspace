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
