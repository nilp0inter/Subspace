package dev.nilp0inter.subspace.lua

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class LuaNativeKernelBridgeTest {
    @Before
    fun resetNativeLoadState() {
        LuaNativeKernel.resetForTest()
    }

    @After
    fun restoreNativeLoadState() {
        LuaNativeKernel.resetForTest()
    }

    @Test
    fun `native bridge construction defers loading until an explicit kernel operation`() {
        assertFalse("test starts before an explicit kernel use", LuaNativeKernel.isLoadAttempted)

        val bridge: LuaKernelBridge = LuaNativeKernelBridge()

        assertFalse(
            "constructing the kernel bridge must not load native code during ordinary startup",
            LuaNativeKernel.isLoadAttempted,
        )

        bridge.create(
            LuaKernelConfig(
                memoryLimitBytes = 4_096,
                hookInterval = 100,
                instructionBudget = 10_000,
            ),
        )

        assertTrue(
            "an explicit kernel operation must be the transition that attempts native loading",
            LuaNativeKernel.isLoadAttempted,
        )
    }

    @Test
    fun `native bridge exposes only approved modules and absent require has no side effects`() {
        val bridge: LuaKernelBridge = LuaNativeKernelBridge()
        val createdOutcome = bridge.create(
            LuaKernelConfig(
                memoryLimitBytes = 4 * 1024 * 1024,
                hookInterval = 100,
                instructionBudget = 10_000,
            ),
        )
        assumeTrue("native kernel unavailable in this JVM test environment: $createdOutcome", createdOutcome is LuaKernelOutcome.Created)
        val created = createdOutcome as LuaKernelOutcome.Created
        val handle = LuaStateHandle(
            stateId = LuaStateId(created.stateId),
            generation = LuaStateGeneration(created.generation),
        )
        try {
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local function keys(value)
                          local result = {}
                          for key in pairs(value) do result[#result + 1] = key end
                          table.sort(result)
                          return result
                        end
                        local preloaded = keys(subspace._preloaded)
                        local globals = keys(_G)
                        local loadedBefore = keys(subspace._modules)
                        local ok, errorValue = pcall(require, "missing.native.namespace")
                        local loadedAfter = keys(subspace._modules)
                        local forbidden = {}
                        local roots = {
                          "http", "https", "filesystem", "fs", "file", "path", "lfs",
                          "socket", "tcp", "udp", "net", "network", "dns", "websocket",
                          "event", "events", "event_loop", "eventloop", "uv", "async",
                          "package", "persistent", "state", "storage", "database", "db",
                          "sqlite", "os", "io", "ffi", "debug",
                        }
                        local function inspect(names, source)
                          for _, name in ipairs(names) do
                            for _, root in ipairs(roots) do
                              if name == root or string.sub(name, 1, #root + 1) == root .. "." then
                                forbidden[#forbidden + 1] = source .. ":" .. name
                              end
                            end
                          end
                        end
                        inspect(preloaded, "preload")
                        inspect(globals, "global")
                        inspect(loadedAfter, "loaded")
                        return {
                          startup = function()
                            return {
                              preloaded = preloaded,
                              globals = globals,
                              loadedBefore = loadedBefore,
                              loadedAfter = loadedAfter,
                              forbidden = forbidden,
                              missingOk = ok,
                              missingError = tostring(errorValue),
                            }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)

            val startup = bridge.invokeCallback(
                handle,
                LuaCallbackHandle(handle, "startup"),
                LuaValue.Nil,
            ) as? LuaKernelOutcome.Completed ?: error("startup callback failed")
            val result = org.json.JSONObject(startup.value ?: error("startup returned no value"))
            assertEquals(
                "[\"subspace.audio\",\"subspace.channel\",\"subspace.fs\",\"subspace.keyboard_output\",\"subspace.log\",\"subspace.playback\",\"subspace.runtime\",\"subspace.synthesis\",\"subspace.transcription\"]",
                result.getJSONArray("preloaded").toString(),
            )
            assertEquals(0, result.getJSONArray("forbidden").length())
            assertEquals(result.getJSONArray("loadedBefore").toString(), result.getJSONArray("loadedAfter").toString())
            assertFalse(result.getBoolean("missingOk"))
            assertTrue(result.getString("missingError").contains("E_MODULE_NOT_FOUND"))
        } finally {
            bridge.close(handle)
        }
    }
    private fun keyboardBridge(): Pair<LuaKernelBridge, LuaStateHandle>? {
        val bridge: LuaKernelBridge = LuaNativeKernelBridge()
        val createdOutcome = bridge.create(
            LuaKernelConfig(
                memoryLimitBytes = 4 * 1024 * 1024,
                hookInterval = 100,
                instructionBudget = 10_000_000,
            ),
        )
        assumeTrue(
            "native kernel unavailable in this JVM test environment: $createdOutcome",
            createdOutcome is LuaKernelOutcome.Created,
        )
        val created = createdOutcome as LuaKernelOutcome.Created
        val handle = LuaStateHandle(
            stateId = LuaStateId(created.stateId),
            generation = LuaStateGeneration(created.generation),
        )
        return bridge to handle
    }

    private fun captureEvent(): LuaValue = LuaValue.Map(
        mapOf(
            "metadata" to LuaValue.Map(
                mapOf(
                    "duration_ms" to LuaValue.Number(1.0),
                    "sample_rate" to LuaValue.Number(16000.0),
                    "channels" to LuaValue.Number(1.0),
                    "pcm_bytes" to LuaValue.Number(32.0),
                ),
            ),
        ),
    )

    @Test
    fun `keyboard send_text yields opaque request and claims typed payload exactly once`() {
        val (bridge, handle) = keyboardBridge() ?: return
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local kb = require("subspace.keyboard_output")
                        return {
                          startup = function() end,
                          handle_input = function(event)
                            local r, e = kb.send_text({ text = "bridge-secret-text", profile = "linux:us" })
                            if not r then return { error = { code = e.error, detail = "send" } } end
                            if r.status ~= "delivered" then
                              return { error = { code = "STATUS", detail = tostring(r.status) } }
                            end
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val yielded = bridge.invokeInputCallback(
                handle,
                LuaCallbackHandle(handle, "handle_input"),
                captureEvent(),
                "tok",
            ) as? LuaKernelOutcome.Yielded ?: error("expected yielded keyboard request")
            val requestId = (yielded.value ?: error("missing request id")).toLong()
            assertEquals(yielded.value, requestId.toString())
            assertFalse("yield label leaks text", yielded.value!!.contains("bridge-secret-text"))
            assertFalse("yield label leaks profile", yielded.value!!.contains("linux:us"))

            val claim = bridge.claimHostOperation(handle, requestId) as? HostOperationClaim.Admitted
                ?: error("expected admitted claim")
            assertEquals(HostOperationKind.KEYBOARD_SEND_TEXT, claim.kind)
            assertEquals("bridge-secret-text", claim.text)
            assertEquals("linux:us", claim.profile)
            assertNull(claim.key)

            val duplicate = bridge.claimHostOperation(handle, requestId)
            assertTrue("duplicate claim must be rejected", duplicate is HostOperationClaim.Rejected)

            val operation = LuaOperationHandle(
                stateHandle = handle,
                coroutineId = LuaCoroutineId(yielded.coroutineId),
                operationId = LuaOperationId(yielded.operationId),
            )
            val completed = bridge.resume(operation, true, """{"status":"delivered"}""")
                as? LuaKernelOutcome.Completed ?: error("expected completed resume")
            assertEquals("""{"ok":true}""", org.json.JSONObject(completed.value!!).toString())
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `keyboard send_key claim carries semantic key and passes through non-delivered outcomes`() {
        val (bridge, handle) = keyboardBridge() ?: return
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local kb = require("subspace.keyboard_output")
                        return {
                          startup = function() end,
                          handle_input = function(event)
                            local r, e = kb.send_key({ key = "enter", profile = "mac:iso" })
                            if not r then return { error = { code = e.error, detail = "send" } } end
                            return { status = r.status, reason = r.reason }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val yielded = bridge.invokeInputCallback(
                handle,
                LuaCallbackHandle(handle, "handle_input"),
                captureEvent(),
                "tok",
            ) as? LuaKernelOutcome.Yielded ?: error("expected yielded keyboard request")
            val requestId = (yielded.value ?: error("missing request id")).toLong()
            val claim = bridge.claimHostOperation(handle, requestId) as? HostOperationClaim.Admitted
                ?: error("expected admitted claim")
            assertEquals(HostOperationKind.KEYBOARD_SEND_KEY, claim.kind)
            assertEquals("enter", claim.key)
            assertEquals("mac:iso", claim.profile)
            assertNull(claim.text)

            val operation = LuaOperationHandle(
                stateHandle = handle,
                coroutineId = LuaCoroutineId(yielded.coroutineId),
                operationId = LuaOperationId(yielded.operationId),
            )
            val completed = bridge.resume(
                operation,
                true,
                """{"status":"indeterminate","reason":"ack-lost"}""",
            ) as? LuaKernelOutcome.Completed ?: error("expected completed resume")
            val result = org.json.JSONObject(completed.value!!)
            assertEquals("indeterminate", result.getString("status"))
            assertEquals("ack-lost", result.getString("reason"))
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `keyboard output without declared capability fails inline without yielding`() {
        val (bridge, handle) = keyboardBridge() ?: return
        try {
            val rc = bridge.setResourceContext(handle, "{}")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local kb = require("subspace.keyboard_output")
                        return {
                          startup = function() end,
                          handle_input = function(event)
                            local r, e = kb.send_text({ text = "x", profile = "p" })
                            return { error = { code = r == nil and e.error or "ok", detail = "send" } }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val completed = bridge.invokeInputCallback(
                handle,
                LuaCallbackHandle(handle, "handle_input"),
                captureEvent(),
                "tok",
            ) as? LuaKernelOutcome.Completed ?: error("undeclared capability must fail inline, not yield")
            val result = org.json.JSONObject(completed.value!!)
            assertEquals("E_CAPABILITY_UNDECLARED", result.getJSONObject("error").getString("code"))
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `sos callback yields keyboard operation through the typed broker`() {
        val (bridge, handle) = keyboardBridge() ?: return
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local kb = require("subspace.keyboard_output")
                        return {
                          startup = function() end,
                          handle_sos = function(event)
                            local r, e = kb.send_key({ key = "enter", profile = "sos:profile" })
                            if not r then return { error = { code = e.error, detail = "sos" } } end
                            if r.status ~= "delivered" then
                              return { error = { code = "STATUS", detail = tostring(r.status) } }
                            end
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val yielded = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_sos"),
                LuaValue.Nil,
            ) as? LuaKernelOutcome.Yielded ?: error("expected yielded SOS keyboard request")
            val requestId = (yielded.value ?: error("missing request id")).toLong()
            val claim = bridge.claimHostOperation(handle, requestId) as? HostOperationClaim.Admitted
                ?: error("expected admitted claim")
            assertEquals(HostOperationKind.KEYBOARD_SEND_KEY, claim.kind)
            assertEquals("enter", claim.key)
            assertEquals("sos:profile", claim.profile)

            val operation = LuaOperationHandle(
                stateHandle = handle,
                coroutineId = LuaCoroutineId(yielded.coroutineId),
                operationId = LuaOperationId(yielded.operationId),
            )
            val completed = bridge.resume(operation, true, """{"status":"delivered"}""")
                as? LuaKernelOutcome.Completed ?: error("expected completed resume")
            assertEquals("""{"ok":true}""", org.json.JSONObject(completed.value!!).toString())
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `sos callback that never yields completes in one slice like the synchronous path`() {
        val (bridge, handle) = keyboardBridge() ?: return
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        return {
                          startup = function() end,
                          handle_sos = function(event)
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val completed = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_sos"),
                LuaValue.Nil,
            ) as? LuaKernelOutcome.Completed ?: error("non-yielding SOS must complete synchronously")
            assertEquals("""{"ok":true}""", org.json.JSONObject(completed.value!!).toString())
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `sos callback rejects foreign callback handles and raw yields`() {
        val (bridge, handle) = keyboardBridge() ?: return
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        return {
                          startup = function() end,
                          handle_input = function(event) return { ok = true } end,
                          handle_sos = function(event)
                            coroutine.yield("raw")
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val foreign = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_input"),
                LuaValue.Nil,
            )
            assertTrue(
                "foreign callback handle must be rejected: $foreign",
                foreign is LuaKernelOutcome.InvalidOwnership,
            )
            val rawYield = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_sos"),
                LuaValue.Nil,
            )
            assertTrue(
                "raw yield must fail the SOS owner: $rawYield",
                rawYield is LuaKernelOutcome.RuntimeFailure,
            )
        } finally {
            bridge.close(handle)
        }
    }
}
