package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelKind
import kotlinx.coroutines.flow.StateFlow

enum class ChannelExecutionStatus {
    IDLE, RECORDING, PROCESSING, SUCCESS, FAILED
}

data class ChannelRuntimeSnapshot(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val enabled: Boolean,
    val isReady: Boolean,
    val executionStatus: ChannelExecutionStatus,
    val summary: String? = null,
    val pendingCount: Int = 0
)

interface ChannelRuntime {
    val id: String
    val definition: ChannelDefinition
    val snapshot: StateFlow<ChannelRuntimeSnapshot>
    
    fun updateDefinition(definition: ChannelDefinition)
    suspend fun prepareInput(): ChannelInputAcceptance
    fun handleSos() {}
    fun refreshReadiness() {}
    fun close()
}

interface ChannelRuntimeFactory {
    fun create(definition: ChannelDefinition): ChannelRuntime
}
