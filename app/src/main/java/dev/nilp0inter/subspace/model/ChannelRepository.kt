package dev.nilp0inter.subspace.model

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

data class ChannelConfiguration(
    val channels: List<Channel>,
    val activeChannelId: String,
)

class ChannelRepository(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun loadConfiguration(): ChannelConfiguration {
        val channels = loadChannels()
        val active = recoverActiveChannelId(channels, loadActiveChannelId())
        if (active != loadActiveChannelId()) saveActiveChannelId(active)
        return ChannelConfiguration(channels = channels, activeChannelId = active)
    }

    fun loadChannels(): List<Channel> {
        if (!prefs.contains(KEY_SCHEMA_VERSION)) {
            val seeded = normalizeChannelPositions(listOf(loadLegacyJournal(), loadLegacyDebugChannel()))
            saveChannels(seeded)
            return seeded
        }

        val count = prefs.getInt(KEY_CHANNEL_COUNT, 0)
        val loaded = (0 until count).mapNotNull(::loadChannelAt)
        return normalizeChannelPositions(loaded)
    }

    fun saveChannels(channels: List<Channel>) {
        val normalized = normalizeChannelPositions(channels)
        val editor = prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .putInt(KEY_CHANNEL_COUNT, normalized.size)
        normalized.forEachIndexed { index, channel -> editor.putChannel(index, channel) }
        editor.apply()
    }

    fun addDebugChannel(name: String = DebugChannel.NAME, position: Int = loadChannels().size): DebugChannel {
        val channels = loadChannels().toMutableList()
        val debug = DebugChannel(
            id = "debug-${UUID.randomUUID()}",
            name = name,
        )
        channels.add(position.coerceIn(0, channels.size), debug)
        val reordered = channels.mapIndexed { index, channel -> channel.withPosition(index) }
        saveChannels(reordered)
        return reordered.filterIsInstance<DebugChannel>().first { it.id == debug.id }
    }

    fun saveChannel(channel: Channel) {
        val channels = loadChannels().map { if (it.id == channel.id) channel else it }
        saveChannels(channels)
    }

    fun updateChannelName(id: String, name: String) {
        val trimmed = name.trim().ifBlank { return }
        saveChannels(loadChannels().map { if (it.id == id) it.withName(trimmed) else it })
    }

    fun moveChannel(id: String, position: Int) {
        val channels = loadChannels().toMutableList()
        val index = channels.indexOfFirst { it.id == id }
        if (index < 0) return
        val channel = channels.removeAt(index)
        channels.add(position.coerceIn(0, channels.size), channel)
        saveChannels(channels.mapIndexed { newIndex, item -> item.withPosition(newIndex) })
    }

    fun loadJournal(): JournalChannel = loadChannels().filterIsInstance<JournalChannel>().firstOrNull()
        ?: loadLegacyJournal()

    fun saveJournal(channel: JournalChannel) = saveChannel(channel)

    fun loadDebugChannel(): DebugChannel = loadChannels().filterIsInstance<DebugChannel>().firstOrNull()
        ?: loadLegacyDebugChannel()

    fun saveDebugChannel(channel: DebugChannel) = saveChannel(channel)

    fun loadActiveChannelId(): String = prefs.getString(KEY_ACTIVE_CHANNEL, JournalChannel.ID) ?: JournalChannel.ID

    fun saveActiveChannelId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_CHANNEL, id).apply()
    }

    private fun recoverActiveChannelId(channels: List<Channel>, saved: String): String = when {
        channels.any { it.id == saved } -> saved
        saved == JournalChannel.ID -> channels.firstOrNull { it is JournalChannel }?.id ?: channels.firstOrNull()?.id.orEmpty()
        saved == DebugChannel.ID -> channels.firstOrNull { it is DebugChannel }?.id ?: channels.firstOrNull()?.id.orEmpty()
        else -> channels.firstOrNull()?.id.orEmpty()
    }

    private fun loadChannelAt(index: Int): Channel? {
        val prefix = channelPrefix(index)
        val id = prefs.getString("${prefix}_id", null) ?: return null
        val typeId = prefs.getString("${prefix}_type", null) ?: return null
        val name = prefs.getString("${prefix}_name", null) ?: typeId
        val position = prefs.getInt("${prefix}_position", index)
        return when (typeId) {
            JournalChannel.TYPE_ID -> JournalChannel(
                id = id,
                name = name,
                position = position,
                baseDirectory = prefs.getString("${prefix}_base_directory", null),
                saveVoice = prefs.getBoolean("${prefix}_save_voice", true),
                saveText = prefs.getBoolean("${prefix}_save_text", true),
            )
            DebugChannel.TYPE_ID -> DebugChannel(
                id = id,
                name = name,
                position = position,
                mode = prefs.getString("${prefix}_debug_mode", DebugMode.ECHO.name)
                    ?.let { runCatching { DebugMode.valueOf(it) }.getOrDefault(DebugMode.ECHO) }
                    ?: DebugMode.ECHO,
            )
            else -> UnknownChannel(id = id, typeId = typeId, name = name, position = position)
        }
    }

    private fun SharedPreferences.Editor.putChannel(index: Int, channel: Channel): SharedPreferences.Editor {
        val prefix = channelPrefix(index)
        putString("${prefix}_id", channel.id)
        putString("${prefix}_type", channel.typeId)
        putString("${prefix}_name", channel.name)
        putInt("${prefix}_position", index)
        when (channel) {
            is JournalChannel -> {
                putString("${prefix}_base_directory", channel.baseDirectory)
                putBoolean("${prefix}_save_voice", channel.saveVoice)
                putBoolean("${prefix}_save_text", channel.saveText)
            }
            is DebugChannel -> putString("${prefix}_debug_mode", channel.mode.name)
            is UnknownChannel -> Unit
        }
        return this
    }

    private fun loadLegacyJournal(): JournalChannel = JournalChannel(
        baseDirectory = prefs.getString(KEY_BASE_DIRECTORY, null),
        saveVoice = prefs.getBoolean(KEY_SAVE_VOICE, true),
        saveText = prefs.getBoolean(KEY_SAVE_TEXT, true),
    )

    private fun loadLegacyDebugChannel(): DebugChannel = DebugChannel(
        mode = prefs.getString(KEY_DEBUG_MODE, DebugMode.ECHO.name)
            ?.let { runCatching { DebugMode.valueOf(it) }.getOrDefault(DebugMode.ECHO) }
            ?: DebugMode.ECHO,
    )

    private fun channelPrefix(index: Int): String = "channel_$index"

    companion object {
        private const val PREFS_NAME = "channels"
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "channel_schema_version"
        private const val KEY_CHANNEL_COUNT = "channel_count"
        private const val KEY_BASE_DIRECTORY = "journal_base_directory"
        private const val KEY_SAVE_VOICE = "journal_save_voice"
        private const val KEY_SAVE_TEXT = "journal_save_text"
        private const val KEY_DEBUG_MODE = "debug_channel_mode"
        private const val KEY_ACTIVE_CHANNEL = "active_channel_id"
    }
}
