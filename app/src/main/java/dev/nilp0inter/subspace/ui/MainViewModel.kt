package dev.nilp0inter.subspace.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.service.PttForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    var service: PttForegroundService? = null
        private set

    private var collectJob: Job? = null

    fun onServiceConnected(service: PttForegroundService) {
        onServiceConnected(
            service = service,
            serviceAppState = service.appState,
            serviceLevel = service.level,
            serviceIsCapturing = service.isCapturing
        )
    }

    fun onServiceConnected(
        service: PttForegroundService?,
        serviceAppState: StateFlow<AppState>,
        serviceLevel: StateFlow<Float>,
        serviceIsCapturing: StateFlow<Boolean>
    ) {
        this.service = service
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            launch {
                serviceAppState.collect { _appState.value = it }
            }
            launch {
                serviceLevel.collect { _level.value = it }
            }
            launch {
                serviceIsCapturing.collect { _isCapturing.value = it }
            }
        }
    }

    fun onServiceDisconnected() {
        collectJob?.cancel()
        collectJob = null
        this.service = null
    }
}
