package dev.nilp0inter.subspace.lua

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
