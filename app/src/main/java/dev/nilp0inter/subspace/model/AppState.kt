package dev.nilp0inter.subspace.model

data class AppState(
    val connection: ConnectionState = ConnectionState(),
    val monitor: MonitorState = MonitorState(),
    val journal: JournalChannel = JournalChannel(),
    val debugChannel: DebugChannel = DebugChannel(),
    val keyboard: KeyboardChannel = KeyboardChannel(),
    val activeChannelId: String = JournalChannel.ID,
    val inputMode: InputMode = InputMode.OnAPinch,
    val inputModeSelectedBy: InputModeSelection = InputModeSelection.User,
    // setupState removed: bootstrap state is now owned by BootstrapCoordinator
    val inputModeAvailability: InputModeAvailability = InputModeAvailability(),
) {
    val readyForMonitor: Boolean
        get() = connection.readyForMonitor
}

/**
 * Records whether the current [InputMode] was chosen by the user or
 * auto-switched by the system (Android Auto connect/disconnect per
 * `car-input-mode-auto-switch`). Defaulting to [InputModeSelection.User] keeps
 * the existing manual-selection behavior observable-unchanged; the new
 * auto-switch consults this flag before overriding the user's choice.
 */
enum class InputModeSelection { User, System }

enum class HardwareMode { Active, Control }
enum class PttSource { Rsm, Phone, CarTelecom }
enum class InputMode { Work, OnTheRoad, OnAPinch }

data class InputModeAvailability(
    val work: Boolean = false,
    val onTheRoad: Boolean = false,
    val onAPinch: Boolean = true,
) {
    fun isAvailable(mode: InputMode): Boolean = when (mode) {
        InputMode.Work -> work
        InputMode.OnTheRoad -> onTheRoad
        InputMode.OnAPinch -> onAPinch
    }
}
