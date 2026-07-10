package dev.nilp0inter.subspace.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AnnouncementPcmCacheTest {

    private lateinit var rootDirectory: File

    private val defaultModelSetHash = ModelSetHash(
        dirName = "supertonic-3",
        version = "1.0",
        repo = "subspace",
        files = listOf(
            FileHash("voice_styles/style1.bin", "a".repeat(64)),
            FileHash("voice_styles/style2.bin", "b".repeat(64)),
            FileHash("other.bin", "c".repeat(64))
        )
    )

    private val defaultSettings = AnnouncementRenderSettings(
        voiceStylePath = "voice_styles/style1.bin",
        lang = "en",
        totalSteps = 10,
        speed = 1.0f,
        scoRate = 16000
    )

    @Before
    fun setUp() {
        rootDirectory = kotlin.io.path.createTempDirectory("announcement-pcm-cache-test").toFile()
    }

    @After
    fun tearDown() {
        rootDirectory.deleteRecursively()
    }

    private fun createCache(identity: AnnouncementCacheIdentity = AnnouncementCacheIdentity.build(12345L, defaultModelSetHash)): AnnouncementPcmCache {
        File(rootDirectory, "entries").mkdirs()
        return AnnouncementPcmCache(rootDirectory, identity, Dispatchers.Default)
    }

    private fun modifyWavChannels(file: File, channels: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(22)
            writeInt16Le(raf, channels)
        }
    }

    private fun modifyWavSampleRate(file: File, rate: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(24)
            writeInt32Le(raf, rate)
        }
    }

    private fun modifyWavBitsPerSample(file: File, bits: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(34)
            writeInt16Le(raf, bits)
        }
    }

    private fun modifyWavDataSize(file: File, size: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(40)
            writeInt32Le(raf, size)
        }
    }

    private fun writeInt16Le(raf: RandomAccessFile, value: Int) {
        raf.writeByte(value and 0xFF)
        raf.writeByte((value shr 8) and 0xFF)
    }

    private fun writeInt32Le(raf: RandomAccessFile, value: Int) {
        raf.writeByte(value and 0xFF)
        raf.writeByte((value shr 8) and 0xFF)
        raf.writeByte((value shr 16) and 0xFF)
        raf.writeByte((value shr 24) and 0xFF)
    }

    private fun setupManifestAndEntries(manifestContent: String): File {
        val manifestFile = File(rootDirectory, "manifest.json")
        manifestFile.writeText(manifestContent)
        val entriesDir = File(rootDirectory, "entries")
        entriesDir.mkdirs()
        val dummyFile = File(entriesDir, "dummy_file.wav")
        dummyFile.writeText("dummy content")
        return dummyFile
    }

    private suspend fun commitSingleEntryAndGetFile(): Pair<AnnouncementPcmCache, File> {
        val cache = createCache()
        val vocabulary = mapOf("key1" to "Text 1")
        val recordings = mapOf("key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000))
        val commitResult = cache.commit(vocabulary, defaultSettings, recordings)
        assertEquals(AnnouncementCacheCommitResult.Written, commitResult)

        val manifestFile = File(rootDirectory, "manifest.json")
        val manifest = AnnouncementPcmCache.parseManifest(manifestFile.readText())
        val filename = manifest.files.first().file
        val file = File(rootDirectory, "entries/$filename")
        assertTrue(file.exists())
        return Pair(cache, file)
    }

    @Test
    fun testAnnouncementCacheIdentityBuild() {
        // 1. Normal construction
        val identity = AnnouncementCacheIdentity.build(12345L, defaultModelSetHash)
        assertNotEquals(AnnouncementCacheIdentity.DISABLED, identity)
        assertEquals(12345L, identity.packageLastUpdateTime)
        assertEquals(64, identity.supertonicManifestSha256.length)
        assertEquals(2, identity.voiceStyleSha256ByFileName.size)
        assertEquals("a".repeat(64), identity.voiceStyleSha256ByFileName["style1.bin"])
        assertEquals("b".repeat(64), identity.voiceStyleSha256ByFileName["style2.bin"])

        // 2. Duplicate paths
        val duplicatePathsModel = defaultModelSetHash.copy(
            files = listOf(
                FileHash("voice_styles/style1.bin", "a".repeat(64)),
                FileHash("voice_styles/style1.bin", "a".repeat(64))
            )
        )
        assertEquals(AnnouncementCacheIdentity.DISABLED, AnnouncementCacheIdentity.build(12345L, duplicatePathsModel))

        // 3. Duplicate basenames in voice_styles/
        val duplicateBasenamesModel = defaultModelSetHash.copy(
            files = listOf(
                FileHash("voice_styles/dir1/style.bin", "a".repeat(64)),
                FileHash("voice_styles/dir2/style.bin", "b".repeat(64))
            )
        )
        assertEquals(AnnouncementCacheIdentity.DISABLED, AnnouncementCacheIdentity.build(12345L, duplicateBasenamesModel))

        // 4. Malformed hashes
        val malformedHashModel1 = defaultModelSetHash.copy(
            files = listOf(FileHash("voice_styles/style1.bin", "too-short"))
        )
        assertEquals(AnnouncementCacheIdentity.DISABLED, AnnouncementCacheIdentity.build(12345L, malformedHashModel1))

        val malformedHashModel2 = defaultModelSetHash.copy(
            files = listOf(FileHash("voice_styles/style1.bin", "g".repeat(64)))
        )
        assertEquals(AnnouncementCacheIdentity.DISABLED, AnnouncementCacheIdentity.build(12345L, malformedHashModel2))

        // 5. Negative / zero packageLastUpdateTime
        assertEquals(AnnouncementCacheIdentity.DISABLED, AnnouncementCacheIdentity.build(0L, defaultModelSetHash))
        assertEquals(AnnouncementCacheIdentity.DISABLED, AnnouncementCacheIdentity.build(-100L, defaultModelSetHash))

        // 6. Empty version
        val emptyVersionModel = defaultModelSetHash.copy(version = "")
        assertEquals(AnnouncementCacheIdentity.DISABLED, AnnouncementCacheIdentity.build(12345L, emptyVersionModel))

        // 7. Determinism: different file orders should yield the same manifest SHA-256
        val order1Model = defaultModelSetHash.copy(
            files = listOf(
                FileHash("voice_styles/style1.bin", "a".repeat(64)),
                FileHash("voice_styles/style2.bin", "b".repeat(64))
            )
        )
        val order2Model = defaultModelSetHash.copy(
            files = listOf(
                FileHash("voice_styles/style2.bin", "b".repeat(64)),
                FileHash("voice_styles/style1.bin", "a".repeat(64))
            )
        )
        val id1 = AnnouncementCacheIdentity.build(12345L, order1Model)
        val id2 = AnnouncementCacheIdentity.build(12345L, order2Model)
        assertNotEquals(AnnouncementCacheIdentity.DISABLED, id1)
        assertEquals(id1.supertonicManifestSha256, id2.supertonicManifestSha256)
    }

    @Test
    fun testAllAndPartialHits() {
        runBlocking {
            val cache = createCache()
            val vocabulary = mapOf("key1" to "Text 1", "key2" to "Text 2")
            val recordings = mapOf(
                "key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000),
                "key2" to RecordedPcm(ShortArray(3200) { 2 }, 16000)
            )

            val commitResult = cache.commit(vocabulary, defaultSettings, recordings)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult)

            val manifestFile = File(rootDirectory, "manifest.json")
            assertTrue(manifestFile.exists())
            val manifest = AnnouncementPcmCache.parseManifest(manifestFile.readText())

            // 1. All hits: loading both should hydrate both
            val loaded = cache.load(vocabulary, defaultSettings)
            assertEquals(2, loaded.size)
            assertArrayEquals(recordings["key1"]!!.samples, loaded["key1"]!!.samples)
            assertArrayEquals(recordings["key2"]!!.samples, loaded["key2"]!!.samples)

            // 2. Partial hits: delete one file and verify it's a miss for that key
            val fileRecordToDelete = manifest.files.find { it.fingerprint == manifest.logicalEntries.first { it.key == "key2" }.fingerprint }!!
            val fileToDelete = File(rootDirectory, "entries/${fileRecordToDelete.file}")
            assertTrue(fileToDelete.exists())
            assertTrue(fileToDelete.delete())

            val partialLoaded = cache.load(vocabulary, defaultSettings)
            assertEquals(1, partialLoaded.size)
            assertTrue(partialLoaded.containsKey("key1"))
            assertFalse(partialLoaded.containsKey("key2"))
        }
    }

    @Test
    fun testIdenticalTextReuse() {
        runBlocking {
            val cache = createCache()
            val vocabulary = mapOf("key1" to "Same Text", "key2" to "Same Text")
            val recordings = mapOf(
                "key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000),
                "key2" to RecordedPcm(ShortArray(1600) { 1 }, 16000)
            )

            val commitResult = cache.commit(vocabulary, defaultSettings, recordings)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult)

            val entriesDir = File(rootDirectory, "entries")
            val wavFiles = entriesDir.listFiles { _, name -> name.endsWith(".wav") }
            assertEquals(1, wavFiles?.size ?: 0)

            val manifestFile = File(rootDirectory, "manifest.json")
            val manifest = AnnouncementPcmCache.parseManifest(manifestFile.readText())
            assertEquals(2, manifest.logicalEntries.size)
            assertEquals(1, manifest.files.size)
            assertEquals(manifest.logicalEntries[0].fingerprint, manifest.logicalEntries[1].fingerprint)
            assertEquals(manifest.logicalEntries[0].fingerprint, manifest.files[0].fingerprint)

            val loaded = cache.load(vocabulary, defaultSettings)
            assertEquals(2, loaded.size)
            assertArrayEquals(recordings["key1"]!!.samples, loaded["key1"]!!.samples)
            assertArrayEquals(recordings["key2"]!!.samples, loaded["key2"]!!.samples)
        }
    }

    @Test
    fun testSemanticInvalidators() {
        runBlocking {
            val initialIdentity = AnnouncementCacheIdentity.build(12345L, defaultModelSetHash)
            val cache = createCache(initialIdentity)
            val vocabulary = mapOf("key1" to "Test Text")
            val recordings = mapOf("key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000))

            val commitResult = cache.commit(vocabulary, defaultSettings, recordings)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult)

            val loadedBefore = cache.load(vocabulary, defaultSettings)
            assertEquals(1, loadedBefore.size)

            val identityWithNewTime = initialIdentity.copy(packageLastUpdateTime = 54321L)
            val cacheNewTime = createCache(identityWithNewTime)
            assertTrue(cacheNewTime.load(vocabulary, defaultSettings).isEmpty())

            val identityWithNewSha = initialIdentity.copy(supertonicManifestSha256 = "f".repeat(64))
            val cacheNewSha = createCache(identityWithNewSha)
            assertTrue(cacheNewSha.load(vocabulary, defaultSettings).isEmpty())

            val modelSetHashWithNewStyleSha = defaultModelSetHash.copy(
                files = listOf(
                    FileHash("voice_styles/style1.bin", "f".repeat(64)),
                    FileHash("other.bin", "c".repeat(64))
                )
            )
            val identityWithNewStyle = AnnouncementCacheIdentity.build(12345L, modelSetHashWithNewStyleSha)
            val cacheNewStyle = createCache(identityWithNewStyle)
            assertTrue(cacheNewStyle.load(vocabulary, defaultSettings).isEmpty())

            val settingsNewStyle = defaultSettings.copy(voiceStylePath = "voice_styles/style2.bin")
            assertTrue(cache.load(vocabulary, settingsNewStyle).isEmpty())

            val settingsNewLang = defaultSettings.copy(lang = "es")
            assertTrue(cache.load(vocabulary, settingsNewLang).isEmpty())

            val settingsNewSteps = defaultSettings.copy(totalSteps = 20)
            assertTrue(cache.load(vocabulary, settingsNewSteps).isEmpty())

            val settingsNewSpeed = defaultSettings.copy(speed = 1.5f)
            assertTrue(cache.load(vocabulary, settingsNewSpeed).isEmpty())

            val settingsNewSco = defaultSettings.copy(scoRate = 8000)
            assertTrue(cache.load(vocabulary, settingsNewSco).isEmpty())

            val disabledIdentityTime = initialIdentity.copy(packageLastUpdateTime = 0L)
            val cacheDisabledTime = createCache(disabledIdentityTime)
            assertTrue(cacheDisabledTime.load(vocabulary, defaultSettings).isEmpty())
            assertEquals(AnnouncementCacheCommitResult.Skipped, cacheDisabledTime.commit(vocabulary, defaultSettings, recordings))

            val disabledIdentitySha = initialIdentity.copy(supertonicManifestSha256 = "short")
            val cacheDisabledSha = createCache(disabledIdentitySha)
            assertTrue(cacheDisabledSha.load(vocabulary, defaultSettings).isEmpty())
            assertEquals(AnnouncementCacheCommitResult.Skipped, cacheDisabledSha.commit(vocabulary, defaultSettings, recordings))
        }
    }

    @Test
    fun testMissingOrInvalidStyleMetadata() {
        runBlocking {
            val identity = AnnouncementCacheIdentity.build(12345L, defaultModelSetHash)
            val cache = createCache(identity)
            val vocabulary = mapOf("key1" to "Text")
            val recordings = mapOf("key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000))

            val settingsMissingStyle = defaultSettings.copy(voiceStylePath = "voice_styles/style3.bin")
            
            val loaded = cache.load(vocabulary, settingsMissingStyle)
            assertTrue(loaded.isEmpty())

            val commitResult = cache.commit(vocabulary, settingsMissingStyle, recordings)
            assertEquals(AnnouncementCacheCommitResult.Skipped, commitResult)
        }
    }

    @Test
    fun testEmptyVocabularyBehavior() {
        runBlocking {
            val cache = createCache()
            val emptyVocabulary = emptyMap<String, String>()

            val loaded = cache.load(emptyVocabulary, defaultSettings)
            assertTrue(loaded.isEmpty())

            val commitResult = cache.commit(emptyVocabulary, defaultSettings, emptyMap())
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult)

            val manifestFile = File(rootDirectory, "manifest.json")
            assertTrue(manifestFile.exists())
            val manifest = AnnouncementPcmCache.parseManifest(manifestFile.readText())
            assertTrue(manifest.logicalEntries.isEmpty())
            assertTrue(manifest.files.isEmpty())

            val vocabulary = mapOf("key1" to "Text 1")
            val recordings = mapOf("key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000))
            val commitResult2 = cache.commit(vocabulary, defaultSettings, recordings)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult2)

            val entriesDir = File(rootDirectory, "entries")
            assertTrue(entriesDir.listFiles()?.any { it.name.endsWith(".wav") } == true)

            val commitResultEmpty = cache.commit(emptyVocabulary, defaultSettings, emptyMap())
            assertEquals(AnnouncementCacheCommitResult.Written, commitResultEmpty)

            val manifest2 = AnnouncementPcmCache.parseManifest(manifestFile.readText())
            assertTrue(manifest2.logicalEntries.isEmpty())
            assertTrue(manifest2.files.isEmpty())
            val filesLeft = entriesDir.listFiles()?.filter { it.name.endsWith(".wav") }
            assertTrue(filesLeft.isNullOrEmpty())
        }
    }

    @Test
    fun testManifestWrongSchemaVersion() {
        runBlocking {
            val content = """
                {
                  "schemaVersion": 2,
                  "logicalEntries": [],
                  "files": []
                }
            """.trimIndent()
            val dummy = setupManifestAndEntries(content)
            val cache = createCache()
            val loaded = cache.load(mapOf("key1" to "Text"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(File(rootDirectory, "manifest.json").exists())
            assertFalse(dummy.exists())
        }
    }

    @Test
    fun testManifestMalformedJson() {
        runBlocking {
            val content = """
                {
                  "schemaVersion": 1,
                  "logicalEntries": [
            """.trimIndent()
            val dummy = setupManifestAndEntries(content)
            val cache = createCache()
            val loaded = cache.load(mapOf("key1" to "Text"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(File(rootDirectory, "manifest.json").exists())
            assertFalse(dummy.exists())
        }
    }

    @Test
    fun testManifestDuplicateKeys() {
        runBlocking {
            val fp = "a".repeat(64)
            val content = """
                {
                  "schemaVersion": 1,
                  "logicalEntries": [
                    {"key": "key1", "fingerprint": "$fp"},
                    {"key": "key1", "fingerprint": "$fp"}
                  ],
                  "files": [
                    {
                      "fingerprint": "$fp",
                      "file": "$fp.wav",
                      "fileSha256": "$fp",
                      "sampleRate": 16000,
                      "channelCount": 1,
                      "bitsPerSample": 16,
                      "sampleCount": 1000
                    }
                  ]
                }
            """.trimIndent()
            val dummy = setupManifestAndEntries(content)
            val cache = createCache()
            val loaded = cache.load(mapOf("key1" to "Text"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(File(rootDirectory, "manifest.json").exists())
            assertFalse(dummy.exists())
        }
    }

    @Test
    fun testManifestDuplicateFileFingerprints() {
        runBlocking {
            val fp = "a".repeat(64)
            val content = """
                {
                  "schemaVersion": 1,
                  "logicalEntries": [
                    {"key": "key1", "fingerprint": "$fp"}
                  ],
                  "files": [
                    {
                      "fingerprint": "$fp",
                      "file": "$fp.wav",
                      "fileSha256": "$fp",
                      "sampleRate": 16000,
                      "channelCount": 1,
                      "bitsPerSample": 16,
                      "sampleCount": 1000
                    },
                    {
                      "fingerprint": "$fp",
                      "file": "$fp.wav",
                      "fileSha256": "$fp",
                      "sampleRate": 16000,
                      "channelCount": 1,
                      "bitsPerSample": 16,
                      "sampleCount": 1000
                    }
                  ]
                }
            """.trimIndent()
            val dummy = setupManifestAndEntries(content)
            val cache = createCache()
            val loaded = cache.load(mapOf("key1" to "Text"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(File(rootDirectory, "manifest.json").exists())
            assertFalse(dummy.exists())
        }
    }

    @Test
    fun testManifestReferentialIntegrityMismatch() {
        runBlocking {
            val fp1 = "a".repeat(64)
            val fp2 = "b".repeat(64)
            val content = """
                {
                  "schemaVersion": 1,
                  "logicalEntries": [
                    {"key": "key1", "fingerprint": "$fp1"}
                  ],
                  "files": [
                    {
                      "fingerprint": "$fp2",
                      "file": "$fp2.wav",
                      "fileSha256": "$fp2",
                      "sampleRate": 16000,
                      "channelCount": 1,
                      "bitsPerSample": 16,
                      "sampleCount": 1000
                    }
                  ]
                }
            """.trimIndent()
            val dummy = setupManifestAndEntries(content)
            val cache = createCache()
            val loaded = cache.load(mapOf("key1" to "Text"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(File(rootDirectory, "manifest.json").exists())
            assertFalse(dummy.exists())
        }
    }

    @Test
    fun testWavIntegrityMissingFile() {
        runBlocking {
            val (cache, file) = commitSingleEntryAndGetFile()
            assertTrue(file.delete())
            val loaded = cache.load(mapOf("key1" to "Text 1"), defaultSettings)
            assertTrue(loaded.isEmpty())
        }
    }

    @Test
    fun testWavIntegrityTruncated() {
        runBlocking {
            val (cache, file) = commitSingleEntryAndGetFile()
            file.writeText("truncated content")
            val loaded = cache.load(mapOf("key1" to "Text 1"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(file.exists())
        }
    }

    @Test
    fun testWavIntegrityWrongSampleRate() {
        runBlocking {
            val (cache, file) = commitSingleEntryAndGetFile()
            modifyWavSampleRate(file, 44100)
            val loaded = cache.load(mapOf("key1" to "Text 1"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(file.exists())
        }
    }

    @Test
    fun testWavIntegrityWrongChannels() {
        runBlocking {
            val (cache, file) = commitSingleEntryAndGetFile()
            modifyWavChannels(file, 2)
            val loaded = cache.load(mapOf("key1" to "Text 1"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(file.exists())
        }
    }

    @Test
    fun testWavIntegrityWrongBitsPerSample() {
        runBlocking {
            val (cache, file) = commitSingleEntryAndGetFile()
            modifyWavBitsPerSample(file, 24)
            val loaded = cache.load(mapOf("key1" to "Text 1"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(file.exists())
        }
    }

    @Test
    fun testWavIntegrityWrongSampleCount() {
        runBlocking {
            val (cache, file) = commitSingleEntryAndGetFile()
            modifyWavDataSize(file, 1000)
            val loaded = cache.load(mapOf("key1" to "Text 1"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(file.exists())
        }
    }

    @Test
    fun testWavIntegritySha256Mismatch() {
        runBlocking {
            val (cache, file) = commitSingleEntryAndGetFile()
            val writer = JournalWavWriter(file, 16000)
            writer.writeChunk(ShortArray(800) { 9 })
            writer.finalize()
            
            val loaded = cache.load(mapOf("key1" to "Text 1"), defaultSettings)
            assertTrue(loaded.isEmpty())
            assertFalse(file.exists())
        }
    }

    @Test
    fun testCommitUnchangedNoRewrite() {
        runBlocking {
            val cache = createCache()
            val vocabulary = mapOf("key1" to "Text 1")
            val recordings = mapOf("key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000))

            val commitResult1 = cache.commit(vocabulary, defaultSettings, recordings)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult1)

            val manifestFile = File(rootDirectory, "manifest.json")
            val mtime1 = manifestFile.lastModified()

            val commitResult2 = cache.commit(vocabulary, defaultSettings, recordings)
            assertEquals(AnnouncementCacheCommitResult.Unchanged, commitResult2)

            val mtime2 = manifestFile.lastModified()
            assertEquals(mtime1, mtime2)
        }
    }

    @Test
    fun testMarkSweepCleanup() {
        runBlocking {
            val cache = createCache()
            val vocabulary = mapOf("key1" to "Text 1")
            val recordings = mapOf("key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000))

            val commitResult = cache.commit(vocabulary, defaultSettings, recordings)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult)

            val entriesDir = File(rootDirectory, "entries")
            val tempRoot = File(rootDirectory, "manifest.json.tmp")
            tempRoot.writeText("temp manifest")
            val tempEntry = File(entriesDir, "some_entry.wav.tmp")
            tempEntry.writeText("temp entry")
            val unreferencedEntry = File(entriesDir, "unreferenced.wav")
            unreferencedEntry.writeText("unreferenced entry")

            val loaded = cache.load(vocabulary, defaultSettings)
            assertEquals(1, loaded.size)

            assertFalse(tempRoot.exists())
            assertFalse(tempEntry.exists())
            assertFalse(unreferencedEntry.exists())

            val tempRoot2 = File(rootDirectory, "manifest.json.tmp")
            tempRoot2.writeText("temp manifest")
            val tempEntry2 = File(entriesDir, "some_entry.wav.tmp")
            tempEntry2.writeText("temp entry")
            val unreferencedEntry2 = File(entriesDir, "unreferenced.wav")
            unreferencedEntry2.writeText("unreferenced entry")

            val newVocabulary = mapOf("key1" to "Text 1", "key2" to "Text 2")
            val newRecordings = mapOf(
                "key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000),
                "key2" to RecordedPcm(ShortArray(1600) { 2 }, 16000)
            )
            val commitResult3 = cache.commit(newVocabulary, defaultSettings, newRecordings)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult3)

            assertFalse(unreferencedEntry2.exists())
            assertFalse(tempRoot2.exists())
            assertTrue(tempEntry2.exists())

            cache.load(newVocabulary, defaultSettings)
            assertFalse(tempRoot2.exists())
            assertFalse(tempEntry2.exists())
        }
    }

    @Test
    fun testCommitRollbackOnFailure() {
        runBlocking {
            val cache = createCache()
            val vocabulary1 = mapOf("key1" to "Text 1")
            val recordings1 = mapOf("key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000))

            val commitResult1 = cache.commit(vocabulary1, defaultSettings, recordings1)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult1)

            val manifestFile = File(rootDirectory, "manifest.json")
            val oldManifestText = manifestFile.readText()
            val oldManifest = AnnouncementPcmCache.parseManifest(oldManifestText)
            val oldWavFilename = oldManifest.files.first().file
            val oldWavFile = File(rootDirectory, "entries/$oldWavFilename")
            assertTrue(oldWavFile.exists())

            assertTrue(rootDirectory.setWritable(false))

            val vocabulary2 = mapOf("key1" to "Text 1", "key2" to "Text 2")
            val recordings2 = mapOf(
                "key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000),
                "key2" to RecordedPcm(ShortArray(3200) { 2 }, 16000)
            )

            val commitResult2 = cache.commit(vocabulary2, defaultSettings, recordings2)
            assertTrue(rootDirectory.setWritable(true))
            assertTrue(commitResult2 is AnnouncementCacheCommitResult.Failed)

            val entriesDir = File(rootDirectory, "entries")
            val wavFiles = entriesDir.listFiles { _, name -> name.endsWith(".wav") }
            assertEquals(1, wavFiles?.size ?: 0)
            assertEquals(oldWavFilename, wavFiles?.first()?.name)
            assertTrue(oldWavFile.exists())

        }
    }

    private class CancellingDispatcher : CoroutineDispatcher() {
        var job: Job? = null
        var dispatchCount = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount++
            if (dispatchCount == 1) {
                job?.cancel()
            }
            Dispatchers.IO.dispatch(context, block)
        }
    }

    @Test
    fun testCommitCancellation() {
        runBlocking {
            val vocabulary1 = mapOf("key1" to "Text 1")
            val recordings1 = mapOf("key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000))

            val firstCache = createCache()
            val commitResult1 = firstCache.commit(vocabulary1, defaultSettings, recordings1)
            assertEquals(AnnouncementCacheCommitResult.Written, commitResult1)

            val manifestFile = File(rootDirectory, "manifest.json")
            val priorManifestText = manifestFile.readText()

            val cancellingDispatcher = CancellingDispatcher()
            val cache = AnnouncementPcmCache(rootDirectory, AnnouncementCacheIdentity.build(12345L, defaultModelSetHash), cancellingDispatcher)

            val vocabulary2 = mapOf("key1" to "Text 1", "key2" to "Text 2")
            val recordings2 = mapOf(
                "key1" to RecordedPcm(ShortArray(1600) { 1 }, 16000),
                "key2" to RecordedPcm(ShortArray(3200) { 2 }, 16000)
            )

            var threwCancellation = false
            val job = Job()
            cancellingDispatcher.job = job

            val scope = CoroutineScope(Dispatchers.Default + job)
            val testJob = scope.launch {
                try {
                    cache.commit(vocabulary2, defaultSettings, recordings2)
                } catch (e: CancellationException) {
                    threwCancellation = true
                }
            }

            testJob.join()

            assertTrue(threwCancellation)
            assertEquals(priorManifestText, manifestFile.readText())

            val entriesDir = File(rootDirectory, "entries")
            val wavFiles = entriesDir.listFiles { _, name -> name.endsWith(".wav") }
            assertEquals(1, wavFiles?.size ?: 0)
        }
    }
}
