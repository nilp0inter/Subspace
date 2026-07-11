package dev.nilp0inter.subspace.model

import io.sleepwalker.core.keymap.HostProfile

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

data class KeyboardChannel(
    override val id: String = ID,
    override val name: String = NAME,
    val hostProfile: HostProfile = HostProfile.LINUX_US,
    val orderIndex: Int = 2,
    @Transient private val bridgeConnectedProvider: () -> Boolean = { false },
) : Channel {
    override val isReady: Boolean
        get() = bridgeConnectedProvider()

    companion object {
        const val ID = "keyboard-channel"
        const val NAME = "Keyboard Channel"
    }
}
fun JournalConfig.toLegacyChannel(name: String, id: String = JournalChannel.ID) = JournalChannel(
    id = id,
    name = name,
    baseDirectory = baseDirectory,
    saveVoice = saveVoice,
    saveText = saveText
)

fun DebugConfig.toLegacyChannel(name: String, id: String = DebugChannel.ID) = DebugChannel(
    id = id,
    name = name,
    mode = mode
)

fun KeyboardConfig.toLegacyChannel(name: String, id: String = KeyboardChannel.ID, bridgeConnectedProvider: () -> Boolean = { false }) = KeyboardChannel(
    id = id,
    name = name,
    hostProfile = hostProfile,
    bridgeConnectedProvider = bridgeConnectedProvider
)
