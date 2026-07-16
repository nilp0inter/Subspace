package dev.nilp0inter.subspace.lua

/**
 * Normalized sealed outcome for every proof bridge operation. Every JNI
 * function returns a JSON object whose `kind` field maps to one of these
 * variants. Malformed or unknown JSON normalizes to
 * [RuntimeFailure] rather than throwing.
 *
 * Required `kind` values from the shared contract:
 * - `created` — state created successfully
 * - `completed` — entrypoint or resume completed
 * - `yielded` — coroutine yielded an opaque operation token
 * - `syntax_failure` — source loading found a syntax error
 * - `validation_failure` — entrypoint or config validation failed
 * - `runtime_failure` — protected Lua callback raised an error
 * - `memory_failure` — allocator denied a request under protected execution
 * - `interrupted` — instruction hook interrupted uncooperative execution
 * - `cancelled` — operation token was cancelled
 * - `invalid_ownership` — handle belongs to another state or unknown handle
 * - `stale` — generation mismatch or duplicate terminal completion
 * - `closed` — state was closed; late completion rejected
 */
internal sealed class LuaProofOutcome {
    abstract val stateId: Long?
    abstract val generation: Long?

    /** State created successfully. */
    data class Created(
        override val stateId: Long,
        override val generation: Long,
        val luaVersion: String,
        val bindingVersion: String,
        val topology: String,
    ) : LuaProofOutcome()

    /** Entrypoint or resume completed. */
    data class Completed(
        override val stateId: Long,
        override val generation: Long,
        val coroutineId: Long?,
        val value: String?,
        val elapsedNanos: Long?,
        val currentBytes: Long?,
        val peakBytes: Long?,
        val deniedAllocations: Long?,
        val bridgeBytes: Long?,
        val luaVersion: String?,
        val bindingVersion: String?,
        val topology: String?,
    ) : LuaProofOutcome()

    /** Coroutine yielded an opaque operation token. */
    data class Yielded(
        override val stateId: Long,
        override val generation: Long,
        val coroutineId: Long,
        val operationId: Long,
        val value: String?,
    ) : LuaProofOutcome()

    /** Source loading found a syntax error. State remains closable. */
    data class SyntaxFailure(
        override val stateId: Long,
        override val generation: Long,
        val diagnostic: String,
    ) : LuaProofOutcome()

    /** Entrypoint or config validation failed. State remains closable. */
    data class ValidationFailure(
        override val stateId: Long,
        override val generation: Long,
        val diagnostic: String,
    ) : LuaProofOutcome()

    /** Protected Lua callback raised an error. Process remains alive. */
    data class RuntimeFailure(
        override val stateId: Long?,
        override val generation: Long?,
        val diagnostic: String,
    ) : LuaProofOutcome()

    /** Allocator denied a request under protected execution. State remains closable. */
    data class MemoryFailure(
        override val stateId: Long,
        override val generation: Long,
        val diagnostic: String,
        val currentBytes: Long?,
        val peakBytes: Long?,
        val deniedAllocations: Long?,
        val bridgeBytes: Long?,
    ) : LuaProofOutcome()

    /** Instruction hook interrupted uncooperative pure-Lua execution. */
    data class Interrupted(
        override val stateId: Long,
        override val generation: Long,
        val diagnostic: String?,
        val elapsedNanos: Long?,
    ) : LuaProofOutcome()

    /** Operation token was cancelled. */
    data class Cancelled(
        override val stateId: Long,
        override val generation: Long,
        val operationId: Long,
    ) : LuaProofOutcome()

    /** Handle belongs to another state, is unknown, or ownership is invalid. */
    data class InvalidOwnership(
        override val stateId: Long?,
        override val generation: Long?,
        val diagnostic: String,
    ) : LuaProofOutcome()

    /** Generation mismatch or duplicate terminal completion. No Lua effect. */
    data class Stale(
        override val stateId: Long?,
        override val generation: Long?,
        val diagnostic: String,
    ) : LuaProofOutcome()

    /**
     * Per-state allocator snapshot. Records current, sampled peak, denied
     * count, terminal Lua bytes, and bridge-owned bytes separately so
     * teardown evidence does not overclaim released Lua memory. Returned by
     * the `snapshot` bridge operation for instrumentation.
     */
    data class Snapshot(
        override val stateId: Long,
        override val generation: Long,
        val currentBytes: Long,
        val peakBytes: Long,
        val deniedAllocations: Long,
        val bridgeBytes: Long,
        val elapsedNanos: Long?,
        val luaVersion: String?,
        val bindingVersion: String?,
        val topology: String?,
    ) : LuaProofOutcome()

    /** State was closed; late completion rejected idempotently. */
    data class Closed(
        override val stateId: Long,
        override val generation: Long,
    ) : LuaProofOutcome()
}