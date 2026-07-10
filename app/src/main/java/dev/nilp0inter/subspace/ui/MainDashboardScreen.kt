package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.KeyboardChannel
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun MainDashboardScreen(
    appState: AppState,
    level: Float,
    isCapturing: Boolean,
    actions: PttUiActions,
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
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "SUBSPACE",
            subtitle = "Field operations console",
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
                mode = InputMode.Work,
                label = "RSM",
                status = if (availability.work) "READY" else "SETUP",
                isActive = activeMode == InputMode.Work,
                isAvailable = availability.work,
                onTileAction = { action -> handleModeTileAction(action, InputMode.Work, actions) },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                mode = InputMode.OnTheRoad,
                label = "CAR",
                status = if (availability.onTheRoad) "READY" else "OFFLINE",
                isActive = activeMode == InputMode.OnTheRoad,
                isAvailable = availability.onTheRoad,
                onTileAction = { action -> handleModeTileAction(action, InputMode.OnTheRoad, actions) },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                mode = InputMode.OnAPinch,
                label = "PHONE",
                status = "READY",
                isActive = activeMode == InputMode.OnAPinch,
                isAvailable = availability.onAPinch,
                onTileAction = { action -> handleModeTileAction(action, InputMode.OnAPinch, actions) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ModeSegment(
    mode: InputMode,
    label: String,
    status: String,
    isActive: Boolean,
    isAvailable: Boolean,
    onTileAction: (DashboardModeTileAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (isActive) MaterialTheme.colorScheme.primary
    else if (isAvailable) MaterialTheme.colorScheme.secondary
    else MaterialTheme.colorScheme.outline
    val contentColor = if (isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    val statusColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isAvailable -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(if (isActive) 2.dp else 1.dp, accent),
        modifier = modifier
            .height(118.dp)
            .combinedClickable(
                onClick = { onTileAction(dashboardModeTileTapAction(mode, isAvailable)) },
                onLongClick = if (mode == InputMode.Work) {
                    { onTileAction(dashboardModeTileLongPressAction(mode)) }
                } else {
                    null
                },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModeGlyph(
                mode = mode,
                color = contentColor,
                modifier = Modifier.size(42.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun handleModeTileAction(
    action: DashboardModeTileAction,
    mode: InputMode,
    actions: PttUiActions,
) {
    when (action) {
        DashboardModeTileAction.SelectMode -> actions.setInputMode(mode)
        DashboardModeTileAction.OpenRsmSetup -> actions.navigateToRsmSetup()
        DashboardModeTileAction.Ignore -> Unit
    }
}

@Composable
private fun ModeGlyph(
    mode: InputMode,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = size.minDimension * 0.075f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        when (mode) {
            InputMode.Work -> {
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.12f),
                    size = Size(w * 0.68f, h * 0.72f),
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.22f, h * 0.52f), Offset(w * 0.22f, h * 0.76f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.78f, h * 0.52f), Offset(w * 0.78f, h * 0.76f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.78f), Offset(w * 0.78f, h * 0.78f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.78f), Offset(w * 0.58f, h * 0.68f), strokeWidth, StrokeCap.Round)
            }
            InputMode.OnTheRoad -> {
                drawCircle(color = color, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(color = color, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.19f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.25f, h * 0.68f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.75f, h * 0.68f), strokeWidth, StrokeCap.Round)
            }
            InputMode.OnAPinch -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.28f, h * 0.08f),
                    size = Size(w * 0.44f, h * 0.84f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.42f, h * 0.78f), Offset(w * 0.58f, h * 0.78f), strokeWidth, StrokeCap.Round)
            }
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

        JournalCard(
            appState = appState,
            actions = actions,
            phonePttGesture = phonePttGesture,
            phonePttLockThresholdPx = phonePttLockThresholdPx,
            onPhonePttTransition = onPhonePttTransition,
        )
        KeyboardChannelCard(
            appState = appState,
            actions = actions,
            phonePttGesture = phonePttGesture,
            phonePttLockThresholdPx = phonePttLockThresholdPx,
            onPhonePttTransition = onPhonePttTransition,
        )
        DebugChannelCard(
            appState = appState,
            actions = actions,
            phonePttGesture = phonePttGesture,
            phonePttLockThresholdPx = phonePttLockThresholdPx,
            onPhonePttTransition = onPhonePttTransition,
        )

        previewChannels.forEach { channel ->
            ChannelCard(channel)
        }
    }
}

@Composable
private fun JournalCard(
    appState: AppState,
    actions: PttUiActions,
    phonePttGesture: PhonePttGestureState,
    phonePttLockThresholdPx: Float,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    val channel = appState.journal
    val channelId = JournalChannel.ID
    val isActive = appState.activeChannelId == channelId
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
                    Text("CH-01 / LOCAL LOG", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                IconButton(onClick = actions::navigateToJournalConfig) {
                    Icon(Icons.Filled.Settings, contentDescription = "Config")
                }
            }
        }
    }
}

@Composable
private fun KeyboardChannelCard(
    appState: AppState,
    actions: PttUiActions,
    phonePttGesture: PhonePttGestureState,
    phonePttLockThresholdPx: Float,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    val channel = appState.keyboard
    val channelId = KeyboardChannel.ID
    val isActive = appState.activeChannelId == channelId
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
                    Text("CH-02 / BLE HID", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (!isReady) {
                        Text(
                            text = "Requires active BLE bridge connection.",
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
                IconButton(onClick = actions::navigateToKeyboardConfig) {
                    Icon(Icons.Filled.Settings, contentDescription = "Config")
                }
            }
        }
    }
}


@Composable
private fun DebugChannelCard(
    appState: AppState,
    actions: PttUiActions,
    phonePttGesture: PhonePttGestureState,
    phonePttLockThresholdPx: Float,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    val channel = appState.debugChannel
    val channelId = DebugChannel.ID
    val isActive = appState.activeChannelId == channelId
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
                    Text("CH-03 / TEST", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                IconButton(onClick = actions::navigateToDebugConfig) {
                    Icon(Icons.Filled.Settings, contentDescription = "Config")
                }
            }
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
    DashboardVuMeterState(isPresent = true, level = if (isCapturing) level else 0f)

enum class DashboardModeTileAction {
    SelectMode,
    OpenRsmSetup,
    Ignore,
}

fun dashboardModeTileTapAction(mode: InputMode, isAvailable: Boolean): DashboardModeTileAction = when {
    isAvailable -> DashboardModeTileAction.SelectMode
    mode == InputMode.Work -> DashboardModeTileAction.OpenRsmSetup
    else -> DashboardModeTileAction.Ignore
}

fun dashboardModeTileLongPressAction(mode: InputMode): DashboardModeTileAction =
    if (mode == InputMode.Work) DashboardModeTileAction.OpenRsmSetup else DashboardModeTileAction.Ignore
