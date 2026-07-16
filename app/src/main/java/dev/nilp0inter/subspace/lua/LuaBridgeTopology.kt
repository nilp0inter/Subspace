package dev.nilp0inter.subspace.lua

/**
 * Selected bridge topology for the proof substrate. All proof states use
 * the JVM-owned ownership model: Kotlin owns instance/generation state,
 * operation tokens, cancellation, and completion admission; native code
 * owns Lua states, protected calls, and coroutine references. Execution
 * occurs under a per-state serialization lock on the JNI caller.
 */
internal enum class LuaBridgeTopology(val wireValue: String) {
    JvmOwned("jvm_owned"),
    ;

    companion object {
        fun fromWire(value: String): LuaBridgeTopology? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Configuration for one proof Lua state. All fields are internal proof
 * parameters and MUST NOT become a public plugin compatibility promise.
 *
 * @param topology ownership/scheduling topology for this state.
 * @param memoryLimitBytes per-state Lua allocator limit in bytes. The
 *   allocator records current and peak native bytes and denies requests that
 *   would exceed this limit.
 * @param hookInterval instruction-count hook interval. The hook interrupts
 *   pure-Lua execution that exceeds [instructionBudget]. Not a public limit.
 * @param instructionBudget total instruction budget for active execution.
 *   Exceeding it normalizes the affected state as interrupted.
 */
internal data class LuaProofConfig(
    val topology: LuaBridgeTopology,
    val memoryLimitBytes: Long,
    val hookInterval: Int,
    val instructionBudget: Long,
) {
    init {
        require(memoryLimitBytes >= 0L) { "Memory limit must be non-negative" }
        require(hookInterval > 0) { "Hook interval must be positive" }
        require(instructionBudget >= 0L) { "Instruction budget must be non-negative" }
    }
}