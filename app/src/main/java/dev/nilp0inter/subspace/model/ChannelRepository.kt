package dev.nilp0inter.subspace.model

import io.sleepwalker.core.keymap.HostProfile

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChannelRepository(
    private val prefs: SharedPreferences,
    private val catalogueFile: File,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        File(context.filesDir, "channels_catalogue.json")
    )

    constructor(prefs: SharedPreferences) : this(
        prefs,
        File(System.getProperty("java.io.tmpdir"), "channels_catalogue_${System.nanoTime()}.json").apply { deleteOnExit() }
    )

    private val fileStore = ChannelCatalogueFileStore(catalogueFile)
    private val _catalogueState: MutableStateFlow<ChannelCatalogueSnapshot>
    private val mutationLock = Any()

    val catalogueState: StateFlow<ChannelCatalogueSnapshot>
        get() = _catalogueState.asStateFlow()

    init {
        var snapshot = fileStore.load()
        if (snapshot == null) {
            snapshot = migrateOrSeed()
            fileStore.save(snapshot)
        }
        _catalogueState = MutableStateFlow(snapshot)
    }

    private fun migrateOrSeed(): ChannelCatalogueSnapshot {
        val journalBaseDir = prefs.getString(KEY_BASE_DIRECTORY, null)
        val journalSaveVoice = prefs.getBoolean(KEY_SAVE_VOICE, true)
        val journalSaveText = prefs.getBoolean(KEY_SAVE_TEXT, true)
        val finalSaveVoice = if (!journalSaveVoice && !journalSaveText) true else journalSaveVoice
        val journalConfig = JournalConfig(journalBaseDir, finalSaveVoice, journalSaveText)
        val journalDef = ChannelDefinition(
            id = JournalChannel.ID,
            name = JournalChannel.NAME,
            kind = ChannelKind.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            config = journalConfig
        )

        val debugModeStr = prefs.getString(KEY_DEBUG_MODE, DebugMode.ECHO.name) ?: DebugMode.ECHO.name
        val debugMode = try {
            DebugMode.valueOf(debugModeStr)
        } catch (e: Exception) {
            DebugMode.ECHO
        }
        val debugDef = ChannelDefinition(
            id = DebugChannel.ID,
            name = DebugChannel.NAME,
            kind = ChannelKind.DEBUG,
            enabled = true,
            configSchemaVersion = 1,
            config = DebugConfig(debugMode)
        )

        val profileKey = prefs.getString(KEY_KEYBOARD_HOST_PROFILE, HostProfile.LINUX_US.key) ?: HostProfile.LINUX_US.key
        val hostProfile = parseHostProfileKey(profileKey)
        val keyboardDef = ChannelDefinition(
            id = KeyboardChannel.ID,
            name = KeyboardChannel.NAME,
            kind = ChannelKind.KEYBOARD,
            enabled = true,
            configSchemaVersion = 1,
            config = KeyboardConfig(hostProfile)
        )

        val activeId = prefs.getString(KEY_ACTIVE_CHANNEL, JournalChannel.ID) ?: JournalChannel.ID
        val definitions = listOf(journalDef, debugDef, keyboardDef)
        val finalActiveId = if (definitions.any { it.id == activeId }) activeId else JournalChannel.ID

        return ChannelCatalogueSnapshot(definitions, finalActiveId)
    }

    fun selectChannel(id: String) {
        synchronized(mutationLock) {
            val current = _catalogueState.value
            val next = current.selectChannel(id)
            fileStore.save(next)
            _catalogueState.value = next
        }
    }

    fun addChannel(definition: ChannelDefinition) {
        synchronized(mutationLock) {
            val current = _catalogueState.value
            val next = current.addChannel(definition)
            fileStore.save(next)
            _catalogueState.value = next
        }
    }

    fun updateChannel(id: String, transform: (ChannelDefinition) -> ChannelDefinition) {
        synchronized(mutationLock) {
            val current = _catalogueState.value
            val next = current.updateChannel(id, transform)
            fileStore.save(next)
            _catalogueState.value = next
        }
    }

    fun moveChannel(id: String, toIndex: Int) {
        synchronized(mutationLock) {
            val current = _catalogueState.value
            val next = current.moveChannel(id, toIndex)
            fileStore.save(next)
            _catalogueState.value = next
        }
    }

    fun removeChannel(id: String) {
        synchronized(mutationLock) {
            val current = _catalogueState.value
            val next = current.removeChannel(id)
            fileStore.save(next)
            _catalogueState.value = next
        }
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
    fun loadChannels(): List<Channel> {
        val snapshot = catalogueState.value
        return snapshot.definitions.map { def ->
            when (def.config) {
                is JournalConfig -> def.config.toLegacyChannel(def.name, def.id)
                is DebugConfig -> def.config.toLegacyChannel(def.name, def.id)
                is KeyboardConfig -> def.config.toLegacyChannel(def.name, def.id)
                else -> throw IllegalStateException("Unknown configuration type: ${def.config}")
            }
        }
    }
    fun loadActiveChannelId(): String =
        prefs.getString(KEY_ACTIVE_CHANNEL, JournalChannel.ID) ?: JournalChannel.ID


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
