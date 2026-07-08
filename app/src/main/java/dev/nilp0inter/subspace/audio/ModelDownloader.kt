package dev.nilp0inter.subspace.audio

import android.content.Context
import dev.nilp0inter.subspace.model.DownloadProgress
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads model files from HuggingFace Hub to `filesDir/{modelSet}` and
 * verifies their SHA-256 against the bundled [ModelHashManifest]. Replaces the
 * old `ParakeetAssetExtractor`/`SupertonicAssetExtractor` asset-extraction
 * path: models are no longer bundled in the APK.
 *
 * `ensure` is the single entry point. It performs a fast version-marker check
 * (the launch-time `ModelVerifier` already did the full per-file hash check);
 * on marker mismatch or absence it downloads every file in the set, resuming
 * partial downloads via HTTP `Range`, and writes the version marker on success.
 *
 * This performs blocking network/disk I/O — callers MUST dispatch to
 * `Dispatchers.IO`.
 */
object ModelDownloader {
    private const val MAX_RETRIES = 3
    private const val HF_BASE = "https://huggingface.co"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Ensure the model set named [dirName] is present on disk and return its
     * directory. If the version marker matches the manifest, the existing
     * directory is returned unchanged (fast path). Otherwise every file in the
     * set is (re)downloaded, SHA-256 verified per file, and the marker written.
     */
    fun ensure(
        context: Context,
        dirName: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File {
        val manifest = ModelVerifier.loadManifest(context)
        val set = manifest.set(dirName)
        val dir = File(context.filesDir, dirName)
        if (!dir.exists()) dir.mkdirs()

        val marker = File(dir, ModelVerifier.MARKER_NAME)
        if (marker.exists() && marker.readText().trim() == set.version) {
            return dir
        }

        downloadSet(set, dir, onProgress)
        return dir
    }

    private fun downloadSet(
        set: ModelSetHash,
        dir: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        if (!dir.exists()) dir.mkdirs()
        val files = set.files
        files.forEachIndexed { index, file ->
            val local = File(dir, file.path.substringAfterLast('/'))
            downloadFileWithRetries(set.repo, file.path, local, file.sha256) { read, total ->
                onProgress(DownloadProgress(file.path, read, total, index, files.size))
            }
        }
        File(dir, ModelVerifier.MARKER_NAME).writeText(set.version)
    }

    /**
     * Download [hfPath] from `https://huggingface.co/{repo}/resolve/main/{hfPath}`
     * into [target], resuming from any existing partial bytes via `Range`.
     * Verifies SHA-256 on completion; on mismatch or failure, retries up to
     * [MAX_RETRIES] times (deleting the file first only on hash mismatch, so
     * network-failed partials stay resumable).
     */
    private fun downloadFileWithRetries(
        repo: String,
        hfPath: String,
        target: File,
        expectedSha256: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadFile(repo, hfPath, target, onProgress)
                if (sha256OfFile(target) == expectedSha256) return
                // Corrupt content — must not resume from it; delete and retry clean.
                target.delete()
                if (attempt > MAX_RETRIES) {
                    throw IllegalStateException(
                        "SHA-256 mismatch for ${target.name} after $MAX_RETRIES attempts",
                    )
                }
            } catch (e: Exception) {
                if (attempt > MAX_RETRIES) throw e
                // Partial bytes retained for Range-resume on the next attempt.
            }
        }
    }

    private fun downloadFile(
        repo: String,
        hfPath: String,
        target: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        val url = "$HF_BASE/$repo/resolve/main/$hfPath"
        val existingBytes = if (target.exists()) target.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { resp ->
            val isPartial = resp.code == 206
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw IllegalStateException("Empty response body for $url")
            val contentLen = body.contentLength()
            val totalBytes: Long
            val startRead: Long
            if (isPartial) {
                totalBytes = existingBytes + (contentLen.takeIf { it > 0 } ?: 0L)
                startRead = existingBytes
            } else {
                totalBytes = contentLen.takeIf { it > 0 } ?: -1L
                startRead = 0L
            }

            RandomAccessFile(target, "rw").use { out ->
                if (isPartial) {
                    out.seek(existingBytes)
                } else {
                    out.setLength(0)
                }
                val source = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var read = startRead
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, totalBytes)
                }
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
}
