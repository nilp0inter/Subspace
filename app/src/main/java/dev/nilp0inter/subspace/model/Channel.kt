package dev.nilp0inter.subspace.model

sealed interface Channel {
    val id: String
    val typeId: String
    val name: String
    val position: Int
    val isReady: Boolean
}

data class JournalChannel(
    override val id: String = ID,
    override val name: String = NAME,
    override val position: Int = 0,
    val baseDirectory: String? = null,
    val saveVoice: Boolean = true,
    val saveText: Boolean = true,
) : Channel {
    override val typeId: String = TYPE_ID
    override val isReady: Boolean
        get() = baseDirectory != null && (saveVoice || saveText)

    init {
        require(saveVoice || saveText) { "Journal must save voice, text, or both" }
    }

    companion object {
        const val ID = "captains-log"
        const val TYPE_ID = "journal"
        const val NAME = "Journal"
    }
}

enum class DebugMode {
    ECHO, STT, TTS, STT_TTS
}

data class DebugChannel(
    override val id: String = ID,
    override val name: String = NAME,
    override val position: Int = 1,
    val mode: DebugMode = DebugMode.ECHO,
) : Channel {
    override val typeId: String = TYPE_ID
    override val isReady: Boolean = true

    companion object {
        const val ID = "debug-channel"
        const val TYPE_ID = "debug"
        const val NAME = "Debug Channel"
    }
}

data class UnknownChannel(
    override val id: String,
    override val typeId: String,
    override val name: String,
    override val position: Int,
) : Channel {
    override val isReady: Boolean = false
}

data class ChannelType(
    val id: String,
    val displayName: String,
)

object ChannelTypes {
    val Journal = ChannelType(JournalChannel.TYPE_ID, "Journal")
    val Debug = ChannelType(DebugChannel.TYPE_ID, "Debug Channel")
    val builtIns: List<ChannelType> = listOf(Journal, Debug)

    fun contains(typeId: String): Boolean = builtIns.any { it.id == typeId }
}

fun normalizeChannelPositions(channels: List<Channel>): List<Channel> =
    channels.sortedWith(compareBy<Channel> { it.position }.thenBy { it.id })
        .mapIndexed { index, channel -> channel.withPosition(index) }

fun Channel.withPosition(position: Int): Channel = when (this) {
    is JournalChannel -> copy(position = position)
    is DebugChannel -> copy(position = position)
    is UnknownChannel -> copy(position = position)
}

fun Channel.withName(name: String): Channel = when (this) {
    is JournalChannel -> copy(name = name)
    is DebugChannel -> copy(name = name)
    is UnknownChannel -> copy(name = name)
}

fun Channel.debugModeOrNull(): DebugMode? = (this as? DebugChannel)?.mode
