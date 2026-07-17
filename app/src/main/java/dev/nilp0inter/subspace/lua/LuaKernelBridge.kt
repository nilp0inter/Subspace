package dev.nilp0inter.subspace.lua

/**
 * Semantic bridge interface for the Lua actor kernel. All kernel states use
 * the JVM-owned ownership model, which implements these semantic operations.
 *
 * No method throws for expected input. Every method returns a normalized
 * [LuaKernelOutcome]. Malformed source, invalid handles, stale generations,
 * duplicate completions, and post-close operations return typed outcomes
 * without entering Lua.
 *
 * No native pointer or Lua registry index crosses this boundary. State,
 * coroutine, and operation references are represented by opaque
 * [LuaStateHandle], [LuaCoroutineHandle], and [LuaOperationHandle] validated
 * against their owning state generation.
 *
 * This interface is internal. It is not registered as a provider, does not
 * participate in application startup, and does not alter existing channel
 * behavior.
 */
internal interface LuaKernelBridge {

    /**
     * Create an independent Lua kernel state with the given [config]. Returns
     * [LuaKernelOutcome.Created] on success. The state has its own global
     * environment, module cache, and allocator accounting.
     */
    fun create(config: LuaKernelConfig): LuaKernelOutcome

    /**
     * Load Lua [source] in text-only mode and validate the named
     * [entrypoint]. Rejects binary chunks, `package.loadlib`, C-module
     * searchers, JNI, FFI, and plugin-provided shared libraries. Returns
     * [LuaKernelOutcome.SyntaxFailure] for malformed source or
     * [LuaKernelOutcome.ValidationFailure] for invalid entrypoint.
     */
    fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome

    /**
     * Start the entrypoint of a loaded state under protected execution. The
     * entrypoint may complete, fail, or call internal
     * `subspace.yield_operation(label)` and yield an opaque operation token.
     */
    fun start(handle: LuaStateHandle): LuaKernelOutcome

    /**
     * Resume a yielded coroutine exactly once with a normalized success or
     * failure value. Duplicate, foreign, stale, and post-close completions
     * return typed outcomes without resuming Lua.
     */
    fun resume(
        operation: LuaOperationHandle,
        success: Boolean,
        value: String,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome

    /**
     * Cancel a suspended operation token. Cancellation and completion race
     * deterministically; exactly one terminal outcome wins.
     */
    fun cancel(operation: LuaOperationHandle): LuaKernelOutcome

    /**
     * Interrupt active Lua execution via the instruction-count hook. The hook
     * normalizes the affected state as interrupted and permits deterministic
     * teardown. Not claimed to preempt a blocking C or JNI function.
     */
    fun interrupt(handle: LuaStateHandle): LuaKernelOutcome

    /**
     * Snapshot per-state allocator accounting: current, sampled peak, denied
     * count, terminal Lua bytes, and bridge-owned bytes.
     */
    fun snapshot(handle: LuaStateHandle): LuaKernelOutcome

    /**
     * Close a state idempotently. Invalidates all owned coroutine and
     * operation handles atomically before native memory is released. Late
     * completions are rejected as [LuaKernelOutcome.Closed] or
     * [LuaKernelOutcome.Stale].
     */
    fun close(handle: LuaStateHandle): LuaKernelOutcome

    /**
     * Load a Lua program image containing multiple modules, evaluate modules under
     * an effect guard, validate the returned callback table, and return a Completed
     * outcome containing the list of available callback names on success.
     */
    fun loadProgramImage(
        handle: LuaStateHandle,
        entryPoint: String,
        sourceMap: Map<String, String>
    ): LuaKernelOutcome

    /**
     * Invoke the startup callback with Lua's zero-argument call semantics.
     * This is intentionally distinct from event callback invocation, whose
     * single [LuaValue] argument is encoded as one Lua argument.
     */
    fun invokeStartupCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome

    /**
     * Invoke a callback function synchronously with the given event arguments.
     * Spawn admission is scoped to this native execution slice.
     */
    fun invokeCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome

    /**
     * Start a spawned background coroutine.
     */
    fun startCoroutine(
        handle: LuaStateHandle,
        coroutineId: LuaCoroutineId,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome
}

/** Native callback port: 0 accepted, 1 closed, 2 capacity exhausted. */
internal interface LuaSpawnAdmission {
    fun admitTask(coroutineId: Long): Int

    companion object {
        fun rejecting(): LuaSpawnAdmission = object : LuaSpawnAdmission {
            override fun admitTask(coroutineId: Long): Int = 1
        }
    }
}