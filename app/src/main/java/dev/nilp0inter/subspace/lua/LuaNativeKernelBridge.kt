package dev.nilp0inter.subspace.lua

/**
 * Lazy native implementation of [LuaKernelBridge] backed by [LuaNativeKernel].
 *
 * The native library is loaded only when the first bridge method is invoked —
 * no ordinary application startup path creates an instance or calls
 * [ensureLoaded]. This keeps the kernel substrate invisible to production
 * channel registration, UI, and service lifecycle.
 *
 * Every method delegates to the JNI object and decodes the JSON outcome via
 * [LuaKernelOutcomeCodec]. Malformed or unknown JSON normalizes to
 * [LuaKernelOutcome.RuntimeFailure] rather than throwing. No native pointer or
 * Lua registry index is exposed across the bridge.
 *
 * State handles are assigned on the JVM side as opaque [LuaStateId] /
 * [LuaStateGeneration] pairs and passed to native code as primitive `Long`
 * values. The native side validates every operation against its owning state
 * generation.
 */
internal class LuaNativeKernelBridge : LuaKernelBridge {

    /**
     * Create a Lua kernel state. Assigns a fresh opaque [LuaStateId] and
     * initial [LuaStateGeneration], passes the config to native code, and
     * decodes the result.
     */
    override fun create(config: LuaKernelConfig): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = null,
                generation = null,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeCreate(
                config.memoryLimitBytes,
                config.hookInterval,
                config.instructionBudget,
                config.maxConcurrentTasks,
                config.maxTimerSlots,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = null,
                generation = null,
                diagnostic = "nativeCreate link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeLoad(
                handle.stateId.value,
                handle.generation.value,
                source,
                entrypoint,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeLoad link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun start(handle: LuaStateHandle): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeStart(
                handle.stateId.value,
                handle.generation.value,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeStart link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun resume(
        operation: LuaOperationHandle,
        success: Boolean,
        value: String,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = operation.stateHandle.stateId.value,
                generation = operation.stateHandle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeResume(
                operation.stateHandle.stateId.value,
                operation.stateHandle.generation.value,
                operation.coroutineId.value,
                operation.operationId.value,
                success,
                value,
                spawnAdmission,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = operation.stateHandle.stateId.value,
                generation = operation.stateHandle.generation.value,
                diagnostic = "nativeResume link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = operation.stateHandle.stateId.value,
                generation = operation.stateHandle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeCancel(
                operation.stateHandle.stateId.value,
                operation.stateHandle.generation.value,
                operation.coroutineId.value,
                operation.operationId.value,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = operation.stateHandle.stateId.value,
                generation = operation.stateHandle.generation.value,
                diagnostic = "nativeCancel link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeInterrupt(
                handle.stateId.value,
                handle.generation.value,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeInterrupt link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeSnapshot(
                handle.stateId.value,
                handle.generation.value,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeSnapshot link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun close(handle: LuaStateHandle): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeClose(
                handle.stateId.value,
                handle.generation.value,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeClose link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun loadProgramImage(
        handle: LuaStateHandle,
        entryPoint: String,
        sourceMap: Map<String, String>
    ): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val sourceMapJson = org.json.JSONObject(sourceMap).toString()
        val json = try {
            LuaNativeKernel.nativeLoadProgramImage(
                handle.stateId.value,
                handle.generation.value,
                sourceMapJson,
                entryPoint
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeLoadProgramImage link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun invokeStartupCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        config: LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (callbackHandle.stateHandle != handle) {
            return invalidCallbackOwnership(handle, callbackHandle)
        }
        val argumentsJson = when (val encoding = config.toJsonString()) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> {
                return LuaKernelOutcome.ValidationFailure(
                    stateId = handle.stateId.value,
                    generation = handle.generation.value,
                    diagnostic = encoding.diagnostic,
                )
            }
        }
        return invokeCallbackJson(handle, callbackHandle, argumentsJson, spawnAdmission)
    }

    override fun invokeCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (callbackHandle.stateHandle != handle) {
            return invalidCallbackOwnership(handle, callbackHandle)
        }
        val argumentsJson = when (val encoding = arguments.toJsonString()) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> {
                return LuaKernelOutcome.ValidationFailure(
                    stateId = handle.stateId.value,
                    generation = handle.generation.value,
                    diagnostic = encoding.diagnostic,
                )
            }
        }
        return invokeCallbackJson(handle, callbackHandle, argumentsJson, spawnAdmission)
    }

    override fun invokeInputCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        capturedAudioToken: String,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (callbackHandle.stateHandle != handle || callbackHandle.name != "handle_input") {
            return invalidCallbackOwnership(handle, callbackHandle)
        }
        val argumentsJson = when (val encoding = arguments.toJsonString()) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> {
                return LuaKernelOutcome.ValidationFailure(
                    stateId = handle.stateId.value,
                    generation = handle.generation.value,
                    diagnostic = encoding.diagnostic,
                )
            }
        }
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeInvokeInputCallback(
                handle.stateId.value,
                handle.generation.value,
                argumentsJson,
                capturedAudioToken,
                spawnAdmission,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeInvokeInputCallback link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun invokeSosCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (callbackHandle.stateHandle != handle || callbackHandle.name != "handle_sos") {
            return invalidCallbackOwnership(handle, callbackHandle)
        }
        val argumentsJson = when (val encoding = arguments.toJsonString()) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> {
                return LuaKernelOutcome.ValidationFailure(
                    stateId = handle.stateId.value,
                    generation = handle.generation.value,
                    diagnostic = encoding.diagnostic,
                )
            }
        }
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeInvokeSosCallback(
                handle.stateId.value,
                handle.generation.value,
                argumentsJson,
                spawnAdmission,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeInvokeSosCallback link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }
    private fun invalidCallbackOwnership(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
    ): LuaKernelOutcome.InvalidOwnership = LuaKernelOutcome.InvalidOwnership(
        stateId = handle.stateId.value,
        generation = handle.generation.value,
        diagnostic = "callback '${callbackHandle.name}' belongs to a different state handle",
    )

    private fun invokeCallbackJson(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        argumentsJson: String,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeInvokeCallback(
                handle.stateId.value,
                handle.generation.value,
                callbackHandle.name,
                argumentsJson,
                spawnAdmission,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeInvokeCallback link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun startCoroutine(
        handle: LuaStateHandle,
        coroutineId: LuaCoroutineId,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeStartCoroutine(
                handle.stateId.value,
                handle.generation.value,
                coroutineId.value,
                spawnAdmission,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeStartCoroutine link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    override fun claimHostOperation(handle: LuaStateHandle, requestId: Long): HostOperationClaim {
        if (!LuaNativeKernel.ensureLoaded()) return HostOperationClaim.Rejected("E_HOST_FAILURE")
        val json = try {
            LuaNativeKernel.nativeClaimHostOperation(
                handle.stateId.value,
                handle.generation.value,
                requestId,
            )
        } catch (e: UnsatisfiedLinkError) {
            return HostOperationClaim.Rejected("E_HOST_FAILURE")
        }
        return decodeClaim(json)
    }

    override fun setResourceContext(
        handle: LuaStateHandle,
        resourceContextJson: String,
    ): LuaKernelOutcome {
        if (!LuaNativeKernel.ensureLoaded()) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "subspace_lua_actor native library not available",
            )
        }
        val json = try {
            LuaNativeKernel.nativeSetResourceContext(
                handle.stateId.value,
                handle.generation.value,
                resourceContextJson,
            )
        } catch (e: UnsatisfiedLinkError) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = "nativeSetResourceContext link error: ${e.message}",
            )
        }
        return LuaKernelOutcomeCodec.decode(json)
    }

    /** Decode the typed claim result JSON into a [HostOperationClaim]. */
    private fun decodeClaim(json: String): HostOperationClaim {
        val root = try {
            org.json.JSONObject(json)
        } catch (_: Exception) {
            return HostOperationClaim.Rejected("E_HOST_FAILURE")
        }
        return when (root.optString("kind")) {
            "completed" -> {
                val kind = when (root.optString("hostOperationKind")) {
                    "TRANSCRIBE" -> HostOperationKind.TRANSCRIBE
                    "SYNTHESIZE" -> HostOperationKind.SYNTHESIZE
                    "PLAYBACK" -> HostOperationKind.PLAYBACK
                    "AUDIO_OPEN" -> HostOperationKind.AUDIO_OPEN
                    "AUDIO_EXPORT" -> HostOperationKind.AUDIO_EXPORT
                    "FS_MKDIR" -> HostOperationKind.FS_MKDIR
                    "FS_STAT" -> HostOperationKind.FS_STAT
                    "FS_LIST" -> HostOperationKind.FS_LIST
                    "FS_READ_TEXT" -> HostOperationKind.FS_READ_TEXT
                    "FS_WRITE_TEXT" -> HostOperationKind.FS_WRITE_TEXT
                    "FS_REMOVE" -> HostOperationKind.FS_REMOVE
                    "KEYBOARD_SEND_TEXT" -> HostOperationKind.KEYBOARD_SEND_TEXT
                    "KEYBOARD_SEND_KEY" -> HostOperationKind.KEYBOARD_SEND_KEY
                    else -> return HostOperationClaim.Rejected("E_HOST_FAILURE")
                }
                HostOperationClaim.Admitted(
                    requestId = root.optLong("requestId"),
                    kind = kind,
                    audioToken = if (root.has("audioToken")) root.optString("audioToken") else null,
                    text = if (root.has("text")) root.optString("text") else null,
                    language = if (root.has("language")) root.optString("language") else null,
                    voice = if (root.has("voice")) root.optString("voice") else null,
                    speed = root.optDouble("speed", 1.0),
                    delaySeconds = root.optDouble("delaySeconds", 0.0),
                    declarationId = if (root.has("declarationId")) root.optString("declarationId") else null,
                    mountToken = if (root.has("mountToken")) root.optString("mountToken") else null,
                    path = if (root.has("path")) root.optString("path") else null,
                    parents = root.optBoolean("parents", false),
                    limit = root.optLong("limit", 0),
                    cursor = if (root.has("cursor")) root.optString("cursor") else null,
                    maxBytes = root.optLong("maxBytes", 0),
                    mode = if (root.has("mode")) root.optString("mode") else null,
                    missingOk = root.optBoolean("missingOk", false),
                    format = if (root.has("format")) root.optString("format") else null,
                    profile = if (root.has("profile")) root.optString("profile") else null,
                    key = if (root.has("key")) root.optString("key") else null,
                )
            }
            "closed" -> HostOperationClaim.Rejected("E_CLOSED")
            "stale" -> HostOperationClaim.Rejected("E_STALE")
            "invalid_ownership" -> HostOperationClaim.Rejected("E_STALE")
            else -> HostOperationClaim.Rejected("E_HOST_FAILURE")
        }
    }
}