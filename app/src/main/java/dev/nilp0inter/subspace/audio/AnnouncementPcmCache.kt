package dev.nilp0inter.subspace.audio

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private val unsignedByteArrayComparator = Comparator<ByteArray> { a, b ->
    val minLen = minOf(a.size, b.size)
    for (i in 0 until minLen) {
        val b1 = a[i].toInt() and 0xFF
        val b2 = b[i].toInt() and 0xFF
        if (b1 != b2) return@Comparator b1.compareTo(b2)
    }
    a.size.compareTo(b.size)
}

private fun String.decodeHex(): ByteArray {
    check(length == 64) { "Invalid SHA-256 hex length" }
    val result = ByteArray(32)
    for (i in 0 until 32) {
        val firstDigit = Character.digit(this[i * 2], 16)
        val secondDigit = Character.digit(this[i * 2 + 1], 16)
        check(firstDigit != -1 && secondDigit != -1) { "Invalid hex character" }
        result[i] = ((firstDigit shl 4) or secondDigit).toByte()
    }
    return result
}

data class AnnouncementCacheIdentity(
    val packageLastUpdateTime: Long,
    val supertonicManifestSha256: String,
    val voiceStyleSha256ByFileName: Map<String, String>,
) {
    companion object {
        val DISABLED = AnnouncementCacheIdentity(0L, "", emptyMap())

        fun build(
            packageLastUpdateTime: Long,
            supertonicModelHash: ModelSetHash
        ): AnnouncementCacheIdentity {
            if (packageLastUpdateTime <= 0L) {
                return DISABLED
            }

            try {
                val version = supertonicModelHash.version
                if (version.isEmpty()) return DISABLED

                val voiceStyleSha256ByFileName = HashMap<String, String>()
                val seenPaths = HashSet<String>()

                for (fileHash in supertonicModelHash.files) {
                    val path = fileHash.path
                    if (!seenPaths.add(path)) {
                        return DISABLED
                    }

                    val hash = fileHash.sha256.lowercase()
                    if (hash.length != 64 || hash.any { Character.digit(it, 16) == -1 }) {
                        return DISABLED
                    }

                    if (path.startsWith("voice_styles/")) {
                        val basename = path.substringAfterLast('/')
                        if (voiceStyleSha256ByFileName.containsKey(basename)) {
                            return DISABLED
                        }
                        voiceStyleSha256ByFileName[basename] = hash
                    }
                }

                val sortedFiles = supertonicModelHash.files.sortedWith(
                    compareBy(unsignedByteArrayComparator) { it.path.toByteArray(Charsets.UTF_8) }
                )

                val md = MessageDigest.getInstance("SHA-256")

                val versionBytes = version.toByteArray(Charsets.UTF_8)
                val buf4 = ByteBuffer.allocate(4)
                buf4.putInt(versionBytes.size)
                md.update(buf4.array())
                md.update(versionBytes)

                buf4.clear()
                buf4.putInt(sortedFiles.size)
                md.update(buf4.array())

                for (file in sortedFiles) {
                    val pathBytes = file.path.toByteArray(Charsets.UTF_8)
                    buf4.clear()
                    buf4.putInt(pathBytes.size)
                    md.update(buf4.array())
                    md.update(pathBytes)

                    val hashBytes = file.sha256.lowercase().decodeHex()
                    md.update(hashBytes)
                }

                val supertonicManifestSha256 = md.digest().joinToString("") { "%02x".format(it) }

                return AnnouncementCacheIdentity(
                    packageLastUpdateTime = packageLastUpdateTime,
                    supertonicManifestSha256 = supertonicManifestSha256,
                    voiceStyleSha256ByFileName = voiceStyleSha256ByFileName
                )
            } catch (e: Exception) {
                return DISABLED
            }
        }
    }
}

data class AnnouncementRenderSettings(
    val voiceStylePath: String,
    val lang: String,
    val totalSteps: Int,
    val speed: Float,
    val scoRate: Int,
)

sealed interface AnnouncementCacheCommitResult {
    data object Unchanged : AnnouncementCacheCommitResult
    data object Written : AnnouncementCacheCommitResult
    data object Skipped : AnnouncementCacheCommitResult
    data class Failed(val reason: String) : AnnouncementCacheCommitResult
}

class AnnouncementPcmCache(
    private val rootDirectory: File,
    private val identity: AnnouncementCacheIdentity,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val entriesDir = File(rootDirectory, "entries")
    private val manifestFile = File(rootDirectory, "manifest.json")

    suspend fun load(
        vocabulary: Map<String, String>,
        settings: AnnouncementRenderSettings,
    ): Map<String, RecordedPcm> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                if (isCacheDisabled(if (vocabulary.isEmpty()) null else settings)) {
                    return@withContext emptyMap()
                }

                cleanupTempFiles()

                if (!entriesDir.exists()) {
                    entriesDir.mkdirs()
                }

                val manifest = readAndValidateManifest()
                if (manifest == null) {
                    return@withContext emptyMap()
                }

                cleanupUnreferencedFiles(manifest)

                if (vocabulary.isEmpty()) {
                    return@withContext emptyMap()
                }

                val fingerprints = try {
                    vocabulary.mapValues { (_, text) ->
                        computePhraseFingerprint(text, settings, identity)
                    }
                } catch (e: Exception) {
                    return@withContext emptyMap()
                }

                val loadedRecordings = HashMap<String, RecordedPcm>()
                val cacheHitsByFingerprint = HashMap<String, RecordedPcm>()

                for ((key, fingerprint) in fingerprints) {
                    val fileRecord = manifest.files.find { it.fingerprint == fingerprint }
                    if (fileRecord == null) {
                        continue
                    }

                    val cached = cacheHitsByFingerprint[fingerprint]
                    if (cached != null) {
                        loadedRecordings[key] = cached
                        continue
                    }

                    val pcm = loadAndValidateEntry(fileRecord)
                    if (pcm != null) {
                        cacheHitsByFingerprint[fingerprint] = pcm
                        loadedRecordings[key] = pcm
                    } else {
                        val entryFile = File(entriesDir, fileRecord.file)
                        runCatching { entryFile.delete() }
                    }
                }

                loadedRecordings
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    suspend fun commit(
        vocabulary: Map<String, String>,
        settings: AnnouncementRenderSettings,
        recordingsByKey: Map<String, RecordedPcm>,
    ): AnnouncementCacheCommitResult = mutex.withLock {
        withContext(ioDispatcher) {
            val isCurrentCacheDisabled = isCacheDisabled(if (vocabulary.isEmpty()) null else settings)
            if (isCurrentCacheDisabled) {
                return@withContext AnnouncementCacheCommitResult.Skipped
            }

            for (key in vocabulary.keys) {
                val recording = recordingsByKey[key]
                if (recording == null || recording.samples.isEmpty()) {
                    return@withContext AnnouncementCacheCommitResult.Failed("Incomplete vocabulary recordings")
                }
            }

            val currentManifest = readAndValidateManifest()
            val newlyPromotedFiles = ArrayList<File>()

            try {
                if (vocabulary.isEmpty()) {
                    val emptyManifest = Manifest(
                        schemaVersion = 1,
                        logicalEntries = emptyList(),
                        files = emptyList()
                    )
                    return@withContext writeManifestAndReconcile(
                        emptyManifest,
                        currentManifest,
                        newlyPromotedFiles
                    )
                }

                val fingerprints = try {
                    vocabulary.mapValues { (_, text) ->
                        computePhraseFingerprint(text, settings, identity)
                    }
                } catch (e: Exception) {
                    return@withContext AnnouncementCacheCommitResult.Skipped
                }

                if (currentManifest != null) {
                    val currentLogicalMap = currentManifest.logicalEntries.associate { it.key to it.fingerprint }
                    if (currentLogicalMap == fingerprints) {
                        val allFilesValid = currentManifest.files.all { fileRecord ->
                            loadAndValidateEntry(fileRecord) != null
                        }
                        if (allFilesValid) {
                            return@withContext AnnouncementCacheCommitResult.Unchanged
                        }
                    }
                }

                val requiredFingerprints = fingerprints.values.toSet()
                val newFiles = ArrayList<FileRecord>()

                for (fingerprint in requiredFingerprints) {
                    coroutineContext.ensureActive()
                    val existingRecord = currentManifest?.files?.find { it.fingerprint == fingerprint }
                    val existingPcm = existingRecord?.let { loadAndValidateEntry(it) }

                    if (existingRecord != null && existingPcm != null) {
                        newFiles.add(existingRecord)
                    } else {
                        val key = fingerprints.entries.find { it.value == fingerprint }!!.key
                        val recording = recordingsByKey[key]!!

                        val fileRecord = writeAndValidateNewEntry(fingerprint, recording, settings.scoRate, newlyPromotedFiles)
                            ?: throw IllegalStateException("Failed to write and validate entry for $fingerprint")
                        newFiles.add(fileRecord)
                    }
                }

                val newLogicalEntries = fingerprints.map { (key, fingerprint) ->
                    LogicalEntry(key, fingerprint)
                }
                val newManifest = Manifest(
                    schemaVersion = 1,
                    logicalEntries = newLogicalEntries,
                    files = newFiles
                )

                writeManifestAndReconcile(newManifest, currentManifest, newlyPromotedFiles)
            } catch (e: CancellationException) {
                rollbackCleanup(currentManifest, newlyPromotedFiles)
                throw e
            } catch (e: Exception) {
                rollbackCleanup(currentManifest, newlyPromotedFiles)
                AnnouncementCacheCommitResult.Failed(e.message ?: "Unknown error")
            }
        }
    }

    private fun isCacheDisabled(settings: AnnouncementRenderSettings? = null): Boolean {
        if (identity.packageLastUpdateTime <= 0L) return true
        if (identity.supertonicManifestSha256.length != 64) return true
        if (settings != null) {
            val styleBasename = File(settings.voiceStylePath).name
            val styleHash = identity.voiceStyleSha256ByFileName[styleBasename]
            if (styleHash == null || styleHash.length != 64) return true
        }
        return false
    }

    private fun cleanupTempFiles() {
        rootDirectory.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                runCatching { file.delete() }
            }
        }
        entriesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                runCatching { file.delete() }
            }
        }
    }

    private fun readAndValidateManifest(): Manifest? {
        if (!manifestFile.exists()) return null
        return try {
            val text = manifestFile.readText()
            parseManifest(text)
        } catch (e: Exception) {
            runCatching { manifestFile.delete() }
            entriesDir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
            null
        }
    }

    private fun cleanupUnreferencedFiles(manifest: Manifest) {
        val referencedFiles = manifest.files.map { it.file }.toSet()
        entriesDir.listFiles()?.forEach { file ->
            if (!referencedFiles.contains(file.name) && !file.name.endsWith(".tmp")) {
                runCatching { file.delete() }
            }
        }
    }

    private fun loadAndValidateEntry(fileRecord: FileRecord): RecordedPcm? {
        val file = File(entriesDir, fileRecord.file)
        if (!file.exists()) return null

        val currentSha = try {
            sha256OfFile(file)
        } catch (e: Exception) {
            return null
        }
        if (currentSha != fileRecord.fileSha256) return null

        val wavInfo = WavPcmReader.read(file) ?: return null
        if (wavInfo.sampleRate != fileRecord.sampleRate) return null
        if (wavInfo.channelCount != fileRecord.channelCount) return null
        if (wavInfo.bitsPerSample != fileRecord.bitsPerSample) return null
        if (wavInfo.samples.size.toLong() != fileRecord.sampleCount) return null
        if (wavInfo.samples.isEmpty()) return null

        return RecordedPcm(wavInfo.samples, wavInfo.sampleRate)
    }

    private suspend fun writeAndValidateNewEntry(
        fingerprint: String,
        recording: RecordedPcm,
        scoRate: Int,
        newlyPromotedFiles: MutableList<File>
    ): FileRecord? {
        coroutineContext.ensureActive()
        val tmpFile = File(entriesDir, "$fingerprint.wav.tmp")
        val finalFile = File(entriesDir, "$fingerprint.wav")

        try {
            val writer = JournalWavWriter(tmpFile, scoRate)
            try {
                writer.writeChunk(recording.samples)
            } finally {
                writer.finalize()
            }

            coroutineContext.ensureActive()

            val wavInfo = WavPcmReader.read(tmpFile) ?: return null
            if (wavInfo.sampleRate != scoRate) return null
            if (wavInfo.channelCount != 1) return null
            if (wavInfo.bitsPerSample != 16) return null
            if (!wavInfo.samples.contentEquals(recording.samples)) return null
            if (wavInfo.samples.isEmpty()) return null

            val fileSha256 = sha256OfFile(tmpFile)

            coroutineContext.ensureActive()

            java.nio.file.Files.move(
                tmpFile.toPath(),
                finalFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )

            newlyPromotedFiles.add(finalFile)

            return FileRecord(
                fingerprint = fingerprint,
                file = "$fingerprint.wav",
                fileSha256 = fileSha256,
                sampleRate = scoRate,
                channelCount = 1,
                bitsPerSample = 16,
                sampleCount = recording.samples.size.toLong()
            )
        } finally {
            if (tmpFile.exists()) {
                runCatching { tmpFile.delete() }
            }
        }
    }

    private suspend fun writeManifestAndReconcile(
        newManifest: Manifest,
        currentManifest: Manifest?,
        newlyPromotedFiles: List<File>
    ): AnnouncementCacheCommitResult {
        val tmpManifestFile = File(rootDirectory, "manifest.json.tmp")
        try {
            val manifestJsonText = serializeManifest(newManifest)
            tmpManifestFile.writeText(manifestJsonText)

            coroutineContext.ensureActive()

            java.nio.file.Files.move(
                tmpManifestFile.toPath(),
                manifestFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )

            if (currentManifest != null) {
                val newReferencedFiles = newManifest.files.map { it.file }.toSet()
                currentManifest.files.forEach { oldFileRecord ->
                    if (!newReferencedFiles.contains(oldFileRecord.file)) {
                        val fileToDelete = File(entriesDir, oldFileRecord.file)
                        runCatching { fileToDelete.delete() }
                    }
                }
            }

            cleanupUnreferencedFiles(newManifest)

            return AnnouncementCacheCommitResult.Written
        } finally {
            if (tmpManifestFile.exists()) {
                runCatching { tmpManifestFile.delete() }
            }
        }
    }

    private fun rollbackCleanup(currentManifest: Manifest?, newlyPromotedFiles: List<File>) {
        cleanupTempFiles()
        val oldReferencedFiles = currentManifest?.files?.map { it.file }?.toSet() ?: emptySet()
        for (file in newlyPromotedFiles) {
            if (!oldReferencedFiles.contains(file.name)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun sha256OfFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computePhraseFingerprint(
        text: String,
        settings: AnnouncementRenderSettings,
        identity: AnnouncementCacheIdentity
    ): String {
        val md = MessageDigest.getInstance("SHA-256")

        val buf4 = ByteBuffer.allocate(4)
        buf4.putInt(1)
        md.update(buf4.array())

        val buf8 = ByteBuffer.allocate(8)
        buf8.putLong(identity.packageLastUpdateTime)
        md.update(buf8.array())

        val textBytes = text.toByteArray(Charsets.UTF_8)
        buf4.clear()
        buf4.putInt(textBytes.size)
        md.update(buf4.array())
        md.update(textBytes)

        val manifestBytes = identity.supertonicManifestSha256.lowercase().decodeHex()
        md.update(manifestBytes)

        val styleBasename = File(settings.voiceStylePath).name
        val styleHashHex = identity.voiceStyleSha256ByFileName[styleBasename]
            ?: throw IllegalArgumentException("Missing voice style hash for $styleBasename")
        val styleBytes = styleHashHex.lowercase().decodeHex()
        md.update(styleBytes)

        val langBytes = settings.lang.toByteArray(Charsets.UTF_8)
        buf4.clear()
        buf4.putInt(langBytes.size)
        md.update(buf4.array())
        md.update(langBytes)

        buf4.clear()
        buf4.putInt(settings.totalSteps)
        md.update(buf4.array())

        buf4.clear()
        buf4.putInt(java.lang.Float.floatToRawIntBits(settings.speed))
        md.update(buf4.array())

        buf4.clear()
        buf4.putInt(settings.scoRate)
        md.update(buf4.array())

        val encBytes = "pcm16le-wav".toByteArray(Charsets.UTF_8)
        buf4.clear()
        buf4.putInt(encBytes.size)
        md.update(buf4.array())
        md.update(encBytes)

        buf4.clear()
        buf4.putInt(1)
        md.update(buf4.array())

        buf4.clear()
        buf4.putInt(16)
        md.update(buf4.array())

        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private fun isValidFingerprint(s: String): Boolean {
            if (s.length != 64) return false
            return s.all { Character.digit(it, 16) != -1 && !it.isUpperCase() }
        }

        private fun escapeJson(s: String): String {
            val sb = StringBuilder()
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '/' -> sb.append("\\/")
                    '\b' -> sb.append("\\b")
                    '\u000c' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u%04x".format(ch.code))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            return sb.toString()
        }

        fun parseManifest(jsonText: String): Manifest {
            val root = JSONObject(jsonText)

            val rootKeys = root.keys().asSequence().toSet()
            val expectedRootKeys = setOf("schemaVersion", "logicalEntries", "files")
            if (rootKeys != expectedRootKeys) {
                throw IllegalArgumentException("Manifest root keys mismatch: $rootKeys")
            }

            val schemaVersionObj = root.get("schemaVersion")
            if (schemaVersionObj !is Int || schemaVersionObj != 1) {
                throw IllegalArgumentException("Invalid schemaVersion: $schemaVersionObj")
            }

            val logicalEntriesArray = root.getJSONArray("logicalEntries")
            val logicalEntries = ArrayList<LogicalEntry>()
            val seenKeys = HashSet<String>()

            for (i in 0 until logicalEntriesArray.length()) {
                val obj = logicalEntriesArray.getJSONObject(i)
                val entryKeys = obj.keys().asSequence().toSet()
                val expectedEntryKeys = setOf("key", "fingerprint")
                if (entryKeys != expectedEntryKeys) {
                    throw IllegalArgumentException("Logical entry keys mismatch: $entryKeys")
                }
                val key = obj.getString("key")
                val fingerprint = obj.getString("fingerprint")

                if (!seenKeys.add(key)) {
                    throw IllegalArgumentException("Duplicate logical entry key: $key")
                }
                if (!isValidFingerprint(fingerprint)) {
                    throw IllegalArgumentException("Invalid fingerprint format: $fingerprint")
                }

                logicalEntries.add(LogicalEntry(key, fingerprint))
            }

            val filesArray = root.getJSONArray("files")
            val files = ArrayList<FileRecord>()
            val seenFileFingerprints = HashSet<String>()

            for (i in 0 until filesArray.length()) {
                val obj = filesArray.getJSONObject(i)
                val fileKeys = obj.keys().asSequence().toSet()
                val expectedFileKeys = setOf(
                    "fingerprint", "file", "fileSha256", "sampleRate",
                    "channelCount", "bitsPerSample", "sampleCount"
                )
                if (fileKeys != expectedFileKeys) {
                    throw IllegalArgumentException("File record keys mismatch: $fileKeys")
                }

                val fingerprint = obj.getString("fingerprint")
                val file = obj.getString("file")
                val fileSha256 = obj.getString("fileSha256")

                val sampleRate = obj.get("sampleRate")
                val channelCount = obj.get("channelCount")
                val bitsPerSample = obj.get("bitsPerSample")
                val sampleCountObj = obj.get("sampleCount")

                if (sampleRate !is Int || sampleRate != 16000) {
                    throw IllegalArgumentException("Invalid sampleRate: $sampleRate")
                }
                if (channelCount !is Int || channelCount != 1) {
                    throw IllegalArgumentException("Invalid channelCount: $channelCount")
                }
                if (bitsPerSample !is Int || bitsPerSample != 16) {
                    throw IllegalArgumentException("Invalid bitsPerSample: $bitsPerSample")
                }

                val sampleCount = when (sampleCountObj) {
                    is Int -> sampleCountObj.toLong()
                    is Long -> sampleCountObj
                    else -> throw IllegalArgumentException("Invalid sampleCount type")
                }
                if (sampleCount <= 0) {
                    throw IllegalArgumentException("Sample count must be positive")
                }

                if (!isValidFingerprint(fingerprint)) {
                    throw IllegalArgumentException("Invalid file fingerprint: $fingerprint")
                }
                if (!isValidFingerprint(fileSha256)) {
                    throw IllegalArgumentException("Invalid fileSha256: $fileSha256")
                }
                if (file != "$fingerprint.wav") {
                    throw IllegalArgumentException("Invalid file name: $file, expected $fingerprint.wav")
                }
                if (!seenFileFingerprints.add(fingerprint)) {
                    throw IllegalArgumentException("Duplicate file record fingerprint: $fingerprint")
                }

                files.add(FileRecord(
                    fingerprint = fingerprint,
                    file = file,
                    fileSha256 = fileSha256,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    bitsPerSample = bitsPerSample,
                    sampleCount = sampleCount
                ))
            }

            val logicalFingerprints = logicalEntries.map { it.fingerprint }.toSet()
            if (logicalFingerprints != seenFileFingerprints) {
                throw IllegalArgumentException("Referential integrity failure")
            }

            return Manifest(schemaVersionObj, logicalEntries, files)
        }

        private fun serializeManifest(manifest: Manifest): String {
            val sortedLogicalEntries = manifest.logicalEntries.sortedWith(
                compareBy(unsignedByteArrayComparator) { it.key.toByteArray(Charsets.UTF_8) }
            )

            val sortedFiles = manifest.files.sortedBy { it.fingerprint }

            val sb = java.lang.StringBuilder()
            sb.append("{\n")
            sb.append("  \"schemaVersion\": 1,\n")

            sb.append("  \"logicalEntries\": [\n")
            for (i in sortedLogicalEntries.indices) {
                val entry = sortedLogicalEntries[i]
                val escapedKey = escapeJson(entry.key)
                sb.append("    {\"key\": \"$escapedKey\", \"fingerprint\": \"${entry.fingerprint}\"}")
                if (i < sortedLogicalEntries.size - 1) {
                    sb.append(",\n")
                } else {
                    sb.append("\n")
                }
            }
            sb.append("  ],\n")

            sb.append("  \"files\": [\n")
            for (i in sortedFiles.indices) {
                val file = sortedFiles[i]
                sb.append("    {\n")
                sb.append("      \"fingerprint\": \"${file.fingerprint}\",\n")
                sb.append("      \"file\": \"${file.file}\",\n")
                sb.append("      \"fileSha256\": \"${file.fileSha256}\",\n")
                sb.append("      \"sampleRate\": ${file.sampleRate},\n")
                sb.append("      \"channelCount\": ${file.channelCount},\n")
                sb.append("      \"bitsPerSample\": ${file.bitsPerSample},\n")
                sb.append("      \"sampleCount\": ${file.sampleCount}\n")
                sb.append("    }")
                if (i < sortedFiles.size - 1) {
                    sb.append(",\n")
                } else {
                    sb.append("\n")
                }
            }
            sb.append("  ]\n")
            sb.append("}")
            return sb.toString()
        }
    }
}

data class LogicalEntry(val key: String, val fingerprint: String)
data class FileRecord(
    val fingerprint: String,
    val file: String,
    val fileSha256: String,
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val sampleCount: Long
)

data class Manifest(
    val schemaVersion: Int,
    val logicalEntries: List<LogicalEntry>,
    val files: List<FileRecord>
)
