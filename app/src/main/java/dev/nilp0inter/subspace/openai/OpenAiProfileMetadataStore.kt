package dev.nilp0inter.subspace.openai

import dev.nilp0inter.subspace.model.OpenAiConnectionProfile
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiCredentialReference
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Immutable, non-secret document persisted independently from channel definitions. */
data class OpenAiProfileMetadataSnapshot(
    val profiles: List<OpenAiConnectionProfile> = emptyList(),
) {
    init {
        require(profiles.map { it.id }.distinct().size == profiles.size) {
            "OpenAI connection profile IDs must be unique"
        }
    }
}

sealed interface OpenAiProfileMetadataError {
    val message: String

    data class MalformedDocument(override val message: String) : OpenAiProfileMetadataError
    data class UnsupportedVersion(val version: Int) : OpenAiProfileMetadataError {
        override val message = "Unsupported OpenAI profile metadata version: $version"
    }

    data class InvalidProfile(override val message: String) : OpenAiProfileMetadataError
    data class Storage(val operation: String) : OpenAiProfileMetadataError {
        override val message = "Could not $operation OpenAI profile metadata"
    }
}

sealed interface OpenAiProfileMetadataLoadResult {
    data class Success(
        val snapshot: OpenAiProfileMetadataSnapshot,
        val sourceVersion: Int,
    ) : OpenAiProfileMetadataLoadResult

    data class Failure(val error: OpenAiProfileMetadataError) : OpenAiProfileMetadataLoadResult
}

sealed interface OpenAiProfileMetadataStoreResult {
    data object Success : OpenAiProfileMetadataStoreResult
    data class Failure(val error: OpenAiProfileMetadataError.Storage) : OpenAiProfileMetadataStoreResult
}

object OpenAiProfileMetadataCodec {
    const val CURRENT_VERSION = 2
    private const val V1 = 1

    fun encode(snapshot: OpenAiProfileMetadataSnapshot): String = JSONObject().apply {
        put("version", CURRENT_VERSION)
        put("profiles", JSONArray().apply {
            snapshot.profiles.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id.value)
                    put("displayName", profile.displayName)
                    put("baseUrl", profile.baseUrl)
                    put("credentialReference", profile.credentialReference.value)
                })
            }
        })
    }.toString()

    fun decode(document: String): OpenAiProfileMetadataLoadResult = try {
        val root = JSONObject(document)
        val version = root.optInt("version", V1)
        when (version) {
            V1 -> decodeV1(root)
            CURRENT_VERSION -> decodeV2(root)
            else -> OpenAiProfileMetadataLoadResult.Failure(
                OpenAiProfileMetadataError.UnsupportedVersion(version),
            )
        }
    } catch (error: Exception) {
        OpenAiProfileMetadataLoadResult.Failure(
            OpenAiProfileMetadataError.MalformedDocument("Invalid OpenAI profile metadata document"),
        )
    }

    private fun decodeV1(root: JSONObject): OpenAiProfileMetadataLoadResult = decodeProfiles(root, V1) { profile ->
        OpenAiConnectionProfile(
            id = OpenAiConnectionProfileId(profile.getString("id")),
            displayName = profile.getString("name"),
            baseUrl = normalizeBaseUrl(profile.getString("baseUrl")),
            credentialReference = OpenAiCredentialReference(profile.getString("credentialReference")),
        )
    }

    private fun decodeV2(root: JSONObject): OpenAiProfileMetadataLoadResult = decodeProfiles(root, CURRENT_VERSION) { profile ->
        OpenAiConnectionProfile(
            id = OpenAiConnectionProfileId(profile.getString("id")),
            displayName = profile.getString("displayName"),
            baseUrl = normalizeBaseUrl(profile.getString("baseUrl")),
            credentialReference = OpenAiCredentialReference(profile.getString("credentialReference")),
        )
    }

    private fun decodeProfiles(
        root: JSONObject,
        sourceVersion: Int,
        decode: (JSONObject) -> OpenAiConnectionProfile,
    ): OpenAiProfileMetadataLoadResult = try {
        val encodedProfiles = root.getJSONArray("profiles")
        val profiles = buildList {
            for (index in 0 until encodedProfiles.length()) add(decode(encodedProfiles.getJSONObject(index)))
        }
        OpenAiProfileMetadataLoadResult.Success(OpenAiProfileMetadataSnapshot(profiles), sourceVersion)
    } catch (error: Exception) {
        OpenAiProfileMetadataLoadResult.Failure(
            OpenAiProfileMetadataError.InvalidProfile("Invalid OpenAI connection profile metadata"),
        )
    }

    /** Canonical endpoint form accepted by the host OpenAI adapter. */
    fun normalizeBaseUrl(raw: String): String {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("OpenAI connection profile base URL is invalid")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "OpenAI connection profile base URL must use HTTPS"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "OpenAI connection profile base URL must identify only an HTTPS endpoint"
        }
        val normalizedPath = uri.rawPath.orEmpty().trimEnd('/')
        return URI("https", null, uri.host.lowercase(), uri.port, normalizedPath, null, null).toASCIIString()
    }
}

/** Atomic-file implementation; a failed save leaves the prior metadata document authoritative. */
class OpenAiProfileMetadataStore(private val file: File) {
    @Synchronized
    fun load(): OpenAiProfileMetadataLoadResult? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            OpenAiProfileMetadataCodec.decode(file.readText())
        } catch (_: IOException) {
            OpenAiProfileMetadataLoadResult.Failure(
                OpenAiProfileMetadataError.MalformedDocument("Unable to read OpenAI profile metadata"),
            )
        }
    }

    @Synchronized
    fun save(snapshot: OpenAiProfileMetadataSnapshot): OpenAiProfileMetadataStoreResult {
        val parent = file.absoluteFile.parentFile
        val temporary = File(parent, "${file.name}.tmp")
        return try {
            parent.mkdirs()
            temporary.writeText(OpenAiProfileMetadataCodec.encode(snapshot))
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            OpenAiProfileMetadataStoreResult.Success
        } catch (_: IOException) {
            if (temporary.exists()) temporary.delete()
            OpenAiProfileMetadataStoreResult.Failure(OpenAiProfileMetadataError.Storage("save"))
        }
    }
}
