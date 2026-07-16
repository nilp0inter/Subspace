package dev.nilp0inter.subspace.lua

/**
 * Composition seam for the proof Lua substrate. Instrumentation entrypoints
 * use this factory to explicitly create a [LuaProofBridge] instance. No
 * ordinary application startup path references this object, ensuring the
 * native library is loaded only on explicit proof bridge use.
 *
 * Tests can verify lazy loading behavior by checking
 * [LuaProofNative.isLoadAttempted] before and after bridge creation/invocation.
 * The factory itself does NOT load the native library — only
 * [LuaProofNativeBridge] calls [LuaProofNative.ensureLoaded] on first method
 * invocation.
 */
internal object LuaProofBridgeFactory {

    /**
     * Create a [LuaProofBridge] backed by the lazy native implementation.
     * The native library is NOT loaded at construction time; it is loaded on
     * the first bridge method invocation via [LuaProofNative.ensureLoaded].
     */
    fun create(): LuaProofBridge = LuaProofNativeBridge()
}