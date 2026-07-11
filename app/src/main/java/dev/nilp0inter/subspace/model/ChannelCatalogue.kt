package dev.nilp0inter.subspace.model

import io.sleepwalker.core.keymap.HostProfile

enum class ChannelKind {
    JOURNAL, DEBUG, KEYBOARD, TEST_FOURTH
}

sealed interface ChannelConfig

data class JournalConfig(
    val baseDirectory: String?,
    val saveVoice: Boolean,
    val saveText: Boolean
) : ChannelConfig

data class DebugConfig(
    val mode: DebugMode
) : ChannelConfig

data class KeyboardConfig(
    val hostProfile: HostProfile
) : ChannelConfig

data class TestFourthConfig(
    val data: String
) : ChannelConfig

data class ChannelDefinition(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val enabled: Boolean,
    val configSchemaVersion: Int,
    val config: ChannelConfig
)

data class ChannelCatalogueSnapshot(
    val definitions: List<ChannelDefinition>,
    val activeChannelId: String
) {
    init {
        ChannelCatalogueValidator.validate(this)
    }

    fun selectChannel(id: String): ChannelCatalogueSnapshot {
        require(definitions.any { it.id == id }) { "Channel ID $id does not exist in catalogue" }
        return copy(activeChannelId = id)
    }

    fun addChannel(definition: ChannelDefinition): ChannelCatalogueSnapshot {
        require(definitions.none { it.id == definition.id }) { "Channel ID ${definition.id} already exists" }
        val nextDefinitions = definitions + definition
        return copy(definitions = nextDefinitions)
    }

    fun updateChannel(id: String, transform: (ChannelDefinition) -> ChannelDefinition): ChannelCatalogueSnapshot {
        val index = definitions.indexOfFirst { it.id == id }
        require(index != -1) { "Channel ID $id does not exist" }
        val oldDef = definitions[index]
        val newDef = transform(oldDef)
        require(newDef.id == id) { "Cannot change ID during update" }
        val nextDefinitions = definitions.toMutableList()
        nextDefinitions[index] = newDef
        return copy(definitions = nextDefinitions)
    }

    fun moveChannel(id: String, toIndex: Int): ChannelCatalogueSnapshot {
        val fromIndex = definitions.indexOfFirst { it.id == id }
        require(fromIndex != -1) { "Channel ID $id does not exist" }
        require(toIndex in definitions.indices) { "Target index $toIndex is out of bounds" }
        if (fromIndex == toIndex) return this
        val nextDefinitions = definitions.toMutableList()
        val item = nextDefinitions.removeAt(fromIndex)
        nextDefinitions.add(toIndex, item)
        return copy(definitions = nextDefinitions)
    }

    fun removeChannel(id: String): ChannelCatalogueSnapshot {
        require(definitions.size > 1) { "Cannot remove the final channel" }
        val index = definitions.indexOfFirst { it.id == id }
        require(index != -1) { "Channel ID $id does not exist" }
        
        val nextDefinitions = definitions.toMutableList()
        nextDefinitions.removeAt(index)
        
        val nextActiveId = if (activeChannelId == id) {
            if (index < definitions.size - 1) {
                definitions[index + 1].id
            } else {
                definitions[index - 1].id
            }
        } else {
            activeChannelId
        }
        
        return copy(definitions = nextDefinitions, activeChannelId = nextActiveId)
    }
}

object ChannelCatalogueValidator {
    fun validate(snapshot: ChannelCatalogueSnapshot) {
        require(snapshot.definitions.isNotEmpty()) { "Catalogue definitions must not be empty" }
        val ids = mutableSetOf<String>()
        for (def in snapshot.definitions) {
            require(def.id.isNotBlank()) { "Channel ID must not be blank" }
            require(ids.add(def.id)) { "Duplicate channel ID: ${def.id}" }
            when (def.kind) {
                ChannelKind.JOURNAL -> {
                    require(def.config is JournalConfig) { "Configuration must be JournalConfig for JOURNAL kind" }
                    val config = def.config as JournalConfig
                    require(config.saveVoice || config.saveText) { "Journal configuration must save voice, text, or both" }
                }
                ChannelKind.DEBUG -> {
                    require(def.config is DebugConfig) { "Configuration must be DebugConfig for DEBUG kind" }
                }
                ChannelKind.KEYBOARD -> {
                    require(def.config is KeyboardConfig) { "Configuration must be KeyboardConfig for KEYBOARD kind" }
                }
                ChannelKind.TEST_FOURTH -> {
                    require(def.config is TestFourthConfig) { "Configuration must be TestFourthConfig for TEST_FOURTH kind" }
                }
            }
        }
        require(snapshot.activeChannelId in ids) { "Active channel ID ${snapshot.activeChannelId} must exist in the catalogue" }
    }
}
