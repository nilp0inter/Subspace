package dev.nilp0inter.subspace.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun unexpectedSerialLossSchedulesReconnectWhileMonitoringIntentRemainsActive() {
        val policy = ReconnectPolicy()
        policy.startMonitoring()

        val decision = policy.scheduleAfterUnexpectedLoss(nowMillis = 1_000, prerequisites = prerequisites())

        assertEquals(ReconnectDecision.Schedule(attemptAtMillis = 1_000), decision)
        assertTrue(policy.monitoringRequested)
        assertEquals(1_000L, policy.nextAttemptAtMillis)
    }

    @Test
    fun explicitDisconnectClearsMonitoringIntentAndCancelsPendingReconnectWork() {
        val policy = ReconnectPolicy()
        policy.startMonitoring()
        policy.scheduleAfterUnexpectedLoss(nowMillis = 1_000, prerequisites = prerequisites())

        policy.clearMonitoring()

        assertFalse(policy.monitoringRequested)
        assertNull(policy.nextAttemptAtMillis)
        assertEquals(
            ReconnectDecision.Blocked(ReconnectBlockReason.MonitoringNotRequested),
            policy.beginAttempt(nowMillis = 1_000, prerequisites = prerequisites()),
        )
    }

    @Test
    fun reconnectAttemptsAreBlockedWhenPrerequisitesAreMissing() {
        val policy = ReconnectPolicy()
        policy.startMonitoring()

        assertEquals(
            ReconnectDecision.Blocked(ReconnectBlockReason.MissingPermissions),
            policy.scheduleAfterUnexpectedLoss(
                nowMillis = 1_000,
                prerequisites = prerequisites(permissionsGranted = false),
            ),
        )
        assertEquals(
            ReconnectDecision.Blocked(ReconnectBlockReason.BluetoothDisabled),
            policy.scheduleAfterUnexpectedLoss(
                nowMillis = 1_000,
                prerequisites = prerequisites(bluetoothEnabled = false),
            ),
        )
        assertEquals(
            ReconnectDecision.Blocked(ReconnectBlockReason.TargetUnavailable),
            policy.scheduleAfterUnexpectedLoss(
                nowMillis = 1_000,
                prerequisites = prerequisites(bondedTargetAvailable = false),
            ),
        )
        assertNull(policy.nextAttemptAtMillis)
    }

    @Test
    fun failedReconnectAttemptsWaitBeforeRetryingAndDoNotRunConcurrently() {
        val policy = ReconnectPolicy(retryDelayMs = 5_000)
        policy.startMonitoring()
        policy.scheduleAfterUnexpectedLoss(nowMillis = 1_000, prerequisites = prerequisites())

        assertEquals(ReconnectDecision.StartAttempt, policy.beginAttempt(nowMillis = 1_000, prerequisites = prerequisites()))
        assertEquals(
            ReconnectDecision.AlreadyInProgress,
            policy.beginAttempt(nowMillis = 1_000, prerequisites = prerequisites()),
        )
        assertEquals(
            ReconnectDecision.Schedule(attemptAtMillis = 6_000),
            policy.finishAttempt(success = false, nowMillis = 1_000, prerequisites = prerequisites()),
        )
        assertEquals(ReconnectDecision.Wait(attemptAtMillis = 6_000), policy.beginAttempt(nowMillis = 5_999, prerequisites = prerequisites()))
        assertEquals(ReconnectDecision.StartAttempt, policy.beginAttempt(nowMillis = 6_000, prerequisites = prerequisites()))
    }

    @Test
    fun initialMonitoringSchedulesImmediateConnectionWithoutPriorSession() {
        val now = 1_000L
        val policy = ReconnectPolicy()
        policy.startMonitoring()

        val decision = policy.scheduleInitialConnection(nowMillis = now, prerequisites = prerequisites())

        assertEquals(ReconnectDecision.Schedule(attemptAtMillis = now), decision)
        assertTrue(policy.monitoringRequested)
        assertEquals(now, policy.nextAttemptAtMillis)
        assertEquals(ReconnectDecision.StartAttempt, policy.beginAttempt(nowMillis = now, prerequisites = prerequisites()))
    }

    private fun prerequisites(
        permissionsGranted: Boolean = true,
        bluetoothEnabled: Boolean = true,
        bondedTargetAvailable: Boolean = true,
    ): ReconnectPrerequisites = ReconnectPrerequisites(
        permissionsGranted = permissionsGranted,
        bluetoothEnabled = bluetoothEnabled,
        bondedTargetAvailable = bondedTargetAvailable,
    )
}
