package dev.nilp0inter.subspace.lua

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                "[\"subspace.channel\",\"subspace.log\",\"subspace.playback\",\"subspace.runtime\",\"subspace.synthesis\",\"subspace.transcription\"]",
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
}
