package dev.nilp0inter.subspace.lua

/**
 * Semantic bridge interface for the proof Lua substrate. All proof states use
 * the selected JVM-owned topology ([LuaBridgeTopology.JvmOwned]), which
 * implements these semantic operations.
 *
 * No method throws for expected input. Every method returns a normalized
 * [LuaProofOutcome]. Malformed source, invalid handles, stale generations,
 * duplicate completions, and post-close operations return typed outcomes
 * without entering Lua.
 *
 * No native pointer or Lua registry index crosses this boundary. State,
 * coroutine, and operation references are represented by opaque
 * [LuaStateHandle], [LuaCoroutineHandle], and [LuaOperationHandle] validated
 * against their owning state generation.
 *
 * This interface is internal and instrumentation-only. It is not registered
 * as a provider, does not participate in application startup, and does not
 * alter existing channel behavior.
 */
internal interface LuaProofBridge {

    /**
     * Create an independent proof Lua state with the given [config]. Returns
     * [LuaProofOutcome.Created] on success. The state has its own global
     * environment, module cache, and allocator accounting.
     */
    fun create(config: LuaProofConfig): LuaProofOutcome

    /**
     * Load Lua [source] in text-only mode and validate the named
     * [entrypoint]. Rejects binary chunks, `package.loadlib`, C-module
     * searchers, JNI, FFI, and plugin-provided shared libraries. Returns
     * [LuaProofOutcome.SyntaxFailure] for malformed source or
     * [LuaProofOutcome.ValidationFailure] for invalid entrypoint.
     */
    fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaProofOutcome

    /**
     * Start the entrypoint of a loaded state under protected execution. The
     * entrypoint may complete, fail, or call internal
     * `subspace.yield_operation(label)` and yield an opaque operation token.
     */
    fun start(handle: LuaStateHandle): LuaProofOutcome

    /**
     * Resume a yielded coroutine exactly once with a normalized success or
     * failure value. Duplicate, foreign, stale, and post-close completions
     * return typed outcomes without resuming Lua.
     */
    fun resume(
        operation: LuaOperationHandle,
        success: Boolean,
        value: String,
    ): LuaProofOutcome

    /**
     * Cancel a suspended operation token. Cancellation and completion race
     * deterministically; exactly one terminal outcome wins.
     */
    fun cancel(operation: LuaOperationHandle): LuaProofOutcome

    /**
     * Interrupt active Lua execution via the instruction-count hook. The hook
     * normalizes the affected state as interrupted and permits deterministic
     * teardown. Not claimed to preempt a blocking C or JNI function.
     */
    fun interrupt(handle: LuaStateHandle): LuaProofOutcome

    /**
     * Snapshot per-state allocator accounting: current, sampled peak, denied
     * count, terminal Lua bytes, and bridge-owned bytes.
     */
    fun snapshot(handle: LuaStateHandle): LuaProofOutcome

    /**
     * Close a state idempotently. Invalidates all owned coroutine and
     * operation handles atomically before native memory is released. Late
     * completions are rejected as [LuaProofOutcome.Closed] or
     * [LuaProofOutcome.Stale].
     */
    fun close(handle: LuaStateHandle): LuaProofOutcome
}