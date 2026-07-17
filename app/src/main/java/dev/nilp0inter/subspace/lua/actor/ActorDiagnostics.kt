package dev.nilp0inter.subspace.lua.actor

import java.util.concurrent.atomic.AtomicLong

/**
 * Internal diagnostics observations feeding host-owned observability.
 *
 * Records current and peak memory, denial counts, instruction counts, and
 * latency. Policy values are evidence-derived, not persisted as plugin-facing
 * limits, and do not appear in the public configuration schema. These
 * observations are NOT a public API.
 */
internal class ActorDiagnostics {

    private val currentBytes = AtomicLong(0L)
    private val peakBytes = AtomicLong(0L)
    private val deniedAllocations = AtomicLong(0L)
    private val instructionCount = AtomicLong(0L)
    private val totalLatencyNanos = AtomicLong(0L)
    private val operationCount = AtomicLong(0L)

    /**
     * Record a current Lua-managed native byte count and update peak.
     */
    fun recordMemory(current: Long) {
        currentBytes.set(current)
        peakBytes.accumulateAndGet(current) { a, b -> maxOf(a, b) }
    }

    /**
     * Record an allocator denial.
     */
    fun recordDenial() {
        deniedAllocations.incrementAndGet()
    }

    /**
     * Record executed instructions.
     */
    fun recordInstructions(count: Long) {
        instructionCount.addAndGet(count)
    }

    /**
     * Record one operation latency in nanoseconds.
     */
    fun recordLatency(elapsedNanos: Long) {
        totalLatencyNanos.addAndGet(elapsedNanos)
        operationCount.incrementAndGet()
    }

    /**
     * Snapshot diagnostics observations.
     */
    fun snapshot(): ActorDiagnosticsSnapshot = ActorDiagnosticsSnapshot(
        currentBytes = currentBytes.get(),
        peakBytes = peakBytes.get(),
        deniedAllocations = deniedAllocations.get(),
        instructionCount = instructionCount.get(),
        totalLatencyNanos = totalLatencyNanos.get(),
        operationCount = operationCount.get(),
    )

    /**
     * Reset diagnostics. Called when a fresh generation is constructed.
     */
    fun reset() {
        currentBytes.set(0L)
        peakBytes.set(0L)
        deniedAllocations.set(0L)
        instructionCount.set(0L)
        totalLatencyNanos.set(0L)
        operationCount.set(0L)
    }
}

/**
 * Immutable diagnostics snapshot.
 */
internal data class ActorDiagnosticsSnapshot(
    val currentBytes: Long,
    val peakBytes: Long,
    val deniedAllocations: Long,
    val instructionCount: Long,
    val totalLatencyNanos: Long,
    val operationCount: Long,
)