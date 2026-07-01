package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.Channel
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun MainDashboardScreen(
    connected: Boolean,
    appState: AppState,
    level: Float,
    isCapturing: Boolean,
    actions: PttUiActions,
    onConnectionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val phonePttLockThresholdPx = with(LocalDensity.current) { PhonePttLockThreshold.toPx() }
    var phonePttGesture by remember { mutableStateOf<PhonePttGestureState>(PhonePttGestureState.Idle) }

    fun applyPhonePttTransition(transition: PhonePttGestureTransition) {
        phonePttGesture = transition.state
        for (command in transition.commands) {
            val channelId = when (val state = transition.state) {
                PhonePttGestureState.Idle -> return
                is PhonePttGestureState.Armed -> state.channelId
                is PhonePttGestureState.Locked -> state.channelId
                is PhonePttGestureState.Finalized -> state.channelId
            }
            when (command) {
                PhonePttGestureCommand.Press -> actions.phonePttPressed(channelId)
                PhonePttGestureCommand.Release -> actions.phonePttReleased(channelId)
            }
        }
        if (transition.state is PhonePttGestureState.Finalized) {
            phonePttGesture = PhonePttGestureState.Idle
        }
    }

    val currentPhonePttGesture by rememberUpdatedState(phonePttGesture)
    val currentApplyPhonePttTransition by rememberUpdatedState<(PhonePttGestureTransition) -> Unit> {
        applyPhonePttTransition(it)
    }

    LaunchedEffect(isCapturing) {
        currentApplyPhonePttTransition(currentPhonePttGesture.captureChangedPhonePttGesture(isCapturing))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                currentApplyPhonePttTransition(currentPhonePttGesture.focusLostPhonePttGesture())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "SUBSPACE",
            subtitle = "Field operations console",
        )

        ConnectionIndicator(
            connected = connected,
            onClick = onConnectionClick,
        )

        VuMeter(level = level, isCapturing = isCapturing)

        InputModeSelector(appState, actions)

        ChannelPanel(
            appState = appState,
            actions = actions,
            phonePttGesture = phonePttGesture,
            phonePttLockThresholdPx = phonePttLockThresholdPx,
            onPhonePttTransition = ::applyPhonePttTransition,
        )
    }
}

@Composable
private fun ConnectionIndicator(
    connected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val label = if (connected) "CONNECTED" else "NOT CONNECTED"
    val guidance = if (connected) "Tap for field monitor" else "Tap for link setup"

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "DEVICE LINK",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = TARGET_DEVICE_NAME,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                StatusPill(label = label, accent = accent)
            }

            Text(
                text = label,
                style = MaterialTheme.typography.displaySmall,
                color = accent,
            )
            Text(
                text = guidance,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InputModeSelector(
    appState: AppState,
    actions: PttUiActions,
) {
    val availability = appState.inputModeAvailability
    val activeMode = appState.inputMode

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "INPUT MODE",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeSegment(
                label = "Work",
                subtitle = "RSM headset",
                isActive = activeMode == InputMode.Work,
                isAvailable = availability.work,
                onClick = { actions.setInputMode(InputMode.Work) },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                label = "On-the-road",
                subtitle = "Steering wheel",
                isActive = activeMode == InputMode.OnTheRoad,
                isAvailable = availability.onTheRoad,
                onClick = { actions.setInputMode(InputMode.OnTheRoad) },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                label = "On-a-pinch",
                subtitle = "Phone alone",
                isActive = activeMode == InputMode.OnAPinch,
                isAvailable = availability.onAPinch,
                onClick = { actions.setInputMode(InputMode.OnAPinch) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeSegment(
    label: String,
    subtitle: String,
    isActive: Boolean,
    isAvailable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (isActive) MaterialTheme.colorScheme.primary
    else if (isAvailable) MaterialTheme.colorScheme.secondary
    else MaterialTheme.colorScheme.outline
    val contentColor = if (isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Card(
        onClick = onClick.takeIf { isAvailable } ?: {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(if (isActive) 2.dp else 1.dp, accent),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ChannelPanel(
    appState: AppState,
    actions: PttUiActions,
    phonePttGesture: PhonePttGestureState,
    phonePttLockThresholdPx: Float,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "CHANNELS",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        appState.channels.forEach { channel ->
            when (channel) {
                is JournalChannel -> JournalCard(
                    channel = channel,
                    activeChannelId = appState.activeChannelId,
                    actions = actions,
                    phonePttGesture = phonePttGesture,
                    phonePttLockThresholdPx = phonePttLockThresholdPx,
                    onPhonePttTransition = onPhonePttTransition,
                )
                is DebugChannel -> DebugChannelCard(
                    channel = channel,
                    activeChannelId = appState.activeChannelId,
                    actions = actions,
                    phonePttGesture = phonePttGesture,
                    phonePttLockThresholdPx = phonePttLockThresholdPx,
                    onPhonePttTransition = onPhonePttTransition,
                )
                else -> UnknownChannelCard(channel)
            }
        }

        Button(onClick = actions::addDebugChannel, modifier = Modifier.fillMaxWidth()) {
            Text("Add Debug Channel")
        }

        previewChannels.forEach { channel ->
            ChannelCard(channel)
        }
    }
}

@Composable
private fun JournalCard(
    channel: JournalChannel,
    activeChannelId: String,
    actions: PttUiActions,
    phonePttGesture: PhonePttGestureState,
    phonePttLockThresholdPx: Float,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    val channelId = channel.id
    val isActive = activeChannelId == channelId
    val isReady = channel.isReady
    val accent = when {
        phonePttGesture.activeChannelId == channelId -> MaterialTheme.colorScheme.secondary
        isActive -> MaterialTheme.colorScheme.primary
        isReady -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .phonePttInput(
                            channelId = channelId,
                            lockThresholdPx = phonePttLockThresholdPx,
                            onSelect = actions::setActiveChannel,
                            onPhonePttTransition = onPhonePttTransition,
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("CH-${channel.position + 1} / LOCAL LOG", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (!isReady) {
                        Text(
                            text = "Requires configuration to broadcast.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    HeldPhonePttInstruction(channelId, phonePttGesture)
                }
                LockedPhonePttStop(channelId, phonePttGesture, onPhonePttTransition)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = when {
                        phonePttGesture.isLocked && phonePttGesture.activeChannelId == channelId -> "LOCKED"
                        phonePttGesture.activeChannelId == channelId -> "PTT"
                        isActive -> "ACTIVE"
                        isReady -> "READY"
                        else -> "STANDBY"
                    },
                    accent = accent,
                )
                IconButton(onClick = { actions.navigateToJournalConfig(channelId) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Config")
                }
            }
        }
    }
}

@Composable
private fun DebugChannelCard(
    channel: DebugChannel,
    activeChannelId: String,
    actions: PttUiActions,
    phonePttGesture: PhonePttGestureState,
    phonePttLockThresholdPx: Float,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    val channelId = channel.id
    val isActive = activeChannelId == channelId
    val accent = when {
        phonePttGesture.activeChannelId == channelId -> MaterialTheme.colorScheme.secondary
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .phonePttInput(
                            channelId = channelId,
                            lockThresholdPx = phonePttLockThresholdPx,
                            onSelect = actions::setActiveChannel,
                            onPhonePttTransition = onPhonePttTransition,
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("CH-${channel.position + 1} / TEST", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Mode: ${channel.mode.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HeldPhonePttInstruction(channelId, phonePttGesture)
                }
                LockedPhonePttStop(channelId, phonePttGesture, onPhonePttTransition)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = when {
                        phonePttGesture.isLocked && phonePttGesture.activeChannelId == channelId -> "LOCKED"
                        phonePttGesture.activeChannelId == channelId -> "PTT"
                        isActive -> "ACTIVE"
                        else -> "READY"
                    },
                    accent = accent,
                )
                IconButton(onClick = { actions.navigateToDebugConfig(channelId) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Config")
                }
            }
        }
    }
}

@Composable
private fun UnknownChannelCard(channel: Channel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Unknown channel type: ${channel.typeId}", color = MaterialTheme.colorScheme.error)
            StatusPill(label = "STANDBY", accent = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun HeldPhonePttInstruction(
    channelId: String,
    phonePttGesture: PhonePttGestureState,
) {
    val armed = phonePttGesture as? PhonePttGestureState.Armed
    if (armed?.channelId != channelId) return
    val direction = when (armed.lockDirection) {
        PhonePttLockDirection.Right -> "slide right to lock ▶"
        PhonePttLockDirection.Left -> "◀ slide left to lock"
    }
    Text(
        text = "RECORDING — $direction",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun LockedPhonePttStop(
    channelId: String,
    phonePttGesture: PhonePttGestureState,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    val locked = phonePttGesture as? PhonePttGestureState.Locked
    if (locked?.channelId != channelId) return
    Text(
        text = "RECORDING LOCKED",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Bold,
    )
    Button(
        onClick = { onPhonePttTransition(phonePttGesture.stopPhonePttGesture()) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("STOP")
    }
}

@Composable
private fun ChannelCard(channel: MockChannel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(label = "MOCK", accent = MaterialTheme.colorScheme.outline)
            }
            Text(
                text = channel.route,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = channel.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Preview only. No audio, network, command, or storage action is wired.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PhonePttLockThreshold = 88.dp

private enum class PreArmGestureResult {
    Tap,
    Cancel,
}

private fun Modifier.phonePttInput(
    channelId: String,
    lockThresholdPx: Float,
    onSelect: (String) -> Unit,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
): Modifier = pointerInput(channelId, lockThresholdPx, onSelect, onPhonePttTransition) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val initialX = down.position.x
        val initialY = down.position.y
        val touchSlopSquared = viewConfiguration.touchSlop * viewConfiguration.touchSlop
        var movedBeforeArmed = false

        val preArmResult = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull PreArmGestureResult.Cancel
                val dx = change.position.x - initialX
                val dy = change.position.y - initialY
                if (dx * dx + dy * dy > touchSlopSquared) {
                    movedBeforeArmed = true
                }
                if (!change.pressed) {
                    return@withTimeoutOrNull if (movedBeforeArmed) {
                        PreArmGestureResult.Cancel
                    } else {
                        PreArmGestureResult.Tap
                    }
                }
            }
        }

        when (preArmResult) {
            PreArmGestureResult.Tap -> {
                onSelect(channelId)
                return@awaitEachGesture
            }
            PreArmGestureResult.Cancel -> return@awaitEachGesture
            null -> Unit
        }

        down.consume()
        var localState = startPhonePttGesture(
            target = PhonePttGestureTarget.MainSurface,
            channelId = channelId,
            initialX = initialX,
            surfaceWidth = size.width.toFloat(),
        ).also(onPhonePttTransition).state

        while (localState.isActive) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                val transition = localState.cancelPhonePttGesture()
                onPhonePttTransition(transition)
                localState = transition.state
                break
            }

            change.consume()
            val transition = if (change.pressed) {
                localState.movePhonePttGesture(
                    currentX = change.position.x,
                    lockThresholdPx = lockThresholdPx,
                )
            } else {
                localState.releasePhonePttGesture()
            }
            if (transition.state != localState || transition.commands.isNotEmpty()) {
                onPhonePttTransition(transition)
            }
            localState = transition.state
            if (!change.pressed) break
        }
    }
}

@Composable
private fun StatusPill(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class MockChannel(
    val name: String,
    val route: String,
    val description: String,
)

private val previewChannels = listOf(
    MockChannel(
        name = "Command Uplink",
        route = "CH-02 / REMOTE",
        description = "Placeholder for a future network-backed channel.",
    )
)

data class DashboardVuMeterState(
    val isPresent: Boolean,
    val level: Float,
)

fun dashboardVuMeterState(isCapturing: Boolean, level: Float): DashboardVuMeterState =
    if (isCapturing) DashboardVuMeterState(isPresent = true, level = level)
    else DashboardVuMeterState(isPresent = false, level = 0f)
