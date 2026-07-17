package dev.nilp0inter.subspace.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaKernelOutcomeCodecTest {
    @Test
    fun `decodes every normalized outcome kind with native evidence`() {
        val cases = listOf(
            DecodeCase(
                name = "completed coroutine result and latency",
                json = """{"kind":"completed","stateId":41,"generation":3,"coroutineId":17,"value":"result","elapsedNanos":9123,"currentBytes":128,"peakBytes":512,"deniedAllocations":1,"bridgeBytes":32,"luaVersion":"Lua 5.4.8","bindingVersion":"mlua 0.12.0"}""",
                expected = LuaKernelOutcome.Completed(41, 3, 17, "result", 9123, 128, 512, 1, 32, "Lua 5.4.8", "mlua 0.12.0", null),
            ),
            DecodeCase(
                name = "yielded operation owner",
                json = """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"value":"network"}""",
                expected = LuaKernelOutcome.Yielded(41, 3, 17, 29, "network"),
            ),
            DecodeCase(
                name = "syntax failure diagnostic",
                json = """{"kind":"syntax_failure","stateId":41,"generation":3,"diagnostic":"line 7: unexpected end"}""",
                expected = LuaKernelOutcome.SyntaxFailure(41, 3, "line 7: unexpected end"),
            ),
            DecodeCase(
                name = "validation failure diagnostic",
                json = """{"kind":"validation_failure","stateId":41,"generation":3,"diagnostic":"entrypoint is not a function"}""",
                expected = LuaKernelOutcome.ValidationFailure(41, 3, "entrypoint is not a function"),
            ),
            DecodeCase(
                name = "runtime failure ownership and diagnostic",
                json = """{"kind":"runtime_failure","stateId":41,"generation":3,"diagnostic":"callback failed"}""",
                expected = LuaKernelOutcome.RuntimeFailure(41, 3, "callback failed"),
            ),
            DecodeCase(
                name = "memory failure allocator and bridge accounting",
                json = """{"kind":"memory_failure","stateId":41,"generation":3,"diagnostic":"allocator denied request","currentBytes":256,"peakBytes":1024,"deniedAllocations":2,"bridgeBytes":64}""",
                expected = LuaKernelOutcome.MemoryFailure(41, 3, "allocator denied request", 256, 1024, 2, 64),
            ),
            DecodeCase(
                name = "interrupted diagnostic and latency",
                json = """{"kind":"interrupted","stateId":41,"generation":3,"diagnostic":"instruction budget exceeded","elapsedNanos":4567}""",
                expected = LuaKernelOutcome.Interrupted(41, 3, "instruction budget exceeded", 4567),
            ),
            DecodeCase(
                name = "cancelled operation",
                json = """{"kind":"cancelled","stateId":41,"generation":3,"operationId":29}""",
                expected = LuaKernelOutcome.Cancelled(41, 3, 29),
            ),
            DecodeCase(
                name = "invalid ownership diagnostic",
                json = """{"kind":"invalid_ownership","stateId":41,"generation":3,"diagnostic":"operation belongs to state 42"}""",
                expected = LuaKernelOutcome.InvalidOwnership(41, 3, "operation belongs to state 42"),
            ),
            DecodeCase(
                name = "stale terminal completion diagnostic",
                json = """{"kind":"stale","stateId":41,"generation":3,"diagnostic":"operation already completed"}""",
                expected = LuaKernelOutcome.Stale(41, 3, "operation already completed"),
            ),
            DecodeCase(
                name = "snapshot keeps Lua and bridge accounting separate",
                json = """{"kind":"completed","operation":"snapshot","stateId":41,"generation":3,"currentBytes":128,"peakBytes":1024,"deniedAllocations":2,"bridgeBytes":64,"elapsedNanos":4567,"luaVersion":"Lua 5.4.8","bindingVersion":"mlua 0.12.0"}""",
                expected = LuaKernelOutcome.Snapshot(41, 3, 128, 1024, 2, 64, 4567, "Lua 5.4.8", "mlua 0.12.0", null),
            ),
            DecodeCase(
                name = "closed state",
                json = """{"kind":"closed","stateId":41,"generation":3}""",
                expected = LuaKernelOutcome.Closed(41, 3),
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, LuaKernelOutcomeCodec.decode(case.json))
        }
    }

    @Test
    fun `decodes created identity and version evidence`() {
        val outcome = LuaKernelOutcomeCodec.decode(
            """{"kind":"created","stateId":41,"generation":3,"luaVersion":"Lua 5.4.8","bindingVersion":"mlua 0.12.0","topology":"kernel"}""",
        )

        assertTrue("created outcome must retain its concrete kind: $outcome", outcome is LuaKernelOutcome.Created)
        outcome as LuaKernelOutcome.Created
        assertEquals(41, outcome.stateId)
        assertEquals(3, outcome.generation)
        assertEquals("Lua 5.4.8", outcome.luaVersion)
        assertEquals("mlua 0.12.0", outcome.bindingVersion)
    }

    @Test
    fun `malformed unknown missing and type-invalid native outcomes normalize to runtime failure`() {
        val cases = listOf(
            "malformed json" to "not json",
            "unknown kind" to """{"kind":"future_kind","stateId":41,"generation":3}""",
            "missing yielded operation id" to """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17}""",
            "quoted numeric state id" to """{"kind":"completed","stateId":"41","generation":3}""",
            "fractional state id" to """{"kind":"completed","stateId":41.5,"generation":3}""",
        )

        cases.forEach { (name, json) ->
            val outcome = LuaKernelOutcomeCodec.decode(json)
            assertTrue("$name must not escape JSON decoding: $outcome", outcome is LuaKernelOutcome.RuntimeFailure)
        }
    }

    private data class DecodeCase(
        val name: String,
        val json: String,
        val expected: LuaKernelOutcome,
    )
}
