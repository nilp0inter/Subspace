package dev.nilp0inter.subspace.lua

/**
 * JNI object exporting the Lua kernel native methods. The native library
 * `subspace_lua_actor` is loaded lazily — only when [ensureLoaded] is called
 * by [LuaNativeKernelBridge] on explicit kernel bridge use. No ordinary
 * application startup path references this object.
 *
 * JNI method signatures match the shared contract exactly:
 *
 * - `nativeCreate(memoryLimitBytes: Long, hookInterval: Int, instructionBudget: Long): String`
 * - `nativeLoad(stateId: Long, generation: Long, source: String, entrypoint: String): String`
 * - `nativeStart(stateId: Long, generation: Long): String`
 * - `nativeResume(stateId: Long, generation: Long, operationId: Long, success: Boolean, value: String): String`
 * - `nativeCancel(stateId: Long, generation: Long, operationId: Long): String`
 * - `nativeInterrupt(stateId: Long, generation: Long): String`
 * - `nativeSnapshot(stateId: Long, generation: Long): String`
 * - `nativeClose(stateId: Long, generation: Long): String`
 *
 * Every JNI function returns a JSON object and must not throw for expected
 * input. No native pointer or Lua registry index is exposed.
 */
internal object LuaNativeKernel {
    private const val NATIVE_LIB_NAME = "subspace_lua_actor"

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadAttempted: Boolean = false

    /**
     * Attempt to load the native library. Returns true on success. Called only
     * by [LuaNativeKernelBridge] on explicit kernel bridge use — never during
     * ordinary application startup.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loadAttempted) return loaded
        loadAttempted = true
        loaded = try {
            System.loadLibrary(NATIVE_LIB_NAME)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
        return loaded
    }

    /** True if [ensureLoaded] has been called at least once. Tests use this to verify no ordinary startup path triggers native loading. */
    val isLoadAttempted: Boolean get() = loadAttempted

    /** True if the native library was successfully loaded. */
    val isLoaded: Boolean get() = loaded

    /**
     * Reset the load state for testing. Allows instrumentation tests to verify
     * lazy loading behavior in isolation. Not called by production code.
     */
    @Synchronized
    fun resetForTest() {
        loaded = false
        loadAttempted = false
    }

    external fun nativeCreate(
        memoryLimitBytes: Long,
        hookInterval: Int,
        instructionBudget: Long,
        maxConcurrentTasks: Int,
        maxTimerSlots: Int,
    ): String

    external fun nativeLoad(
        stateId: Long,
        generation: Long,
        source: String,
        entrypoint: String,
    ): String

    external fun nativeStart(
        stateId: Long,
        generation: Long,
    ): String

    external fun nativeResume(
        stateId: Long,
        generation: Long,
        coroutineId: Long,
        operationId: Long,
        success: Boolean,
        value: String,
        spawnAdmission: LuaSpawnAdmission,
    ): String

    external fun nativeCancel(
        stateId: Long,
        generation: Long,
        coroutineId: Long,
        operationId: Long,
    ): String

    external fun nativeInterrupt(
        stateId: Long,
        generation: Long,
    ): String

    external fun nativeSnapshot(
        stateId: Long,
        generation: Long,
    ): String

    external fun nativeClose(
        stateId: Long,
        generation: Long,
    ): String

    external fun nativeLoadProgramImage(
        stateId: Long,
        generation: Long,
        sourceMapJson: String,
        entryPoint: String,
    ): String

    external fun nativeInvokeCallback(
        stateId: Long,
        generation: Long,
        callbackName: String,
        argumentsJson: String,
        spawnAdmission: LuaSpawnAdmission,
    ): String

    external fun nativeStartCoroutine(
        stateId: Long,
        generation: Long,
        coroutineId: Long,
        spawnAdmission: LuaSpawnAdmission,
    ): String
}