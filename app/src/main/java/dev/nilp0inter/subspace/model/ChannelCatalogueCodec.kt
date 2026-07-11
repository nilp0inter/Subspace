package dev.nilp0inter.subspace.model

import io.sleepwalker.core.keymap.HostProfile
import org.json.JSONArray
import org.json.JSONObject

object ChannelCatalogueCodec {
    private const val DOCUMENT_VERSION = 1
    
    fun toJson(snapshot: ChannelCatalogueSnapshot): String {
        val root = JSONObject()
        root.put("version", DOCUMENT_VERSION)
        root.put("activeChannelId", snapshot.activeChannelId)
        
        val defsArray = JSONArray()
        for (def in snapshot.definitions) {
            val defObj = JSONObject()
            defObj.put("id", def.id)
            defObj.put("name", def.name)
            defObj.put("kind", def.kind.name)
            defObj.put("enabled", def.enabled)
            defObj.put("configSchemaVersion", def.configSchemaVersion)
            
            val configObj = JSONObject()
            when (def.config) {
                is JournalConfig -> {
                    configObj.put("baseDirectory", def.config.baseDirectory ?: JSONObject.NULL)
                    configObj.put("saveVoice", def.config.saveVoice)
                    configObj.put("saveText", def.config.saveText)
                }
                is DebugConfig -> {
                    configObj.put("mode", def.config.mode.name)
                }
                is KeyboardConfig -> {
                    configObj.put("hostProfile", def.config.hostProfile.key)
                }
                is TestFourthConfig -> {
                    configObj.put("data", def.config.data)
                }
            }
            defObj.put("config", configObj)
            defsArray.put(defObj)
        }
        root.put("definitions", defsArray)
        return root.toString(2)
    }

    fun fromJson(jsonStr: String): ChannelCatalogueSnapshot {
        val root = JSONObject(jsonStr)
        val version = root.getInt("version")
        require(version == DOCUMENT_VERSION) { "Unsupported document version: $version" }
        
        val activeChannelId = root.getString("activeChannelId")
        val defsArray = root.getJSONArray("definitions")
        val definitions = mutableListOf<ChannelDefinition>()
        
        for (i in 0 until defsArray.length()) {
            val defObj = defsArray.getJSONObject(i)
            val id = defObj.getString("id")
            val name = defObj.getString("name")
            val kindStr = defObj.getString("kind")
            val kind = ChannelKind.valueOf(kindStr)
            val enabled = defObj.getBoolean("enabled")
            val configSchemaVersion = defObj.getInt("configSchemaVersion")
            
            val configObj = defObj.getJSONObject("config")
            val config = when (kind) {
                ChannelKind.JOURNAL -> {
                    val baseDir = if (configObj.isNull("baseDirectory")) null else configObj.getString("baseDirectory")
                    val saveVoice = configObj.getBoolean("saveVoice")
                    val saveText = configObj.getBoolean("saveText")
                    JournalConfig(baseDir, saveVoice, saveText)
                }
                ChannelKind.DEBUG -> {
                    val modeStr = configObj.getString("mode")
                    val mode = DebugMode.valueOf(modeStr)
                    DebugConfig(mode)
                }
                ChannelKind.KEYBOARD -> {
                    val profileKey = configObj.getString("hostProfile")
                    val profile = parseHostProfileKey(profileKey)
                    KeyboardConfig(profile)
                }
                ChannelKind.TEST_FOURTH -> {
                    val data = configObj.getString("data")
                    TestFourthConfig(data)
                }
            }
            definitions.add(ChannelDefinition(id, name, kind, enabled, configSchemaVersion, config))
        }
        
        return ChannelCatalogueSnapshot(definitions, activeChannelId)
    }

    private fun parseHostProfileKey(key: String): HostProfile {
        val parts = key.split(":")
        if (parts.size < 2 || parts.any { it.isBlank() }) return HostProfile.LINUX_US
        return HostProfile(
            hostOs = parts[0],
            layout = parts[1],
            variant = if (parts.size >= 3) parts[2] else null,
        )
    }
}
