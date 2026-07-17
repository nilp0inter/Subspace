package dev.nilp0inter.subspace.lua

/**
 * Owner-bearing handle for a Lua kernel state. Pairs the opaque [stateId] with
 * the current [generation] so that stale completions can be rejected without
 * entering Lua. No native pointer or Lua registry index is stored.
 */
internal data class LuaStateHandle(
    val stateId: LuaStateId,
    val generation: LuaStateGeneration,
)

/**
 * Owner-bearing handle for a Lua kernel coroutine. Carries its owning
 * [stateHandle] so the bridge can reject foreign-state coroutine handles.
 */
internal data class LuaCoroutineHandle(
    val stateHandle: LuaStateHandle,
    val coroutineId: LuaCoroutineId,
)

/**
 * Owner-bearing handle for a suspended kernel operation token. Carries its
 * owning [stateHandle] and the [coroutineId] that yielded it. Each token
 * accepts at most one terminal resume, cancellation, or close outcome.
 */
internal data class LuaOperationHandle(
    val stateHandle: LuaStateHandle,
    val coroutineId: LuaCoroutineId,
    val operationId: LuaOperationId,
)