package dev.nilp0inter.subspace.dependency

import dev.nilp0inter.subspace.lua.API_VERSION
import dev.nilp0inter.subspace.lua.LUA_VERSION
import dev.nilp0inter.subspace.lua.LuaCallbackHandle
import dev.nilp0inter.subspace.lua.LuaCoroutineId
import dev.nilp0inter.subspace.lua.LuaKernelBridge
import dev.nilp0inter.subspace.lua.LuaKernelConfig
import dev.nilp0inter.subspace.lua.LuaKernelOutcome
import dev.nilp0inter.subspace.lua.LuaOperationHandle
import dev.nilp0inter.subspace.lua.LuaSpawnAdmission
import dev.nilp0inter.subspace.lua.LuaStateHandle
import dev.nilp0inter.subspace.lua.LuaValue
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.InstalledProviderBinding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transaction-contract coverage for the installed package repository. Fixtures intentionally write
 * Unix local and central ZIP metadata so every mutation reaches storage rather than archive
 * validation. Publication is a recording host boundary: every recorded snapshot was produced only
 * after the repository had selected and materialized its committed on-disk index.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledPackageTransactionsTest {
    @Test
    fun `install reinstall update rollback no rollback and removal keep index content and publication in lockstep`() = runTest {
        withTemporaryDirectory { root ->
            val publications = RecordingPublisher(root)
            val repository = repository(root, publications)
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val v2 = packageArchive("123", "2.0.0", "second")

            assertEquals(MutationResult.Installed(provider), success(repository.installOrUpdate(ByteArrayInputStream(v1), source)))
            val afterInstall = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(v1), afterInstall.active.digest.value)
            assertNull(afterInstall.rollback)
            assertContent(root, afterInstall.active.digest.value, v1)
            publications.assertLast(setOf(provider), emptySet())

            val publicationsAfterInstall = publications.snapshots.size
            assertEquals(MutationResult.Reinstalled(provider), success(repository.installOrUpdate(ByteArrayInputStream(v1), source)))
            val afterReinstall = index(root).providers.getValue(source.repositoryId)
            assertEquals(afterInstall, afterReinstall)
            assertEquals("an exact reinstall must not publish a replacement snapshot", publicationsAfterInstall, publications.snapshots.size)

            assertEquals(MutationResult.Updated(provider), success(repository.installOrUpdate(ByteArrayInputStream(v2), source)))
            val afterUpdate = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(v2), afterUpdate.active.digest.value)
            assertEquals(digest(v1), afterUpdate.rollback?.digest?.value)
            assertContent(root, afterUpdate.active.digest.value, v2)
            assertContent(root, requireNotNull(afterUpdate.rollback).digest.value, v1)
            publications.assertLast(setOf(provider), emptySet())

            assertEquals(MutationResult.RolledBack(provider), success(repository.rollback(source.repositoryId)))
            val afterRollback = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(v1), afterRollback.active.digest.value)
            assertEquals(digest(v2), afterRollback.rollback?.digest?.value)
            publications.assertLast(setOf(provider), emptySet())

            val noRollbackRoot = File(root, "no-rollback")
            val noRollbackPublications = RecordingPublisher(noRollbackRoot)
            val noRollbackRepository = repository(noRollbackRoot, noRollbackPublications)
            success(noRollbackRepository.installOrUpdate(ByteArrayInputStream(v1), source))
            val noRollbackIndex = index(noRollbackRoot)
            val noRollbackPublicationCount = noRollbackPublications.snapshots.size
            assertFailure(noRollbackRepository.rollback(source.repositoryId), PackageFailure.RollbackDetail.NO_ROLLBACK_REVISION)
            assertEquals(noRollbackIndex, index(noRollbackRoot))
            assertEquals(noRollbackPublicationCount, noRollbackPublications.snapshots.size)

            assertEquals(MutationResult.Removed(provider), success(repository.remove(source.repositoryId)))
            assertFalse(index(root).providers.containsKey(source.repositoryId))
            publications.assertLast(emptySet(), emptySet())
        }
    }

    @Test
    fun `staging failure preserves authoritative revision while committed publication failure recovers after restart`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val v2 = packageArchive("123", "2.0.0", "second")
            val publications = RecordingPublisher(root)
            val repository = repository(root, publications)
            success(repository.installOrUpdate(ByteArrayInputStream(v1), source))
            val beforeFailure = index(root)
            val publishedBeforeFailure = publications.snapshots.size

            assertFailure(repository.installOrUpdate(FailingInputStream(), source), PackageFailure.StorageDetail.WRITE_FAILED)
            assertEquals(beforeFailure, index(root))
            assertEquals(publishedBeforeFailure, publications.snapshots.size)
            assertTrue(File(root, "staging").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            val fileInsteadOfStore = File(root, "file-instead-of-store").apply { writeText("not a directory", UTF_8) }
            val blockedPublications = RecordingPublisher(fileInsteadOfStore)
            assertFailure(
                repository(fileInsteadOfStore, blockedPublications).installOrUpdate(ByteArrayInputStream(v1), source),
                PackageFailure.StorageDetail.WRITE_FAILED,
            )
            assertTrue("a failed staging-directory write must never publish", blockedPublications.snapshots.isEmpty())

            val rejectingPublisher = RecordingPublisher(root, reject = true)
            val committingRepository = repository(root, rejectingPublisher)
            val outcome = success(committingRepository.installOrUpdate(ByteArrayInputStream(v2), source))
            val publicationFailure = outcome as? MutationResult.PublicationFailure
                ?: throw AssertionError("expected a post-commit publication failure, got $outcome")
            assertTrue(publicationFailure.error is PackageFailure.Loading)
            val committed = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(v2), committed.active.digest.value)
            assertEquals(digest(v1), committed.rollback?.digest?.value)
            assertTrue("publication is rejected after, not before, the durable index commit", rejectingPublisher.snapshots.isEmpty())

            val restartedPublisher = RecordingPublisher(root)
            val restarted = repository(root, restartedPublisher)
            assertEquals(Unit, success(restarted.loadAndPublish()))
            restartedPublisher.assertLast(setOf(provider), emptySet())
            assertEquals(digest(v2), index(root).providers.getValue(source.repositoryId).active.digest.value)
        }
    }

    @Test
    fun `recovery isolates corrupt active provider selects complete backup and leaves absent store empty without migration`() = runTest {
        withTemporaryDirectory { root ->
            val sourceA = sourceRecord("123", "456", "789")
            val sourceB = sourceRecord("124", "457", "790")
            val providerA = InstalledProviderId.derive(sourceA.repositoryId)
            val providerB = InstalledProviderId.derive(sourceB.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val v2 = packageArchive("123", "2.0.0", "second")
            val sibling = packageArchive("124", "1.0.0", "sibling")
            val publisher = RecordingPublisher(root)
            val repository = repository(root, publisher)
            success(repository.installOrUpdate(ByteArrayInputStream(v1), sourceA))
            success(repository.installOrUpdate(ByteArrayInputStream(v2), sourceA))
            success(repository.installOrUpdate(ByteArrayInputStream(sibling), sourceB))
            val activeA = index(root).providers.getValue(sourceA.repositoryId).active.digest.value
            assertTrue(File(root, "content/sha256/$activeA").delete())

            val restartedPublisher = RecordingPublisher(root)
            assertEquals(Unit, success(repository(root, restartedPublisher).loadAndPublish()))
            restartedPublisher.assertLast(setOf(providerB), setOf(providerA))
            assertEquals(digest(v2), index(root).providers.getValue(sourceA.repositoryId).active.digest.value)
            assertEquals(digest(v1), index(root).providers.getValue(sourceA.repositoryId).rollback?.digest?.value)

            val backupRoot = File(root, "backup")
            val backupPublisher = RecordingPublisher(backupRoot)
            val backupRepository = repository(backupRoot, backupPublisher)
            success(backupRepository.installOrUpdate(ByteArrayInputStream(v1), sourceA))
            success(backupRepository.installOrUpdate(ByteArrayInputStream(v2), sourceA))
            File(backupRoot, "index.json").writeText("not a complete index", UTF_8)
            assertEquals(SelectedGeneration.BACKUP, success(InstalledPackageStore(backupRoot).loadIndex()).generation)
            val backupRecoveryPublisher = RecordingPublisher(backupRoot)
            assertEquals(Unit, success(repository(backupRoot, backupRecoveryPublisher).loadAndPublish()))
            backupRecoveryPublisher.assertLast(setOf(providerA), emptySet())
            assertEquals(digest(v1), backupRecoveryPublisher.snapshots.last().bindings.getValue(providerA).provider.fingerprint.value)

            val absentRoot = File(root, "absent").apply { mkdirs() }
            val catalogue = File(absentRoot, "channel-catalogue.json").apply { writeText("{\"opaque\":true}", UTF_8) }
            val bytesBeforeLoad = catalogue.readBytes()
            val absent = InstalledPackageStore(absentRoot)
            assertEquals(SelectedGeneration.EMPTY, success(absent.loadIndex()).generation)
            assertTrue(bytesBeforeLoad.contentEquals(catalogue.readBytes()))
            assertFalse(File(absentRoot, "index.json").exists())
            assertFalse(File(absentRoot, "index.backup.json").exists())
        }
    }

    @Test
    fun `cleanup removes incomplete staging and unreferenced content within its bounded work budget`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val archive = packageArchive("123", "1.0.0", "first")
            val repository = repository(root, RecordingPublisher(root))
            success(repository.installOrUpdate(ByteArrayInputStream(archive), source))
            val activeDigest = index(root).providers.getValue(source.repositoryId).active.digest.value
            val staging = File(root, "staging")
            val content = File(root, "content/sha256")
            File(staging, "incomplete.tmp").writeText("partial", UTF_8)
            val orphan = File(content, "f".repeat(64)).apply { writeText("orphan", UTF_8) }
            repeat(1_001) { ordinal -> File(staging, "leftover-$ordinal.tmp").writeText("x", UTF_8) }

            val cleanup = success(InstalledPackageStore(root).cleanup(index(root)))
            assertTrue("cleanup must cap work rather than scan unbounded stale storage", cleanup.inspectedCount < 1_003)
            assertTrue("the cleanup cap must leave work for a subsequent bounded pass", staging.listFiles().orEmpty().isNotEmpty())
            assertContent(root, activeDigest, archive)
            assertTrue("the first bounded pass may spend its budget in staging before content", orphan.exists())

            var passes = 0
            while (orphan.exists()) {
                success(InstalledPackageStore(root).cleanup(index(root)))
                passes++
                assertTrue("each cleanup pass has a finite bound", passes <= 3)
            }
            assertFalse(File(staging, "incomplete.tmp").exists())
            assertContent(root, activeDigest, archive)
        }
    }

    @Test
    fun `every durable content and index boundary preserves prior authoritative selection without publication`() = runTest {
        val cases = listOf(
            DurableFailureCase(DurableBoundary.STAGED_CONTENT_FSYNC, PackageFailure.StorageDetail.WRITE_FAILED, false),
            DurableFailureCase(DurableBoundary.CONTENT_ATOMIC_MOVE, PackageFailure.StorageDetail.COMMIT_FAILED, false),
            DurableFailureCase(DurableBoundary.CONTENT_DIR_FSYNC, PackageFailure.StorageDetail.WRITE_FAILED, true),
            DurableFailureCase(DurableBoundary.STAGING_DIR_FSYNC_AFTER_CONTENT, PackageFailure.StorageDetail.WRITE_FAILED, true),
            DurableFailureCase(DurableBoundary.INDEX_TEMP_WRITE, PackageFailure.StorageDetail.WRITE_FAILED, true),
            DurableFailureCase(DurableBoundary.INDEX_TEMP_FSYNC, PackageFailure.StorageDetail.WRITE_FAILED, true),
            DurableFailureCase(DurableBoundary.CURRENT_TO_BACKUP_MOVE, PackageFailure.StorageDetail.COMMIT_FAILED, true),
            DurableFailureCase(DurableBoundary.TEMP_TO_CURRENT_MOVE, PackageFailure.StorageDetail.COMMIT_FAILED, true),
            DurableFailureCase(DurableBoundary.STORE_DIR_FSYNC, PackageFailure.StorageDetail.COMMIT_FAILED, true),
            DurableFailureCase(DurableBoundary.STAGING_DIR_FSYNC_AFTER_INDEX, PackageFailure.StorageDetail.COMMIT_FAILED, true),
        )
        cases.forEach { case ->
            withTemporaryDirectory { root ->
                val source = sourceRecord("123", "456", "789")
                val provider = InstalledProviderId.derive(source.repositoryId)
                val v1 = packageArchive("123", "1.0.0", "first")
                val v2 = packageArchive("123", "2.0.0", "second")
                success(repository(root, RecordingPublisher(root)).installOrUpdate(ByteArrayInputStream(v1), source))
                val before = index(root)
                val publications = RecordingPublisher(root)
                val faultedStore = InstalledPackageStore(root, FaultInjector { boundary ->
                    if (boundary == case.boundary) throw IOException("injected ${case.boundary}")
                })

                assertFailure(
                    repository(faultedStore, publications).installOrUpdate(ByteArrayInputStream(v2), source),
                    case.expectedFailure,
                )
                assertEquals("${case.boundary} must retain one complete predecessor index", before, index(root))
                assertTrue("${case.boundary} must not publish a failed candidate", publications.snapshots.isEmpty())
                assertEquals(case.contentWasCommitted, File(root, "content/sha256/${digest(v2)}").exists())

                val restartPublisher = RecordingPublisher(root)
                assertEquals(Unit, success(repository(root, restartPublisher).loadAndPublish()))
                restartPublisher.assertLast(setOf(provider), emptySet())
                assertEquals(digest(v1), restartPublisher.snapshots.last().bindings.getValue(provider).provider.fingerprint.value)
            }
        }
    }

    @Test
    fun `simultaneous mutations serialize by admission order and retain both exact revisions`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val firstGate = CompletableDeferred<Unit>()
            val firstStarted = CompletableDeferred<Unit>()
            val first = packageArchive("123", "1.0.0", "first")
            val second = packageArchive("123", "2.0.0", "second")
            val publications = RecordingPublisher(root)
            val repository = InstalledPackageRepository(
                store = InstalledPackageStore(root),
                bridge = NoopLuaKernelBridge,
                publisher = publications::publish,
                dispatcher = Dispatchers.Default,
            )

            val firstMutation = async { repository.installOrUpdate(GatedInputStream(first, firstStarted, firstGate), source) }
            firstStarted.await()
            val secondMutation = async { repository.installOrUpdate(ByteArrayInputStream(second), source) }
            firstGate.complete(Unit)

            assertEquals(MutationResult.Installed(provider), success(firstMutation.await()))
            assertEquals(MutationResult.Updated(provider), success(secondMutation.await()))
            val record = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(second), record.active.digest.value)
            assertEquals(digest(first), record.rollback?.digest?.value)
            assertEquals(2, publications.snapshots.size)
            publications.assertLast(setOf(provider), emptySet())
        }
    }

    @Test
    fun `close aborts incomplete staging cleans it and suppresses every late publication`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val publications = RecordingPublisher(root)
            val repository = InstalledPackageRepository(
                store = InstalledPackageStore(root),
                bridge = NoopLuaKernelBridge,
                publisher = publications::publish,
                dispatcher = Dispatchers.Default,
            )
            val mutation = async { repository.installOrUpdate(GatedInputStream(packageArchive("123", "1.0.0", "first"), started, gate), source) }
            started.await()

            repository.requestClose()
            val close = async(Dispatchers.Default) { repository.closeAndAwait(1_000) }
            gate.complete(Unit)
            assertFailure(mutation.await(), PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
            success(close.await())
            assertTrue(index(root).providers.isEmpty())
            assertTrue(File(root, "staging").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            assertTrue("shutdown must suppress every publication from the interrupted operation", publications.snapshots.isEmpty())
            assertFailure(repository.installOrUpdate(ByteArrayInputStream(packageArchive("123", "1.0.0", "late")), source), PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
            assertTrue(publications.snapshots.isEmpty())
        }
    }

    private fun repository(root: File, publisher: RecordingPublisher): InstalledPackageRepository =
        repository(InstalledPackageStore(root), publisher)

    private fun repository(store: InstalledPackageStore, publisher: RecordingPublisher): InstalledPackageRepository = InstalledPackageRepository(
        store = store,
        bridge = NoopLuaKernelBridge,
        publisher = publisher::publish,
        dispatcher = Dispatchers.Default,
    )

    private fun index(root: File): StoredInstalledIndex = success(InstalledPackageStore(root).loadIndex()).index

    private fun assertContent(root: File, digest: String, expected: ByteArray) {
        assertTrue("committed content $digest must exist", File(root, "content/sha256/$digest").readBytes().contentEquals(expected))
    }

    private fun sourceRecord(repositoryId: String, releaseId: String, assetId: String): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(repositoryId),
        coordinates = GitHubRepositoryCoordinates("owner-$repositoryId", "repository-$repositoryId"),
        release = GitHubReleaseIdentity(releaseId, "v$releaseId", false),
        asset = GitHubAssetIdentity(assetId, "package-$assetId.zip"),
    )

    private fun packageArchive(repositoryId: String, version: String, marker: String): ByteArray {
        val source = "-- $marker\\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
        val manifest = """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Transaction package","summary":"Transactional package fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"}}"""
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), 0b1000000110100100),
            ),
        )
    }

    private fun strictUnixStoredZip(entries: List<ZipFixtureEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val centralEntries = entries.map { entry ->
            val name = entry.name.toByteArray(UTF_8)
            val crc = CRC32().apply { update(entry.bytes) }.value
            val localOffset = output.size().toLong()
            output.u32(LOCAL_SIGNATURE)
            output.u16(20)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(crc)
            output.u32(entry.bytes.size.toLong())
            output.u32(entry.bytes.size.toLong())
            output.u16(name.size)
            output.u16(0)
            output.write(name)
            output.write(entry.bytes)
            CentralFixtureEntry(entry, name, crc, localOffset)
        }
        val centralOffset = output.size().toLong()
        centralEntries.forEach { central ->
            output.u32(CENTRAL_SIGNATURE)
            output.u16((3 shl 8) or 20)
            output.u16(20)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(central.crc)
            output.u32(central.entry.bytes.size.toLong())
            output.u32(central.entry.bytes.size.toLong())
            output.u16(central.name.size)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(central.entry.unixMode.toLong() shl 16)
            output.u32(central.localOffset)
            output.write(central.name)
        }
        val centralSize = output.size().toLong() - centralOffset
        output.u32(EOCD_SIGNATURE)
        output.u16(0)
        output.u16(0)
        output.u16(entries.size)
        output.u16(entries.size)
        output.u32(centralSize)
        output.u32(centralOffset)
        output.u16(0)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("expected success, got ${outcome.error}")
    }

    private fun assertFailure(outcome: PackageOutcome<*>, expected: Any) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        val actual = when (failure) {
            is PackageFailure.Storage -> failure.detail
            is PackageFailure.Rollback -> failure.detail
            is PackageFailure.Shutdown -> failure.detail
            else -> null
        }
        assertEquals("expected typed failure $expected, got $failure", expected, actual)
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("installed-package-transactions-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class ZipFixtureEntry(val name: String, val bytes: ByteArray, val unixMode: Int)
    private data class CentralFixtureEntry(val entry: ZipFixtureEntry, val name: ByteArray, val crc: Long, val localOffset: Long)
    private data class DurableFailureCase(
        val boundary: DurableBoundary,
        val expectedFailure: PackageFailure.StorageDetail,
        val contentWasCommitted: Boolean,
    )

    private class RecordingPublisher(private val root: File, private val reject: Boolean = false) {
        val snapshots = Collections.synchronizedList(mutableListOf<PublishedSnapshot>())

        suspend fun publish(materialized: MaterializationResult): PackageOutcome<Unit> {
            if (reject) return PackageOutcome.Failure(PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED))
            val authoritative = when (val load = InstalledPackageStore(root).loadIndex()) {
                is PackageOutcome.Success -> load.value.index
                is PackageOutcome.Failure -> throw AssertionError("publisher observed no authoritative index: ${load.error}")
            }
            val bindingIds = materialized.bindings.keys
            val failureIds = materialized.failures.keys
            bindingIds.forEach { id ->
                val record = authoritative.providers.values.singleOrNull { InstalledProviderId.derive(it.active.sourceRecord.repositoryId) == id }
                    ?: throw AssertionError("publisher observed $id before a committed authoritative index binding")
                val binding = materialized.bindings.getValue(id)
                assertEquals(record.active.digest.value, binding.provider.fingerprint.value)
            }
            snapshots += PublishedSnapshot(bindingIds, failureIds, materialized.bindings)
            return PackageOutcome.Success(Unit)
        }

        fun assertLast(bindings: Set<ChannelImplementationId>, failures: Set<ChannelImplementationId>) {
            val last = snapshots.lastOrNull() ?: throw AssertionError("expected a publication")
            assertEquals(bindings, last.bindingIds)
            assertEquals(failures, last.failureIds)
        }
    }

    private data class PublishedSnapshot(
        val bindingIds: Set<ChannelImplementationId>,
        val failureIds: Set<ChannelImplementationId>,
        val bindings: Map<ChannelImplementationId, InstalledProviderBinding>,
    )

    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw IOException("injected staging read failure")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("injected staging read failure")
    }

    private class GatedInputStream(
        private val bytes: ByteArray,
        private val started: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : InputStream() {
        private var offset = 0
        private var admitted = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!admitted) {
                admitted = true
                started.complete(Unit)
                runBlocking { gate.await() }
            }
            if (this.offset == bytes.size) return -1
            val copied = minOf(length, bytes.size - this.offset)
            bytes.copyInto(buffer, offset, this.offset, this.offset + copied)
            this.offset += copied
            return copied
        }
    }

    private object NoopLuaKernelBridge : LuaKernelBridge {
        override fun create(config: LuaKernelConfig): LuaKernelOutcome = error("materialization must not create Lua states")
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = error("not used")
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = error("not used")
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome = error("not used")
        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun invokeCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
    }

    private companion object {
        const val LOCAL_SIGNATURE = 0x04034b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val EOCD_SIGNATURE = 0x06054b50L
    }
}
