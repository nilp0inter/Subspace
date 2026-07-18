package dev.nilp0inter.subspace.dependency

import dev.nilp0inter.subspace.lua.API_VERSION
import dev.nilp0inter.subspace.lua.LUA_VERSION
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the public static package-validation boundary.  The ZIP writer deliberately
 * emits Unix central/local metadata, while its independent central and local fields allow each
 * raw-parser consistency check to be targeted without testing implementation details.
 */
class PackageValidatorContractTest {
    @Test
    fun `valid source-only package derives canonical modules retains source provenance and hashes exact bytes`() = withTemporaryDirectory { root ->
        val source = sourceRecord()
        val archive = archiveBytes(
            modules = mapOf(
                "main" to SOURCE,
                "client.http" to "return {}",
            ),
        )

        val revision = expectSuccess(validate(root, archive, source))

        assertEquals(setOf("main", "client.http"), revision.sourceMap.keys)
        assertEquals(SOURCE, revision.sourceMap.getValue("main"))
        assertEquals("main", revision.programImage.entryPoint)
        assertEquals("github-repository:123", InstalledProviderId.derive(source.repositoryId).value)
        assertEquals(source, revision.sourceRecord)
        assertEquals(sha256(archive), revision.digest.value)
        assertEquals(revision.digest.value, revision.fingerprint.value)
    }

    @Test
    fun `manifest shape rejects missing unknown duplicate mistyped blank and oversized declarations`() = withTemporaryDirectory { root ->
        val base = manifest()
        val cases = listOf(
            ManifestCase("missing root field", base.replace("\"packageVersion\":\"1.0.0\",", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing nested field", base.replace("\"summary\":\"Package summary\"", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown root field", base.dropLast(1) + ",\"capabilities\":[]}", PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("unknown nested field", base.replace("\"summary\":\"Package summary\"", "\"summary\":\"Package summary\",\"extra\":true"), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("duplicate key", base.replace("\"repositoryId\":\"123\"", "\"repositoryId\":\"123\",\"repositoryId\":\"123\""), PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("mistyped manifest version", base.replace("\"manifestVersion\":1", "\"manifestVersion\":\"1\""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped repository identity", base.replace("\"repositoryId\":\"123\"", "\"repositoryId\":123"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped presentation", "{\"manifestVersion\":1,\"repositoryId\":\"123\",\"packageVersion\":\"1.0.0\",\"entryModule\":\"main\",\"presentation\":[],\"runtime\":{\"luaVersion\":\"$LUA_VERSION\",\"apiVersion\":\"$API_VERSION\"}}", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank package version", base.replace("\"packageVersion\":\"1.0.0\"", "\"packageVersion\":\" \\t\""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank label", base.replace("\"label\":\"Package\"", "\"label\":\"\""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank summary", base.replace("\"summary\":\"Package summary\"", "\"summary\":\" \\n\""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name,
            )
        }

        val largeManifest = manifest(packageVersion = "v".repeat(400))
        val archive = archiveBytes(manifest = largeManifest)
        val bounds = bounds(maxManifestBytes = largeManifest.toByteArray(UTF_8).size - 1)
        assertFormat(validate(root, archive, sourceRecord(), bounds), PackageFailure.FormatDetail.BOUNDS_EXCEEDED, "oversized manifest")
    }

    @Test
    fun `manifest version runtime and repository assertion fail with typed compatibility or identity outcomes`() = withTemporaryDirectory { root ->
        val cases = listOf(
            ManifestCase("unsupported manifest version", manifest().replace("\"manifestVersion\":1", "\"manifestVersion\":2"), PackageFailure.CompatibilityDetail.UNSUPPORTED_MANIFEST_VERSION),
            ManifestCase("incompatible Lua version", manifest().replace(LUA_VERSION, "Lua 5.3"), PackageFailure.CompatibilityDetail.LUA_VERSION_INCOMPATIBLE),
            ManifestCase("incompatible API version", manifest().replace(API_VERSION, "subspace-lua-v2"), PackageFailure.CompatibilityDetail.API_VERSION_INCOMPATIBLE),
            ManifestCase("repository identity mismatch", manifest(repositoryId = "124"), PackageFailure.IdentityDetail.REPOSITORY_ID_MISMATCH),
        )
        cases.forEach { case ->
            val outcome = validate(root, archiveBytes(manifest = case.json), sourceRecord())
            when (case.expected) {
                is PackageFailure.CompatibilityDetail -> assertCompatibility(outcome, case.expected, case.name)
                is PackageFailure.IdentityDetail -> assertIdentity(outcome, case.expected, case.name)
                else -> error("wrong case type")
            }
        }

        val renamedCoordinates = sourceRecord(coordinates = GitHubRepositoryCoordinates("renamed-owner", "renamed-repository"))
        val revision = expectSuccess(validate(root, archiveBytes(), renamedCoordinates))
        assertEquals("github-repository:123", InstalledProviderId.derive(revision.sourceRecord.repositoryId).value)
        assertEquals(renamedCoordinates.coordinates, revision.sourceRecord.coordinates)
    }

    @Test
    fun `canonical path grammar rejects traversal aliases separators and collisions`() = withTemporaryDirectory { root ->
        val invalidPaths = listOf(
            "../main.lua", "lua/../main.lua", "/lua/main.lua", "lua//main.lua", "lua\\main.lua",
            "lua/%2fmain.lua", "lua/%5Cmain.lua", "lua/ma%69n.lua", "lua/main\u0000.lua",
            "lua/main.lua/", "lua/Main.lua", "lua/e\u0301.lua",
        )
        invalidPaths.forEach { path ->
            assertFormat(
                validate(root, archiveBytes(entries = validEntries() + FixtureEntry(path, SOURCE.toByteArray(UTF_8))), sourceRecord()),
                if (path == "lua/Main.lua") PackageFailure.FormatDetail.COLLISION else if (path == "lua/main.lua/") PackageFailure.FormatDetail.UNEXPECTED_ENTRY else PackageFailure.FormatDetail.INVALID_MODULE_GRAMMAR,
                path,
            )
        }

        listOf(
            listOf("lua/main.lua", "lua/main.lua") to "exact duplicate",
            listOf("lua/main.lua", "lua/MAIN.lua") to "case collision",
            listOf("lua/e.lua", "lua/e\u0301.lua") to "non-NFC collision input",
        ).forEach { (paths, name) ->
            val entries = listOf(
                FixtureEntry("manifest.json", manifest().toByteArray(UTF_8)),
                FixtureEntry("lua/", ByteArray(0), directory = true),
            ) + paths.map { FixtureEntry(it, SOURCE.toByteArray(UTF_8)) }
            val expected = if (name == "non-NFC collision input") PackageFailure.FormatDetail.INVALID_MODULE_GRAMMAR else PackageFailure.FormatDetail.COLLISION
            assertFormat(validate(root, strictZip(entries), sourceRecord()), expected, name)
        }
    }

    @Test
    fun `archive layout rejects missing manifest unexpected files and unsupported Unix entry kinds`() = withTemporaryDirectory { root ->
        assertFormat(
            validate(root, strictZip(listOf(FixtureEntry("lua/", ByteArray(0), directory = true), FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8)))), sourceRecord()),
            PackageFailure.FormatDetail.MISSING_MANIFEST,
            "missing manifest",
        )

        val cases = listOf(
            EntryCase("asset", FixtureEntry("assets/icon.png", byteArrayOf(1)), PackageFailure.FormatDetail.UNEXPECTED_ENTRY),
            EntryCase("bytecode path", FixtureEntry("lua/main.luac", byteArrayOf(1)), PackageFailure.FormatDetail.UNEXPECTED_ENTRY),
            EntryCase("symlink", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), unixMode = 0xA1FF), PackageFailure.FormatDetail.UNEXPECTED_ENTRY),
            EntryCase("executable", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), unixMode = 0x81ED), PackageFailure.FormatDetail.UNEXPECTED_ENTRY),
            EntryCase("non Unix host", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), versionMadeBy = 0x0014), PackageFailure.FormatDetail.INVALID_ZIP),
            EntryCase("unsupported compression", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), method = 12), PackageFailure.FormatDetail.UNSUPPORTED_COMPRESSION),
            EntryCase("encrypted", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), flags = 1), PackageFailure.FormatDetail.ENCRYPTED_ENTRY),
            EntryCase("data descriptor", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), flags = 8), PackageFailure.FormatDetail.INVALID_ZIP),
        )
        cases.forEach { case ->
            assertFormat(validate(root, strictZip(validEntries(module = case.entry)), sourceRecord()), case.expected, case.name)
        }
    }

    @Test
    fun `raw ZIP parser rejects multi disk and every local central consistency mismatch`() = withTemporaryDirectory { root ->
        val archive = strictZip(validEntries())
        assertFormat(validate(root, mutateEocd(archive) { it[4] = 1 }, sourceRecord()), PackageFailure.FormatDetail.INVALID_ZIP, "multi disk")

        val mutations = listOf(
            "version" to { bytes: ByteArray -> mutateLocal(bytes, 4) { it + 1 } },
            "flags" to { bytes: ByteArray -> mutateLocal(bytes, 6) { it xor 0x0800 } },
            "modification time" to { bytes: ByteArray -> mutateLocal(bytes, 10) { it + 1 } },
            "modification date" to { bytes: ByteArray -> mutateLocal(bytes, 12) { it + 1 } },
            "method" to { bytes: ByteArray -> mutateLocal(bytes, 8) { 8 } },
            "CRC" to { bytes: ByteArray -> mutateLocal32(bytes, 14) { it xor 1 } },
            "compressed size" to { bytes: ByteArray -> mutateLocal32(bytes, 18) { it + 1 } },
            "uncompressed size" to { bytes: ByteArray -> mutateLocal32(bytes, 22) { it + 1 } },
            "name" to { bytes: ByteArray -> mutateLocalName(bytes) },
            "extra" to { bytes: ByteArray -> mutateLocalExtra(bytes) },
        )
        mutations.forEach { (name, mutate) ->
            assertFormat(validate(root, mutate(archive), sourceRecord()), PackageFailure.FormatDetail.INVALID_ZIP, "local central $name")
        }
    }

    @Test
    fun `strict content decoding rejects invalid UTF8 Lua binary chunks and corrupted content`() = withTemporaryDirectory { root ->
        val cases = listOf(
            "invalid manifest UTF8" to strictZip(validEntries(manifest = byteArrayOf(0xC3.toByte(), 0x28))),
            "invalid Lua UTF8" to strictZip(validEntries(module = FixtureEntry("lua/main.lua", byteArrayOf(0xC3.toByte(), 0x28)))),
            "Lua bytecode" to strictZip(validEntries(module = FixtureEntry("lua/main.lua", byteArrayOf(0x1B, 0x4C, 0x75, 0x61)))),
            "CRC corrupt payload" to corruptFirstPayload(strictZip(validEntries())),
        )
        assertFormat(validate(root, cases[0].second, sourceRecord()), PackageFailure.FormatDetail.MALFORMED_MANIFEST, cases[0].first)
        assertFormat(validate(root, cases[1].second, sourceRecord()), PackageFailure.FormatDetail.INVALID_ZIP, cases[1].first)
        assertFormat(validate(root, cases[2].second, sourceRecord()), PackageFailure.FormatDetail.BYTECODE_PROHIBITED, cases[2].first)
        assertIntegrity(validate(root, cases[3].second, sourceRecord()), PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE, cases[3].first)
    }

    @Test
    fun `every configured archive resource bound accepts exact edge and rejects one beyond`() = withTemporaryDirectory { root ->
        val baseline = archiveBytes()
        assertBoundsEdge(root, "artifact", baseline, bounds(maxArtifactBytes = baseline.size.toLong()), bounds(maxArtifactBytes = baseline.size.toLong() - 1))

        val entryEdge = strictZip(validEntries())
        assertBoundsEdge(root, "entry count", entryEdge, bounds(maxEntryCount = 3), bounds(maxEntryCount = 2))

        val manifestText = manifest()
        val manifestEdge = archiveBytes(manifest = manifestText)
        assertBoundsEdge(root, "manifest bytes", manifestEdge, bounds(maxManifestBytes = manifestText.toByteArray(UTF_8).size), bounds(maxManifestBytes = manifestText.toByteArray(UTF_8).size - 1))

        val path = "lua/long_name.lua"
        val pathEdge = strictZip(validEntries(
            manifest = manifest(entryModule = "long_name").toByteArray(UTF_8),
            module = FixtureEntry(path, SOURCE.toByteArray(UTF_8)),
        ))
        assertBoundsEdge(root, "path bytes", pathEdge, bounds(maxPathBytes = path.toByteArray(UTF_8).size), bounds(maxPathBytes = path.toByteArray(UTF_8).size - 1))

        val module = "x".repeat(32)
        val moduleEdge = strictZip(validEntries(module = FixtureEntry("lua/main.lua", module.toByteArray(UTF_8))))
        assertBoundsEdge(root, "per source bytes", moduleEdge, bounds(maxPerModuleBytes = module.length, maxTotalSourceBytes = module.length), bounds(maxPerModuleBytes = module.length - 1, maxTotalSourceBytes = module.length))

        val totalEntries = listOf(
            FixtureEntry("manifest.json", manifest().toByteArray(UTF_8)),
            FixtureEntry("lua/", ByteArray(0), directory = true),
            FixtureEntry("lua/main.lua", "a".repeat(16).toByteArray(UTF_8)),
            FixtureEntry("lua/client.lua", "b".repeat(16).toByteArray(UTF_8)),
        )
        val totalEdge = strictZip(totalEntries)
        assertBoundsEdge(root, "total source bytes", totalEdge, bounds(maxPerModuleBytes = 16, maxTotalSourceBytes = 32), bounds(maxPerModuleBytes = 16, maxTotalSourceBytes = 31))

        val expanded = "z".repeat(1_024).toByteArray(UTF_8)
        val deflated = strictZip(validEntries(module = FixtureEntry("lua/main.lua", expanded, method = 8)))
        assertBoundsEdge(root, "compression expansion", deflated, bounds(maxExpansionRatio = expansionRatio(deflated, "lua/main.lua")), bounds(maxExpansionRatio = expansionRatio(deflated, "lua/main.lua") - 0.01))
    }

    @Test
    fun `static validation does not execute an adversarial Lua payload`() = withTemporaryDirectory { root ->
        val marker = File(root, "executed")
        val payload = "error('package payload must not execute during validation'); os.execute([[touch ${marker.absolutePath}]])"

        expectSuccess(validate(root, archiveBytes(modules = mapOf("main" to payload)), sourceRecord()))

        assertFalse("validation must not create a Lua runtime or execute the package payload", marker.exists())
    }

    private fun assertBoundsEdge(root: File, name: String, archive: ByteArray, atLimit: PackageValidationBounds, overLimit: PackageValidationBounds) {
        expectSuccess(validate(root, archive, sourceRecord(), atLimit))
        assertFormat(validate(root, archive, sourceRecord(), overLimit), PackageFailure.FormatDetail.BOUNDS_EXCEEDED, "$name beyond limit")
    }

    private fun validate(root: File, archive: ByteArray, source: PackageSourceRecord, bounds: PackageValidationBounds = generousBounds()): PackageOutcome<ValidatedPackageRevision> =
        PackageValidator.validatePackage(ByteArrayInputStream(archive), source, File(root, "${System.nanoTime()}.zip"), bounds)

    private fun expectSuccess(outcome: PackageOutcome<ValidatedPackageRevision>): ValidatedPackageRevision = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private fun assertFormat(outcome: PackageOutcome<*>, detail: PackageFailure.FormatDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as FORMAT/$detail but was $failure", failure is PackageFailure.Format && failure.detail == detail)
    }

    private fun assertCompatibility(outcome: PackageOutcome<*>, detail: PackageFailure.CompatibilityDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as COMPATIBILITY/$detail but was $failure", failure is PackageFailure.Compatibility && failure.detail == detail)
    }

    private fun assertIdentity(outcome: PackageOutcome<*>, detail: PackageFailure.IdentityDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as IDENTITY/$detail but was $failure", failure is PackageFailure.Identity && failure.detail == detail)
    }

    private fun assertIntegrity(outcome: PackageOutcome<*>, detail: PackageFailure.IntegrityDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as INTEGRITY/$detail but was $failure", failure is PackageFailure.Integrity && failure.detail == detail)
    }

    private fun sourceRecord(coordinates: GitHubRepositoryCoordinates = GitHubRepositoryCoordinates("owner", "repository")) = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity("123"),
        coordinates = coordinates,
        release = GitHubReleaseIdentity("456", "v1.0.0", false),
        asset = GitHubAssetIdentity("789", "package.zip"),
        ownerId = "9000001",
    )

    private fun manifest(
        repositoryId: String = "123",
        packageVersion: String = "1.0.0",
        entryModule: String = "main",
    ): String = """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$packageVersion","entryModule":"$entryModule","presentation":{"label":"Package","summary":"Package summary"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"}}"""

    private fun archiveBytes(
        manifest: String = manifest(),
        modules: Map<String, String> = mapOf("main" to SOURCE),
        entries: List<FixtureEntry>? = null,
    ): ByteArray {
        val resolvedEntries = entries ?: buildList {
            add(FixtureEntry("manifest.json", manifest.toByteArray(UTF_8)))
            add(FixtureEntry("lua/", ByteArray(0), directory = true))
            modules.forEach { (module, source) ->
                add(FixtureEntry("lua/${module.replace('.', '/')}.lua", source.toByteArray(UTF_8)))
            }
        }
        return strictZip(resolvedEntries)
    }

    private fun validEntries(
        manifest: ByteArray = manifest().toByteArray(UTF_8),
        module: FixtureEntry = FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8)),
    ): List<FixtureEntry> = listOf(
        FixtureEntry("manifest.json", manifest),
        FixtureEntry("lua/", ByteArray(0), directory = true),
        module,
    )

    private fun strictZip(entries: List<FixtureEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = entries.map { entry ->
            val localName = entry.localName.toByteArray(UTF_8)
            val localData = encoded(entry.bytes, entry.localMethod)
            val crc = CRC32().apply { update(entry.bytes) }.value
            val offset = out.size().toLong()
            out.u32(LOCAL_SIGNATURE)
            out.u16(entry.localVersionNeeded)
            out.u16(entry.localFlags)
            out.u16(entry.localMethod)
            out.u16(0)
            out.u16(0)
            out.u32(crc)
            out.u32(localData.size.toLong())
            out.u32(entry.bytes.size.toLong())
            out.u16(localName.size)
            out.u16(entry.localExtra.size)
            out.write(localName)
            out.write(entry.localExtra)
            out.write(localData)
            CentralEntry(entry, crc, localData.size, offset)
        }
        val centralOffset = out.size().toLong()
        central.forEach { item ->
            val entry = item.entry
            val centralName = entry.name.toByteArray(UTF_8)
            out.u32(CENTRAL_SIGNATURE)
            out.u16(entry.versionMadeBy)
            out.u16(entry.versionNeeded)
            out.u16(entry.flags)
            out.u16(entry.method)
            out.u16(0)
            out.u16(0)
            out.u32(item.crc)
            out.u32(if (entry.method == 0) entry.bytes.size.toLong() else item.compressedSize.toLong())
            out.u32(entry.bytes.size.toLong())
            out.u16(centralName.size)
            out.u16(entry.extra.size)
            out.u16(0)
            out.u16(0)
            out.u16(0)
            out.u32(entry.unixMode.toLong() shl 16)
            out.u32(item.localOffset)
            out.write(centralName)
            out.write(entry.extra)
        }
        val centralSize = out.size().toLong() - centralOffset
        out.u32(EOCD_SIGNATURE)
        out.u16(0)
        out.u16(0)
        out.u16(entries.size)
        out.u16(entries.size)
        out.u32(centralSize)
        out.u32(centralOffset)
        out.u16(0)
        return out.toByteArray()
    }

    private fun encoded(data: ByteArray, method: Int): ByteArray = when (method) {
        0, 12 -> data
        8 -> {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                deflater.setInput(data)
                deflater.finish()
                val compressed = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (!deflater.finished()) {
                    compressed.write(buffer, 0, deflater.deflate(buffer))
                }
                compressed.toByteArray()
            } finally {
                deflater.end()
            }
        }
        else -> data
    }

    private fun mutateEocd(archive: ByteArray, change: (ByteArray) -> Unit): ByteArray = archive.copyOf().also { bytes ->
        val offset = bytes.size - 22
        val eocd = bytes.copyOfRange(offset, bytes.size)
        change(eocd)
        eocd.copyInto(bytes, offset)
    }

    private fun mutateLocal(archive: ByteArray, offset: Int, change: (Int) -> Int): ByteArray = archive.copyOf().also { bytes ->
        val value = u16(bytes, offset)
        put16(bytes, offset, change(value))
    }

    private fun mutateLocal32(archive: ByteArray, offset: Int, change: (Long) -> Long): ByteArray = archive.copyOf().also { bytes ->
        val value = u32(bytes, offset)
        put32(bytes, offset, change(value))
    }

    private fun mutateLocalName(archive: ByteArray): ByteArray = archive.copyOf().also { bytes ->
        val nameOffset = 30
        bytes[nameOffset] = 'M'.code.toByte()
    }

    private fun mutateLocalExtra(archive: ByteArray): ByteArray = archive.copyOf().also { bytes ->
        val extraLengthOffset = 28
        put16(bytes, extraLengthOffset, 4)
        val nameLength = u16(bytes, 26)
        val extraOffset = 30 + nameLength
        bytes[extraOffset] = 0x55
        bytes[extraOffset + 1] = 0x54
        bytes[extraOffset + 2] = 0
        bytes[extraOffset + 3] = 0
    }

    private fun corruptFirstPayload(archive: ByteArray): ByteArray = archive.copyOf().also { bytes ->
        val nameLength = u16(bytes, 26)
        bytes[30 + nameLength] = (bytes[30 + nameLength].toInt() xor 0x01).toByte()
    }

    private fun expansionRatio(archive: ByteArray, target: String): Double {
        var position = 0
        while (u32(archive, position) != CENTRAL_SIGNATURE) {
            val nameLength = u16(archive, position + 26)
            val extraLength = u16(archive, position + 28)
            val compressed = u32(archive, position + 18).toInt()
            val name = archive.copyOfRange(position + 30, position + 30 + nameLength).toString(UTF_8)
            if (name == target) return u32(archive, position + 22).toDouble() / compressed
            position += 30 + nameLength + extraLength + compressed
        }
        error("missing $target")
    }

    private fun bounds(
        maxArtifactBytes: Long = 1_000_000,
        maxEntryCount: Int = 32,
        maxManifestBytes: Int = 100_000,
        maxPathBytes: Int = 512,
        maxPerModuleBytes: Int = 100_000,
        maxTotalSourceBytes: Int = 200_000,
        maxExpansionRatio: Double = 100.0,
    ) = PackageValidationBounds(maxArtifactBytes, maxEntryCount, maxManifestBytes, maxPathBytes, maxPerModuleBytes, maxTotalSourceBytes, maxExpansionRatio)

    private fun generousBounds() = bounds()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 24) and 0xFF).toInt())
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long = (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8) or ((bytes[offset + 2].toLong() and 0xFF) shl 16) or ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    private fun put16(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte() }
    private fun put32(bytes: ByteArray, offset: Int, value: Long) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte(); bytes[offset + 2] = (value ushr 16).toByte(); bytes[offset + 3] = (value ushr 24).toByte() }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val root = createTempDirectory("package-validator-contract-").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    private data class ManifestCase(val name: String, val json: String, val expected: Any)
    private data class EntryCase(val name: String, val entry: FixtureEntry, val expected: PackageFailure.FormatDetail)
    private data class CentralEntry(val entry: FixtureEntry, val crc: Long, val compressedSize: Int, val localOffset: Long)
    private data class FixtureEntry(
        val name: String,
        val bytes: ByteArray,
        val directory: Boolean = false,
        val method: Int = 0,
        val flags: Int = 0,
        val versionMadeBy: Int = 0x0314,
        val versionNeeded: Int = 20,
        val unixMode: Int = if (directory) 0x41ED else 0x81A4,
        val extra: ByteArray = ByteArray(0),
        val localName: String = name,
        val localMethod: Int = method,
        val localFlags: Int = flags,
        val localVersionNeeded: Int = versionNeeded,
        val localExtra: ByteArray = extra,
    )

    private companion object {
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val SOURCE = "return { startup = function() end, handle_readiness = function() return { ready = true } end }"
    }
}
