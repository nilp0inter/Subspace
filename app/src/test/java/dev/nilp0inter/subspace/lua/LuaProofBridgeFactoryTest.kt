package dev.nilp0inter.subspace.lua

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaProofBridgeFactoryTest {
    @Before
    fun resetNativeLoadState() {
        LuaProofNative.resetForTest()
    }

    @After
    fun restoreNativeLoadState() {
        LuaProofNative.resetForTest()
    }

    @Test
    fun `proof composition defers native loading until an explicit bridge operation`() {
        assertFalse("test starts before any explicit proof use", LuaProofNative.isLoadAttempted)

        val bridge = LuaProofBridgeFactory.create()

        assertFalse(
            "constructing proof composition must not load native code during ordinary startup",
            LuaProofNative.isLoadAttempted,
        )

        bridge.create(
            LuaProofConfig(
                topology = LuaBridgeTopology.JvmOwned,
                memoryLimitBytes = 4_096,
                hookInterval = 100,
                instructionBudget = 10_000,
            ),
        )

        assertTrue(
            "an explicit bridge operation must be the transition that attempts native loading",
            LuaProofNative.isLoadAttempted,
        )
    }
}
