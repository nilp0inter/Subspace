package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.bluetooth.SleepwalkerConnectionResult
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.KeyboardConfig
import io.sleepwalker.core.keymap.HostProfile
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelRuntime
import dev.nilp0inter.subspace.service.ChannelRuntimeFactory
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KeyboardRuntimeFactory(
    private val scope: CoroutineScope,
    private val controllerProvider: () -> KeyboardPttController?,
    private val bridgeConnectedFlow: StateFlow<Boolean>,
    private val ensureBridgeConnected: suspend () -> SleepwalkerConnectionResult,
) : ChannelRuntimeFactory {
    override fun create(definition: ChannelDefinition): ChannelRuntime {
        return KeyboardRuntime(
            scope,
            definition,
            controllerProvider,
            bridgeConnectedFlow,
            ensureBridgeConnected,
        )
    }
}

class KeyboardRuntime(
    parentScope: CoroutineScope,
    override var definition: ChannelDefinition,
    private val controllerProvider: () -> KeyboardPttController?,
    private val bridgeConnectedFlow: StateFlow<Boolean>,
    private val ensureBridgeConnected: suspend () -> SleepwalkerConnectionResult,
) : ChannelRuntime {

    private val runtimeScope = CoroutineScope(parentScope.coroutineContext + Job())
    
    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            kind = definition.kind,
            enabled = definition.enabled,
            isReady = definition.enabled && bridgeConnectedFlow.value,
            executionStatus = ChannelExecutionStatus.IDLE
        )
    )
    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

    init {
        runtimeScope.launch {
            bridgeConnectedFlow.collect { connected ->
                _snapshot.value = _snapshot.value.copy(
                    isReady = definition.enabled && connected
                )
            }
        }
    }

    override val id: String
        get() = definition.id

    private fun evaluateReadiness(def: ChannelDefinition): Boolean {
        return def.enabled && bridgeConnectedFlow.value
    }

    override fun updateDefinition(definition: ChannelDefinition) {
        this.definition = definition
        val controller = controllerProvider()
        if (controller != null) {
            controller.setEnabled(definition.enabled)
        }
        _snapshot.value = _snapshot.value.copy(
            name = definition.name,
            enabled = definition.enabled,
            isReady = evaluateReadiness(definition)
        )
    }

    override suspend fun prepareInput(): ChannelInputAcceptance {
        if (!definition.enabled) {
            return ChannelInputAcceptance.Refused("Keyboard channel is disabled")
        }
        if (definition.config !is KeyboardConfig) {
            return ChannelInputAcceptance.Unavailable("Invalid Keyboard configuration")
        }
        if (!bridgeConnectedFlow.value) {
            when (val result = ensureBridgeConnected()) {
                SleepwalkerConnectionResult.Connected -> Unit
                is SleepwalkerConnectionResult.Failed ->
                    return ChannelInputAcceptance.Refused(result.reason)
                SleepwalkerConnectionResult.TimedOut ->
                    return ChannelInputAcceptance.Refused("Sleepwalker connection timed out")
            }
        }
        if (!definition.enabled) {
            return ChannelInputAcceptance.Refused("Keyboard channel is disabled")
        }
        val controller = controllerProvider()
            ?: return ChannelInputAcceptance.Unavailable("Keyboard controller not initialized")

        controller.setEnabled(true)
        return ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
                controller.onInputStarted(session)
            }

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
                val result = controller.onInputReleased(recording)
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                return result
            }

            override fun onInputPlaybackCompleted() {}

            override fun onInputCancelled(reason: String) {
                controller.onInputCancelled(reason)
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
            }

            override fun onInputFailed(reason: String) {
                controller.onInputFailed(reason)
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
            }
        })
    }

    override fun handleSos() {
        controllerProvider()?.sendEnter()
    }

    override fun close() {
        runtimeScope.cancel()
        controllerProvider()?.cancelAndRelease()
    }
}
