package dev.nilp0inter.subspace.lua

/**
 * Configuration for one Lua kernel state. All fields are internal kernel
 * parameters and MUST NOT become a public plugin compatibility promise.
 *
 * @param memoryLimitBytes per-state Lua allocator limit in bytes. The
 *   allocator records current and peak native bytes and denies requests that
 *   would exceed this limit.
 * @param hookInterval instruction-count hook interval. The hook interrupts
 *   pure-Lua execution that exceeds [instructionBudget]. Not a public limit.
 * @param instructionBudget total instruction budget for active execution.
 *   Exceeding it normalizes the affected state as interrupted.
 */
internal data class LuaKernelConfig(
    val memoryLimitBytes: Long,
    val hookInterval: Int,
    val instructionBudget: Long,
    val maxConcurrentTasks: Int = 16,
    val maxTimerSlots: Int = 16,
) {
    init {
        require(memoryLimitBytes >= 0L) { "Memory limit must be non-negative" }
        require(hookInterval > 0) { "Hook interval must be positive" }
        require(instructionBudget >= 0L) { "Instruction budget must be non-negative" }
    }
}