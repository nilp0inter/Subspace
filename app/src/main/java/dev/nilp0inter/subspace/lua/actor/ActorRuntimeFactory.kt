package dev.nilp0inter.subspace.lua.actor

import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.RevocableChannelCapabilityScope
import dev.nilp0inter.subspace.lua.LuaKernelBridge
import kotlinx.coroutines.CoroutineScope

/**
 * Internal factory for constructing [ActorRuntime] instances.
 *
 * This factory is an internal mechanism and is absent from application
 * startup unless a future provider explicitly constructs a Lua actor. It
 * does not register a provider, does not participate in application startup,
 * and does not alter existing channel behavior. No ordinary application
 * startup path references this factory.
 *
 * A Lua actor is created only when a future package/API change supplies a
 * Lua-backed provider for a channel instance. The factory does not own
 * Android lifecycle, does not create threads, and does not execute outside
 * the generation's invocation gate.
 */
internal object ActorRuntimeFactory {

    @Volatile
    private var createAttempted: Boolean = false

    /**
     * True if [create] has been called at least once. Tests use this to
     * verify that no ordinary application startup path triggers actor
     * construction. Reset via [resetForTest].
     *
     * This is a test-visible boolean event, not a count: multiple calls
     * produce the same `true` value. It parallels
     * [dev.nilp0inter.subspace.lua.LuaNativeKernel.isLoadAttempted].
     */
    val isCreateAttempted: Boolean
        get() = createAttempted

    /**
     * Reset the attempt flag for testing. Allows instrumentation tests to
     * verify lazy construction behavior in isolation. Not called by
     * production code.
     */
    fun resetForTest() {
        createAttempted = false
    }

    /**
     * Create one [ActorRuntime] bounded by the given generation.
     *
     * @param scope the authoritative [CapabilityScopeIdentity] for this
     *   actor's generation.
     * @param bridge the promoted [LuaKernelBridge] backed by the single
     *   internal Lua kernel.
     * @param gate the injected [ActorGenerationGate] admitting host events
     *   through the generation's invocation boundary.
     * @param policy the internal configurable resource policy.
     * @param capabilityScope the revocable capability scope for this
     *   generation, or null if no capabilities are declared.
     * @param parentScope the generation-owned parent coroutine scope. Must
     *   itself be lifecycle-owned by the host.
     */
    fun create(
        scope: CapabilityScopeIdentity,
        bridge: LuaKernelBridge,
        gate: ActorGenerationGate,
        policy: ActorPolicy,
        capabilityScope: RevocableChannelCapabilityScope?,
        parentScope: CoroutineScope,
    ): ActorRuntime {
        createAttempted = true
        return ActorRuntime(
            scope = scope,
            bridge = bridge,
            gate = gate,
            policy = policy,
            capabilityScope = capabilityScope,
            parentScope = parentScope,
        )
    }
}