package dev.nilp0inter.subspace.audiofile

import dev.nilp0inter.subspace.storage.ReasonSanitizer
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 6.8: One quota-bound app-private staged codec artifact.
 *
 * The capability allocates a unique host-owned temporary file under a non-Lua-visible root, lets a
 * codec (the OGG/Vorbis encoder) write into it, then streams the complete artifact to the mount and
 * deletes it. [accountedBytes] is the bytes currently charged against the staging quotas: the
 * reserved ceiling on [AudioFileStaging.acquire], reconciled to the actual encoded size by
 * [AudioFileStaging.commit]. [release] is exact-once and always runs on a terminal path. The file
 * path is never exposed to a caller or a language runtime.
 */
public class StagedArtifact internal constructor(
    public val file: File,
    internal var accountedBytes: Long,
) {
    internal val released = AtomicBoolean(false)
}

/**
 * 6.8: Quota-bound app-private codec staging with guaranteed cleanup.
 *
 * Staging is host-owned temporary storage used when a codec cannot write directly to a document
 * provider (the native OGG/Vorbis encoder requires a real path). It enforces finite per-operation,
 * per-generation, and per-process byte limits, and deletes the temporary artifact on success,
 * failure, timeout, cancellation, revocation, and close. Copying the staged artifact to the mount
 * completes before any success; a process interruption MAY leave an uncommitted provider artifact,
 * but never a lingering staged file once [release] runs. The staging root and file paths are never
 * exposed to a language runtime.
 */
public class AudioFileStaging(
    private val root: File,
    private val limits: AudioFileLimits,
    maxReasonBytes: Int = 256,
) {
    private val sanitizer = ReasonSanitizer(maxReasonBytes)
    private val generationBytes = AtomicLong(0)
    private val processBytes = AtomicLong(0)
    private val activeFiles = AtomicInteger(0)
    private val lock = Any()
    private val entropy = SecureRandom()
    private val closed = AtomicBoolean(false)

    val generationStagingBytes: Long get() = generationBytes.get()
    val processStagingBytes: Long get() = processBytes.get()
    val activeFileCount: Int get() = activeFiles.get()
    val isClosed: Boolean get() = closed.get()

    /**
     * Reserves [reserveBytes] of the staging budget (the per-operation ceiling) and allocates a
     * unique temporary file. Fails closed: closed staging → [AudioFileErrorCode.E_CLOSED];
     * generation or process budget exhaustion → [AudioFileErrorCode.E_TOO_LARGE].
     */
    fun acquire(reserveBytes: Long): AudioFileOutcome<StagedArtifact> {
        if (closed.get()) {
            return AudioFileOutcome.Failure(
                AudioFileError(AudioFileErrorCode.E_CLOSED, sanitizer.sanitize("staging is closed")),
            )
        }
        synchronized(lock) {
            val nextGeneration = generationBytes.get() + reserveBytes
            val nextProcess = processBytes.get() + reserveBytes
            if (nextGeneration > limits.maxStagingBytesPerGeneration ||
                nextProcess > limits.maxStagingBytesPerProcess
            ) {
                return AudioFileOutcome.Failure(
                    AudioFileError(AudioFileErrorCode.E_TOO_LARGE, sanitizer.sanitize("staging budget exhausted")),
                )
            }
            generationBytes.set(nextGeneration)
            processBytes.set(nextProcess)
        }
        activeFiles.incrementAndGet()
        val file = File(root, "audio-stage-${newToken()}.tmp")
        return AudioFileOutcome.Success(StagedArtifact(file, reserveBytes))
    }

    /**
     * Enforces the per-operation ceiling on the actual encoded size and reconciles the reserved
     * budget down to [actualBytes]. Fails with [AudioFileErrorCode.E_TOO_LARGE] when the encoded
     * artifact exceeds the per-operation staging bound.
     */
    fun commit(staged: StagedArtifact, actualBytes: Long): AudioFileOutcome<Unit> {
        if (actualBytes > limits.maxStagingBytesPerOperation) {
            return AudioFileOutcome.Failure(
                AudioFileError(AudioFileErrorCode.E_TOO_LARGE, sanitizer.sanitize("encoded artifact exceeds the staging bound")),
            )
        }
        synchronized(lock) {
            val delta = actualBytes - staged.accountedBytes
            generationBytes.addAndGet(delta)
            processBytes.addAndGet(delta)
            staged.accountedBytes = actualBytes
        }
        return AudioFileOutcome.Success(Unit)
    }

    /**
     * Exact-once cleanup: deletes the temporary file (best effort) and releases the accounted budget
     * back to the generation and process ledgers. Runs on every terminal path; a second call is a
     * no-op that double-releases nothing.
     */
    fun release(staged: StagedArtifact) {
        if (!staged.released.compareAndSet(false, true)) return
        runCatching { if (staged.file.exists()) staged.file.delete() }
        synchronized(lock) {
            generationBytes.addAndGet(-staged.accountedBytes)
            processBytes.addAndGet(-staged.accountedBytes)
        }
        activeFiles.decrementAndGet()
    }

    /** Resets the per-generation staging budget (called when the generation advances). */
    fun resetGeneration() {
        synchronized(lock) {
            generationBytes.set(0)
        }
    }

    /** Closes staging: further [acquire] calls fail closed. Idempotent. */
    fun close() {
        closed.set(true)
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        entropy.nextBytes(bytes)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
