package dev.nilp0inter.subspace.lua

import org.json.JSONException
import org.json.JSONObject

/**
 * Strict decoder for the JSON outcome objects returned by the native proof
 * bridge. Malformed or unknown JSON normalizes to a
 * [LuaProofOutcome.RuntimeFailure] rather than throwing.
 *
 * Required `kind` values are mapped to [LuaProofOutcome] variants. Unknown
 * `kind` values, missing required fields, or malformed JSON all normalize to
 * [LuaProofOutcome.RuntimeFailure] with a diagnostic string.
 *
 * Optional fields: `stateId`, `generation`, `coroutineId`, `operationId`,
 * `value`, `diagnostic`, `currentBytes`, `peakBytes`, `deniedAllocations`,
 * `bridgeBytes`, `elapsedNanos`, `luaVersion`, `bindingVersion`, `topology`.
 */
internal object LuaProofOutcomeCodec {

    /**
     * Decode a JSON outcome string. Never throws for expected input;
     * malformed or unknown JSON returns [LuaProofOutcome.RuntimeFailure].
     */
    fun decode(json: String): LuaProofOutcome {
        val root: JSONObject = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return LuaProofOutcome.RuntimeFailure(
                stateId = null,
                generation = null,
                diagnostic = "malformed outcome json: ${e.message}",
            )
        } catch (e: Exception) {
            return LuaProofOutcome.RuntimeFailure(
                stateId = null,
                generation = null,
                diagnostic = "unexpected decode error: ${e.javaClass.simpleName}",
            )
        }

        val kind: String = try {
            root.getString("kind")
        } catch (e: JSONException) {
            return LuaProofOutcome.RuntimeFailure(
                stateId = optLong(root, "stateId"),
                generation = optLong(root, "generation"),
                diagnostic = "missing required 'kind' field",
            )
        }

        return when (kind) {
            "created" -> decodeCreated(root)
            "completed" -> decodeCompleted(root)
            "yielded" -> decodeYielded(root)
            "syntax_failure" -> decodeSyntaxFailure(root)
            "validation_failure" -> decodeValidationFailure(root)
            "runtime_failure" -> decodeRuntimeFailure(root)
            "memory_failure" -> decodeMemoryFailure(root)
            "interrupted" -> decodeInterrupted(root)
            "cancelled" -> decodeCancelled(root)
            "invalid_ownership" -> decodeInvalidOwnership(root)
            "stale" -> decodeStale(root)
            "closed" -> decodeClosed(root)
            else -> LuaProofOutcome.RuntimeFailure(
                stateId = optLong(root, "stateId"),
                generation = optLong(root, "generation"),
                diagnostic = "unknown outcome kind: $kind",
            )
        }
    }

    private fun decodeCreated(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "created: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "created: missing generation")
        val luaVersion = reqString(root, "luaVersion") ?: return failure(root, "created: missing luaVersion")
        val bindingVersion = reqString(root, "bindingVersion") ?: return failure(root, "created: missing bindingVersion")
        val topology = reqString(root, "topology") ?: return failure(root, "created: missing topology")
        return LuaProofOutcome.Created(
            stateId = stateId,
            generation = generation,
            luaVersion = luaVersion,
            bindingVersion = bindingVersion,
            topology = topology,
        )
    }

    private fun decodeCompleted(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "completed: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "completed: missing generation")
        // The native snapshot operation emits kind="completed" with an explicit
        // "operation":"snapshot" marker plus all four memory telemetry fields
        // (currentBytes, peakBytes, deniedAllocations, bridgeBytes). Normal
        // load/start/resume completions may also carry telemetry fields but
        // lack the marker. We decode marker-present as LuaProofOutcome.Snapshot
        // and marker-absent as LuaProofOutcome.Completed (retaining any
        // telemetry fields on Completed so they are not lost).
        val operation = optString(root, "operation")
        if (operation == "snapshot") {
            val currentBytes = reqLong(root, "currentBytes") ?: return failure(root, "snapshot: missing currentBytes")
            val peakBytes = reqLong(root, "peakBytes") ?: return failure(root, "snapshot: missing peakBytes")
            val deniedAllocations = reqLong(root, "deniedAllocations") ?: return failure(root, "snapshot: missing deniedAllocations")
            val bridgeBytes = reqLong(root, "bridgeBytes") ?: return failure(root, "snapshot: missing bridgeBytes")
            return LuaProofOutcome.Snapshot(
                stateId = stateId,
                generation = generation,
                currentBytes = currentBytes,
                peakBytes = peakBytes,
                deniedAllocations = deniedAllocations,
                bridgeBytes = bridgeBytes,
                elapsedNanos = optLong(root, "elapsedNanos"),
                luaVersion = optString(root, "luaVersion"),
                bindingVersion = optString(root, "bindingVersion"),
                topology = optString(root, "topology"),
            )
        }
        return LuaProofOutcome.Completed(
            stateId = stateId,
            generation = generation,
            coroutineId = optLong(root, "coroutineId"),
            value = optString(root, "value"),
            elapsedNanos = optLong(root, "elapsedNanos"),
            currentBytes = optLong(root, "currentBytes"),
            peakBytes = optLong(root, "peakBytes"),
            deniedAllocations = optLong(root, "deniedAllocations"),
            bridgeBytes = optLong(root, "bridgeBytes"),
            luaVersion = optString(root, "luaVersion"),
            bindingVersion = optString(root, "bindingVersion"),
            topology = optString(root, "topology"),
        )
    }

    private fun decodeYielded(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "yielded: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "yielded: missing generation")
        val coroutineId = reqLong(root, "coroutineId") ?: return failure(root, "yielded: missing coroutineId")
        val operationId = reqLong(root, "operationId") ?: return failure(root, "yielded: missing operationId")
        return LuaProofOutcome.Yielded(
            stateId = stateId,
            generation = generation,
            coroutineId = coroutineId,
            operationId = operationId,
            value = optString(root, "value"),
        )
    }

    private fun decodeSyntaxFailure(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "syntax_failure: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "syntax_failure: missing generation")
        val diagnostic = reqString(root, "diagnostic") ?: return failure(root, "syntax_failure: missing diagnostic")
        return LuaProofOutcome.SyntaxFailure(
            stateId = stateId,
            generation = generation,
            diagnostic = diagnostic,
        )
    }

    private fun decodeValidationFailure(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "validation_failure: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "validation_failure: missing generation")
        val diagnostic = reqString(root, "diagnostic") ?: return failure(root, "validation_failure: missing diagnostic")
        return LuaProofOutcome.ValidationFailure(
            stateId = stateId,
            generation = generation,
            diagnostic = diagnostic,
        )
    }

    private fun decodeRuntimeFailure(root: JSONObject): LuaProofOutcome {
        return LuaProofOutcome.RuntimeFailure(
            stateId = optLong(root, "stateId"),
            generation = optLong(root, "generation"),
            diagnostic = reqString(root, "diagnostic") ?: "runtime failure (no diagnostic)",
        )
    }

    private fun decodeMemoryFailure(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "memory_failure: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "memory_failure: missing generation")
        val diagnostic = reqString(root, "diagnostic") ?: return failure(root, "memory_failure: missing diagnostic")
        return LuaProofOutcome.MemoryFailure(
            stateId = stateId,
            generation = generation,
            diagnostic = diagnostic,
            currentBytes = optLong(root, "currentBytes"),
            peakBytes = optLong(root, "peakBytes"),
            deniedAllocations = optLong(root, "deniedAllocations"),
            bridgeBytes = optLong(root, "bridgeBytes"),
        )
    }


    private fun decodeInterrupted(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "interrupted: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "interrupted: missing generation")
        return LuaProofOutcome.Interrupted(
            stateId = stateId,
            generation = generation,
            diagnostic = optString(root, "diagnostic"),
            elapsedNanos = optLong(root, "elapsedNanos"),
        )
    }

    private fun decodeCancelled(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "cancelled: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "cancelled: missing generation")
        val operationId = reqLong(root, "operationId") ?: return failure(root, "cancelled: missing operationId")
        return LuaProofOutcome.Cancelled(
            stateId = stateId,
            generation = generation,
            operationId = operationId,
        )
    }

    private fun decodeInvalidOwnership(root: JSONObject): LuaProofOutcome {
        return LuaProofOutcome.InvalidOwnership(
            stateId = optLong(root, "stateId"),
            generation = optLong(root, "generation"),
            diagnostic = reqString(root, "diagnostic") ?: "invalid ownership (no diagnostic)",
        )
    }

    private fun decodeStale(root: JSONObject): LuaProofOutcome {
        return LuaProofOutcome.Stale(
            stateId = optLong(root, "stateId"),
            generation = optLong(root, "generation"),
            diagnostic = reqString(root, "diagnostic") ?: "stale (no diagnostic)",
        )
    }

    private fun decodeClosed(root: JSONObject): LuaProofOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "closed: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "closed: missing generation")
        return LuaProofOutcome.Closed(
            stateId = stateId,
            generation = generation,
        )
    }

    private fun failure(root: JSONObject, reason: String): LuaProofOutcome =
        LuaProofOutcome.RuntimeFailure(
            stateId = optLong(root, "stateId"),
            generation = optLong(root, "generation"),
            diagnostic = reason,
        )

    private fun optLong(root: JSONObject, field: String): Long? {
        if (!root.has(field) || root.isNull(field)) return null
        // Strict: accept only integral JSON Number, not quoted strings or
        // fractional values. org.json getLong() coerces "42" to 42L and
        // truncates 41.5 to 41; reject both to preserve type safety.
        val raw = root.opt(field)
        if (raw !is Number) return null
        if (raw is Long || raw is Int || raw is Short || raw is Byte) {
            return raw.toLong()
        }
        // Double/Float: reject non-integral values.
        val d = raw.toDouble()
        if (d != Math.floor(d) || d.isInfinite() || d.isNaN()) return null
        return d.toLong()
    }

    private fun reqLong(root: JSONObject, field: String): Long? {
        if (!root.has(field) || root.isNull(field)) return null
        val raw = root.opt(field)
        if (raw !is Number) return null
        if (raw is Long || raw is Int || raw is Short || raw is Byte) {
            return raw.toLong()
        }
        val d = raw.toDouble()
        if (d != Math.floor(d) || d.isInfinite() || d.isNaN()) return null
        return d.toLong()
    }

    private fun optString(root: JSONObject, field: String): String? {
        if (!root.has(field) || root.isNull(field)) return null
        // Strict: accept only JSON String, not coerced numbers.
        val raw = root.opt(field)
        if (raw !is String) return null
        return raw
    }

    private fun reqString(root: JSONObject, field: String): String? {
        if (!root.has(field) || root.isNull(field)) return null
        val raw = root.opt(field)
        if (raw !is String) return null
        return raw
    }

}