package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ConnectionState
import dev.nilp0inter.subspace.model.DevicePresence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testViewModelCachesStateAndPropagates() = runTest(testDispatcher) {
        val viewModel = MainViewModel()
        
        // 1. Assert default values
        assertEquals(AppState(), viewModel.appState.value)
        assertEquals(0f, viewModel.level.value)
        assertEquals(false, viewModel.isCapturing.value)
        assertNull(viewModel.service)

        // 2. Setup mock flows
        val appStateFlow = MutableStateFlow(AppState())
        val levelFlow = MutableStateFlow(0f)
        val isCapturingFlow = MutableStateFlow(false)

        // Connect the flows
        viewModel.onServiceConnected(
            service = null, // service is null since we're testing on JVM
            serviceAppState = appStateFlow,
            serviceLevel = levelFlow,
            serviceIsCapturing = isCapturingFlow
        )

        // Let coroutines run to register the collectors
        advanceUntilIdle()

        // 3. Update flows and verify propagation
        val updatedState = AppState(connection = ConnectionState(devicePresence = DevicePresence.Bonded))
        appStateFlow.value = updatedState
        levelFlow.value = 0.75f
        isCapturingFlow.value = true

        advanceUntilIdle()

        assertEquals(updatedState, viewModel.appState.value)
        assertEquals(0.75f, viewModel.level.value)
        assertEquals(true, viewModel.isCapturing.value)

        // 4. Disconnect and verify caching
        viewModel.onServiceDisconnected()
        advanceUntilIdle()

        // Verify that the values remain cached
        assertEquals(updatedState, viewModel.appState.value)
        assertEquals(0.75f, viewModel.level.value)
        assertEquals(true, viewModel.isCapturing.value)

        // 5. Update mock flows after disconnect and verify no propagation
        appStateFlow.value = AppState()
        levelFlow.value = 0.1f
        isCapturingFlow.value = false

        advanceUntilIdle()

        // Cached values should not change
        assertEquals(updatedState, viewModel.appState.value)
        assertEquals(0.75f, viewModel.level.value)
        assertEquals(true, viewModel.isCapturing.value)
    }
}
