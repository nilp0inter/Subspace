package dev.nilp0inter.subspace.model

sealed interface Channel {
    val id: String
    val name: String
    val enabled: Boolean
}

data class CaptainsLogChannel(
    override val id: String = ID,
    override val name: String = NAME,
    override val enabled: Boolean = false,
    val baseDirectory: String? = null,
    val saveVoice: Boolean = true,
    val saveText: Boolean = true,
) : Channel {
    init {
        require(saveVoice || saveText) { "Captain's Log must save voice, text, or both" }
    }

    companion object {
        const val ID = "captains-log"
        const val NAME = "Captain's Log"
    }
}
