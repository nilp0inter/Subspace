package dev.nilp0inter.subspace.model

import org.json.JSONArray
import org.json.JSONObject

data class DecodedChannelCatalogue(
    val snapshot: ChannelCatalogueSnapshot,
    val sourceDocumentVersion: Int,
)

sealed interface ChannelCatalogueDecodeResult {
    data class Success(val document: DecodedChannelCatalogue) : ChannelCatalogueDecodeResult
    data class Failure(val error: ChannelCatalogueDecodeError) : ChannelCatalogueDecodeResult
}

sealed interface ChannelCatalogueDecodeError {
    val message: String

    data class UnsupportedDocumentVersion(val version: Int) : ChannelCatalogueDecodeError {
        override val message = "Unsupported catalogue document version: $version"
    }

    data class UnsupportedLegacyKind(val kind: String) : ChannelCatalogueDecodeError {
        override val message = "Unsupported legacy channel kind: $kind"
    }

    data class MalformedDocument(override val message: String) : ChannelCatalogueDecodeError

    data class InvalidCatalogue(val error: ChannelCatalogueError) : ChannelCatalogueDecodeError {
        override val message = error.message
    }
}

object ChannelCatalogueCodec {
    const val CURRENT_DOCUMENT_VERSION = 2
    private const val LEGACY_DOCUMENT_VERSION = 1

    /** Encodes only document v2. Legacy v1 is read once and never written again. */
    fun toJson(snapshot: ChannelCatalogueSnapshot): String {
        require(ChannelCatalogueValidator.validate(snapshot) is ChannelCatalogueValidationResult.Valid)
        return JSONObject().apply {
            put("version", CURRENT_DOCUMENT_VERSION)
            put("activeChannelId", snapshot.activeChannelId)
            put("definitions", JSONArray().also { definitions ->
                snapshot.definitions.forEach { definition ->
                    definitions.put(JSONObject().apply {
                        put("id", definition.id)
                        put("name", definition.name)
                        put("implementationId", definition.implementationId.value)
                        put("enabled", definition.enabled)
                        put("configSchemaVersion", definition.configSchemaVersion)
                        put("config", definition.configPayload.toJsonObject())
                    })
                }
            })
        }.toString(2)
    }

    fun decode(json: String): ChannelCatalogueDecodeResult = try {
        val root = JSONObject(json)
        when (val version = root.getInt("version")) {
            CURRENT_DOCUMENT_VERSION -> decodeV2(root)
            LEGACY_DOCUMENT_VERSION -> decodeV1(root)
            else -> ChannelCatalogueDecodeResult.Failure(
                ChannelCatalogueDecodeError.UnsupportedDocumentVersion(version),
            )
        }
    } catch (error: LegacyKindException) {
        ChannelCatalogueDecodeResult.Failure(
            ChannelCatalogueDecodeError.UnsupportedLegacyKind(error.kind),
        )
    } catch (error: Exception) {
        ChannelCatalogueDecodeResult.Failure(
            ChannelCatalogueDecodeError.MalformedDocument(error.message ?: "Malformed catalogue document"),
        )
    }

    private fun decodeV2(root: JSONObject): ChannelCatalogueDecodeResult {
        val definitions = decodeDefinitions(root) { definition ->
            ChannelDefinition(
                id = definition.getString("id"),
                name = definition.getString("name"),
                implementationId = ChannelImplementationId(definition.getString("implementationId")),
                enabled = definition.getBoolean("enabled"),
                configSchemaVersion = definition.getInt("configSchemaVersion"),
                configPayload = OpaqueJsonObject.fromJsonObject(definition.getJSONObject("config")),
            )
        } ?: return ChannelCatalogueDecodeResult.Failure(
            ChannelCatalogueDecodeError.MalformedDocument("Malformed v2 definition"),
        )
        return decoded(root.getString("activeChannelId"), definitions, CURRENT_DOCUMENT_VERSION)
    }

    /** Converts legacy discriminators into stable built-in implementation IDs without decoding config. */
    private fun decodeV1(root: JSONObject): ChannelCatalogueDecodeResult {
        val definitions = decodeDefinitions(root) { definition ->
            val implementationId = when (val kind = definition.getString("kind")) {
                "JOURNAL" -> BuiltInChannelImplementationIds.JOURNAL
                "DEBUG" -> ChannelImplementationId("builtin:debug")
                "KEYBOARD" -> BuiltInChannelImplementationIds.KEYBOARD
                else -> throw LegacyKindException(kind)
            }
            ChannelDefinition(
                id = definition.getString("id"),
                name = definition.getString("name"),
                implementationId = implementationId,
                enabled = definition.getBoolean("enabled"),
                configSchemaVersion = definition.getInt("configSchemaVersion"),
                configPayload = OpaqueJsonObject.fromJsonObject(definition.getJSONObject("config")),
            )
        } ?: return ChannelCatalogueDecodeResult.Failure(
            ChannelCatalogueDecodeError.MalformedDocument("Malformed v1 definition"),
        )
        return decoded(root.getString("activeChannelId"), definitions, LEGACY_DOCUMENT_VERSION)
    }

    private fun decodeDefinitions(
        root: JSONObject,
        decode: (JSONObject) -> ChannelDefinition,
    ): List<ChannelDefinition>? = try {
        root.getJSONArray("definitions").let { array ->
            List(array.length()) { index -> decode(array.getJSONObject(index)) }
        }
    } catch (error: LegacyKindException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun decoded(
        activeChannelId: String,
        definitions: List<ChannelDefinition>,
        sourceVersion: Int,
    ): ChannelCatalogueDecodeResult {
        val snapshot = ChannelCatalogueSnapshot(definitions, activeChannelId)
        return when (val validation = ChannelCatalogueValidator.validate(snapshot)) {
            ChannelCatalogueValidationResult.Valid -> ChannelCatalogueDecodeResult.Success(
                DecodedChannelCatalogue(snapshot, sourceVersion),
            )
            is ChannelCatalogueValidationResult.Invalid -> ChannelCatalogueDecodeResult.Failure(
                ChannelCatalogueDecodeError.InvalidCatalogue(validation.error),
            )
        }
    }

    private class LegacyKindException(val kind: String) : IllegalArgumentException(kind)
}
