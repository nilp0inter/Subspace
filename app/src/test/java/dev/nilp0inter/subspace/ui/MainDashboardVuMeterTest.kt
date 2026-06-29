package dev.nilp0inter.subspace.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the dashboard's VU meter presence and level-reflection behavior.
 *
 * The `MainDashboardScreen` Composable itself needs Compose UI test infra (robolectric /
 * androidTest), which this repo does not currently ship. To keep the dashboard's meter-rendering
 * contract verifiable on plain JVM, the decision is extracted into [dashboardVuMeterState]
 * (a pure predicate the Composable uses to gate the meter). These tests pin its behavior so
 * regressions in `main-device-dashboard` delta spec scenarios are caught without device/UI tests.
 *
 * Maps to `main-device-dashboard` (delta) spec scenarios:
 * - Dashboard shows the meter while capturing
 * - Dashboard omits the meter (no reserved space) while idle
 * - Meter reflects any talk mode (driven by the unified capture signal, no per-mode wiring)
 */
class MainDashboardVuMeterTest {

    @Test
    fun `meter is present with the live level while capturing`() {
        val state = dashboardVuMeterState(isCapturing = true, level = 0.42f)
        assertTrue("meter should be present while capturing", state.isPresent)
        assertEquals(0.42f, state.level, 1e-4f)
    }

    @Test
    fun `meter is absent and reports zero level while idle`() {
        val state = dashboardVuMeterState(isCapturing = false, level = 0.42f)
        assertFalse("meter should be absent while idle (no reserved space)", state.isPresent)
        assertEquals("idle meter reports zero level (no stale value leaks into layout)",
            0f, state.level, 1e-4f)
    }

    @Test
    fun `meter reflects a changing level while capturing`() {
        // The dashboard predicate is a pure pass-through of the unified capture signal — it does
        // not branch per talk mode (journal / STT / future channels). The same meter state shape
        // is produced regardless of which channel is capturing.
        val first = dashboardVuMeterState(isCapturing = true, level = 0.1f)
        val second = dashboardVuMeterState(isCapturing = true, level = 0.7f)
        assertTrue(first.isPresent)
        assertTrue(second.isPresent)
        assertEquals(0.1f, first.level, 1e-4f)
        assertEquals(0.7f, second.level, 1e-4f)
    }

    @Test
    fun `meter absence is the same regardless of the last seen level`() {
        // No reserved space: the idle state collapses to absent/0 for any prior level value.
        val fromLow = dashboardVuMeterState(isCapturing = false, level = 0.0f)
        val fromHigh = dashboardVuMeterState(isCapturing = false, level = 0.95f)
        assertEquals(fromLow, fromHigh)
        assertFalse(fromLow.isPresent)
    }
}