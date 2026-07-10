package dev.nilp0inter.subspace.telecom

import android.telecom.CallAudioState
import dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME
import org.junit.Assert.assertEquals
import org.junit.Test

class TelecomCaptureRouteStabilizerTest {
    @Test
    fun acceptableRoutePublishesReadinessOnlyAfterStabilityWindow() {
        val scheduler = ManualScheduler()
        val routeChanges = mutableListOf<Boolean>()
        val stabilizer = createStabilizer(scheduler, routeChanges)

        stabilizer.routeChanged(acceptable = true)

        assertEquals(1, scheduler.pendingCount)
        assertEquals(emptyList<Boolean>(), routeChanges)

        scheduler.advanceBy(STABILITY_DELAY_MS - 1)
        assertEquals(emptyList<Boolean>(), routeChanges)

        scheduler.advanceBy(1)
        assertEquals(listOf(true), routeChanges)
    }

    @Test
    fun unacceptableRouteCancelsPendingReadiness() {
        val scheduler = ManualScheduler()
        val routeChanges = mutableListOf<Boolean>()
        val stabilizer = createStabilizer(scheduler, routeChanges)

        stabilizer.routeChanged(acceptable = true)
        scheduler.advanceBy(STABILITY_DELAY_MS / 2)
        stabilizer.routeChanged(acceptable = false)
        scheduler.advanceBy(STABILITY_DELAY_MS)

        assertEquals(listOf(false), routeChanges)
    }

    @Test
    fun duplicateAcceptableRouteDoesNotRestartStabilityWindow() {
        val scheduler = ManualScheduler()
        val routeChanges = mutableListOf<Boolean>()
        val stabilizer = createStabilizer(scheduler, routeChanges)

        stabilizer.routeChanged(acceptable = true)
        scheduler.advanceBy(STABILITY_DELAY_MS - 1)
        stabilizer.routeChanged(acceptable = true)
        scheduler.advanceBy(1)

        assertEquals(listOf(true), routeChanges)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun cancelSuppressesPendingReadiness() {
        val scheduler = ManualScheduler()
        val routeChanges = mutableListOf<Boolean>()
        val stabilizer = createStabilizer(scheduler, routeChanges)

        stabilizer.routeChanged(acceptable = true)
        stabilizer.cancel()
        scheduler.advanceBy(STABILITY_DELAY_MS)

        assertEquals(emptyList<Boolean>(), routeChanges)
    }

    @Test
    fun captureRoutePredicateRequiresActiveNonRsmBluetoothRoute() {
        data class Case(
            val name: String,
            val route: Int,
            val activeBluetoothDevicePresent: Boolean,
            val activeBluetoothDeviceName: String?,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(
                name = "Bluetooth device present but call route is not Bluetooth",
                route = CallAudioState.ROUTE_EARPIECE,
                activeBluetoothDevicePresent = true,
                activeBluetoothDeviceName = "Vehicle HFP",
                expected = false,
            ),
            Case(
                name = "Bluetooth call route has no active Bluetooth device",
                route = CallAudioState.ROUTE_BLUETOOTH,
                activeBluetoothDevicePresent = false,
                activeBluetoothDeviceName = "Vehicle HFP",
                expected = false,
            ),
            Case(
                name = "Bluetooth call route targets the RSM",
                route = CallAudioState.ROUTE_BLUETOOTH,
                activeBluetoothDevicePresent = true,
                activeBluetoothDeviceName = "Cabin $TARGET_DEVICE_NAME",
                expected = false,
            ),
            Case(
                name = "Bluetooth call route uses the active car device",
                route = CallAudioState.ROUTE_BLUETOOTH,
                activeBluetoothDevicePresent = true,
                activeBluetoothDeviceName = "Vehicle HFP",
                expected = true,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                isAcceptableTelecomCaptureRoute(
                    route = case.route,
                    activeBluetoothDevicePresent = case.activeBluetoothDevicePresent,
                    activeBluetoothDeviceName = case.activeBluetoothDeviceName,
                ),
            )
        }
    }

    private fun createStabilizer(
        scheduler: ManualScheduler,
        routeChanges: MutableList<Boolean>,
    ) = TelecomCaptureRouteStabilizer(
        stabilityDelayMs = STABILITY_DELAY_MS,
        postDelayed = scheduler::postDelayed,
        removeCallbacks = scheduler::removeCallbacks,
        onRouteChanged = routeChanges::add,
    )

    private class ManualScheduler {
        private data class ScheduledCallback(
            val callback: Runnable,
            val dueAtMs: Long,
        )

        private val callbacks = mutableListOf<ScheduledCallback>()
        private var nowMs = 0L

        val pendingCount: Int
            get() = callbacks.size

        fun postDelayed(callback: Runnable, delayMs: Long) {
            callbacks += ScheduledCallback(callback, nowMs + delayMs)
        }

        fun removeCallbacks(callback: Runnable) {
            callbacks.removeAll { it.callback === callback }
        }

        fun advanceBy(durationMs: Long) {
            val targetMs = nowMs + durationMs
            while (true) {
                val next = callbacks
                    .filter { it.dueAtMs <= targetMs }
                    .minByOrNull { it.dueAtMs }
                    ?: break
                callbacks.remove(next)
                nowMs = next.dueAtMs
                next.callback.run()
            }
            nowMs = targetMs
        }
    }

    private companion object {
        const val STABILITY_DELAY_MS = 200L
    }
}
