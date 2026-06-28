package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.InputModeAvailability
import dev.nilp0inter.subspace.model.PttSource

/**
 * State machine for [InputMode].
 *
 * Holds the current [mode] and [availability], and applies the transition rules
 * defined in the `input-mode` specification:
 *
 * 1. Android Auto connects → OnTheRoad (from any mode).
 * 2. RSM connects+bonds from OnAPinch → Work (stays OnTheRoad if already there).
 * 3. OnTheRoad disconnect → Work if RSM bonded, else OnAPinch.
 * 4. Work disconnect → OnTheRoad if AA connected, else OnAPinch.
 * 5. User selects any available mode → that mode (rules re-assert on next device event).
 *
 * Actuator auto-transition:
 * - Rsm → Work, Phone → OnAPinch, CarTelecom → OnTheRoad.
 * - Transition only occurs if the home mode is available.
 *
 * Mode-exclusive gating:
 * - Only one capture at a time is enforced by [PttForegroundService.activePttSession].
 */
internal class InputModeController {
    var mode: InputMode = InputMode.OnAPinch
        private set

    var availability: InputModeAvailability = InputModeAvailability()
        private set

    private var lastReadyForMonitor = false
    private var lastAaConnected = false
    private var userOverride: InputMode? = null

    /**
     * Update availability and apply transition rules based on changed inputs.
     *
     * Call this whenever `connection.readyForMonitor` or `AndroidAutoPresenceBus`
     * state changes.
     *
     * @param readyForMonitor true when permissions + BT + RSM bonded + SPP connected + SCO available.
     * @param aaConnected true when at least one media browser client is connected.
     */
    fun updateInputs(readyForMonitor: Boolean, aaConnected: Boolean) {
        availability = InputModeAvailability(
            work = readyForMonitor,
            onTheRoad = aaConnected,
            onAPinch = true,
        )

        val rsmBecameReady = readyForMonitor && !lastReadyForMonitor
        val rsmBecameNotReady = !readyForMonitor && lastReadyForMonitor
        val aaBecameConnected = aaConnected && !lastAaConnected
        val aaBecameDisconnected = !aaConnected && lastAaConnected

        // Apply transition rules on edge events.
        when {
            // Rule 1: AA connects → OnTheRoad (from any mode).
            aaBecameConnected && availability.onTheRoad -> {
                mode = InputMode.OnTheRoad
                userOverride = null
            }
            // Rule 3: OnTheRoad disconnect → Work if RSM bonded, else OnAPinch.
            aaBecameDisconnected && mode == InputMode.OnTheRoad -> {
                mode = if (readyForMonitor && availability.work) InputMode.Work else InputMode.OnAPinch
                userOverride = null
            }
            // Rule 2: RSM connects from OnAPinch → Work (stays OnTheRoad if already there).
            rsmBecameReady && mode == InputMode.OnAPinch && availability.work -> {
                mode = InputMode.Work
                userOverride = null
            }
            // Rule 4: Work disconnect → OnTheRoad if AA connected, else OnAPinch.
            rsmBecameNotReady && mode == InputMode.Work -> {
                mode = if (aaConnected && availability.onTheRoad) InputMode.OnTheRoad else InputMode.OnAPinch
                userOverride = null
            }
            // Rules re-assert after user override when a device event fires.
            userOverride != null -> {
                userOverride = null
                if (!availability.isAvailable(mode)) {
                    mode = fallbackMode()
                }
            }
        }

        // If current mode became unavailable (e.g. Work lost SCO while in Work),
        // transition to a safe mode immediately.
        if (!availability.isAvailable(mode)) {
            mode = fallbackMode()
            userOverride = null
        }

        lastReadyForMonitor = readyForMonitor
        lastAaConnected = aaConnected
    }

    /**
     * User selects a mode. Only transitions if the mode is available.
     * The selection is honored at selection time; transition rules re-assert
     * on the next device event.
     *
     * @return true if the transition was applied.
     */
    fun setInputMode(requested: InputMode): Boolean {
        if (!availability.isAvailable(requested)) return false
        mode = requested
        userOverride = requested
        return true
    }

    /**
     * Auto-transition to the home mode for the given [source] actuator.
     * Used before dispatching a PTT press so the route resolution matches
     * the actuator's natural mode.
     *
     * @return true if the transition succeeded (mode is available).
     */
    fun autoTransitionFor(source: PttSource): Boolean {
        val homeMode = when (source) {
            PttSource.Rsm -> InputMode.Work
            PttSource.Phone -> InputMode.OnAPinch
            PttSource.CarTelecom -> InputMode.OnTheRoad
        }
        if (!availability.isAvailable(homeMode)) return false
        mode = homeMode
        userOverride = null
        return true
    }

    private fun fallbackMode(): InputMode = when {
        availability.onAPinch -> InputMode.OnAPinch
        availability.work -> InputMode.Work
        availability.onTheRoad -> InputMode.OnTheRoad
        else -> InputMode.OnAPinch
    }
}