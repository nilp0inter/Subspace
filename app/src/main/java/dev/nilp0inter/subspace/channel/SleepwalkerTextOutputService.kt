package dev.nilp0inter.subspace.channel

import android.util.Log

import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import dev.nilp0inter.subspace.bluetooth.SleepwalkerConnectionResult
import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextKeyRequest
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TextOutputFailureReason
import dev.nilp0inter.subspace.channel.capability.TextOutputIndeterminateReason
import dev.nilp0inter.subspace.channel.capability.TextOutputKey
import dev.nilp0inter.subspace.channel.capability.TextOutputProfile
import dev.nilp0inter.subspace.channel.capability.TextOutputRejectionReason
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.KeymapDatabase
import io.sleepwalker.core.protocol.Usages
import io.sleepwalker.core.text.TapScriptCompiler
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.text.TextRenderingFailure
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Host-owned semantic authority for Sleepwalker output.
 *
 * Channel runtimes supply only their instance identity, logical host profile, and text or a
 * constrained key action. This service owns all transport work, including preparation,
 * serialization, HID compilation, acknowledgement, and terminal safety cleanup.
 */
class SleepwalkerTextOutputService(
    private val scope: CoroutineScope,
    private val connection: SleepwalkerBleConnection,
    private val hid: LowLevelHid,
    private val keymapDatabase: KeymapDatabase,
    private val connect: suspend (timeoutMs: Long) -> SleepwalkerConnectionResult,
    private val preparationTimeoutMs: Long = DEFAULT_PREPARATION_TIMEOUT_MS,
    private val deliveryTimeoutMs: Long = DEFAULT_DELIVERY_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_PREPARATION_TIMEOUT_MS = SleepwalkerBleConnection.CONNECTION_TIMEOUT_MS
        const val DEFAULT_DELIVERY_TIMEOUT_MS = 15_000L
    }

    private val operationMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val operationCounter = AtomicLong()
    private var preparation: Deferred<SleepwalkerConnectionResult>? = null
    private var activeDelivery: Job? = null
    private var closed = false

    private val _availability = MutableStateFlow<TextOutputAvailability>(TextOutputAvailability.Unavailable)
    val availability: StateFlow<TextOutputAvailability> = _availability.asStateFlow()

    init {
        require(preparationTimeoutMs > 0) { "Preparation timeout must be positive" }
        require(deliveryTimeoutMs > 0) { "Delivery timeout must be positive" }
        scope.launch {
            connection.connectionState.collect { state ->
                if (closed) return@collect
                _availability.value = when (state) {
                    KeyboardConnectionState.Connected -> TextOutputAvailability.Available
                    KeyboardConnectionState.Scanning,
                    KeyboardConnectionState.Connecting -> TextOutputAvailability.Preparing
                    KeyboardConnectionState.Disconnected -> TextOutputAvailability.Unavailable
                }
            }
        }
    }

    /**
     * Creates an instance-bound semantic port for one runtime capability lease. The port does
     * not expose connection state, transport objects, or host lifecycle controls.
     */
    fun capabilityFor(instanceId: String): TextOutputCapability {
        require(instanceId.isNotBlank()) { "Text output instance ID must not be blank" }
        return object : TextOutputCapability {
            override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
                val operationId = nextOperationId(instanceId)
                val profile = request.profile.toHostProfile()
                    ?: return TextOutputOutcome.Rejected(
                        operationId,
                        TextOutputRejectionReason.INVALID_PROFILE,
                    ).toCapabilityOutcome()
                return sendText(operationId, profile, request.text).toCapabilityOutcome()
            }

            override suspend fun sendKey(request: TextKeyRequest): TextDeliveryOutcome {
                val operationId = nextOperationId(instanceId)
                if (request.profile.toHostProfile() == null) {
                    return TextOutputOutcome.Rejected(
                        operationId,
                        TextOutputRejectionReason.INVALID_PROFILE,
                    ).toCapabilityOutcome()
                }
                return sendKey(operationId, request.key).toCapabilityOutcome()
            }
        }
    }

    /**
     * Acquires semantic delivery availability. Compatible callers await the same bounded
     * connection attempt; a caller's cancellation never cancels shared host preparation.
     */
    suspend fun prepare(): TextOutputPreparation {
        val attempt = lifecycleMutex.withLock {
            if (closed) return TextOutputPreparation.Failed("Text output service is closed")
            sharedPreparation()
        }
        return when (val result = attempt.await()) {
            SleepwalkerConnectionResult.Connected -> TextOutputPreparation.Available
            SleepwalkerConnectionResult.TimedOut -> TextOutputPreparation.TimedOut
            is SleepwalkerConnectionResult.Failed -> TextOutputPreparation.Failed(result.reason)
        }
    }

    internal suspend fun isReadyForDelivery(): Boolean = lifecycleMutex.withLock {
        !closed && connection.connectionState.value == KeyboardConnectionState.Connected
    }

    /** Submits one non-replayable text operation addressed to a channel instance. */
    private suspend fun sendText(
        operationId: TextOutputOperationId,
        profile: HostProfile,
        text: String,
    ): TextOutputOutcome {
        if (text.isEmpty()) {
            Log.i(CHANNEL_EFFECT_TAG, "TEXT_OUTPUT_REJECTED operation=${operationId.value} reason=empty_text")
            return TextOutputOutcome.Rejected(operationId, TextOutputRejectionReason.EMPTY_TEXT)
        }
        val result = TextPlanner(database = keymapDatabase, hid = hid).plan(text, profile)
        val plan = result.plan
        val failure = result.failure
        if (plan == null || failure != null) {
            val reason = when (failure) {
                is TextRenderingFailure.UnrepresentableGlyph -> TextOutputRejectionReason.UNSUPPORTED_CHARACTER
                is TextRenderingFailure.MissingLayout -> TextOutputRejectionReason.INVALID_PROFILE
                else -> TextOutputRejectionReason.POLICY_REFUSED
            }
            Log.i(CHANNEL_EFFECT_TAG, "TEXT_OUTPUT_REJECTED operation=${operationId.value} reason=$reason text_length=${text.length}")
            return TextOutputOutcome.Rejected(operationId, reason)
        }
        val compiled = TapScriptCompiler.compile(plan, hid)
        Log.i(
            CHANNEL_EFFECT_TAG,
            "TEXT_OUTPUT_PLANNED operation=${operationId.value} text_length=${text.length} plan_ops=${plan.size} delivery_ops=${compiled.size}",
        )
        return deliver(operationId, compiled)
    }

    /** Submits one constrained host-defined key action; arbitrary HID operations are never exposed. */
    private suspend fun sendKey(
        operationId: TextOutputOperationId,
        action: TextOutputKey,
    ): TextOutputOutcome {
        val ops = when (action) {
            TextOutputKey.ENTER -> listOf(hid.keyTap(Usages.USB_KEY_ENTER))
            TextOutputKey.ESCAPE -> return TextOutputOutcome.Rejected(
                operationId,
                TextOutputRejectionReason.POLICY_REFUSED,
            )
        }
        return deliver(operationId, ops)
    }

    /**
     * Cancels future admission, waits for the serialized operation boundary, and closes the sole
     * host-owned transport once. Runtime instances must never call this method.
     */
    suspend fun close() {
        val (pending, delivery) = lifecycleMutex.withLock {
            if (closed) return
            closed = true
            preparation.also { preparation = null } to activeDelivery
        }
        delivery?.cancel()
        pending?.cancelAndJoin()
        operationMutex.withLock {
            connection.disconnect()
            _availability.value = TextOutputAvailability.Closed
        }
    }

    private fun sharedPreparation(): Deferred<SleepwalkerConnectionResult> {
        if (connection.connectionState.value == KeyboardConnectionState.Connected) {
            return CompletableDeferred(SleepwalkerConnectionResult.Connected)
        }
        preparation?.takeIf { it.isActive }?.let { return it }
        _availability.value = TextOutputAvailability.Preparing
        return scope.async {
            withTimeoutOrNull(preparationTimeoutMs) {
                connect(preparationTimeoutMs)
            } ?: SleepwalkerConnectionResult.TimedOut
        }.also { created ->
            preparation = created
            created.invokeOnCompletion {
                scope.launch {
                    lifecycleMutex.withLock {
                        if (preparation === created) preparation = null
                    }
                }
            }
        }
    }

    private suspend fun deliver(
        operationId: TextOutputOperationId,
        deliveryOps: List<io.sleepwalker.core.hid.LowLevelOp>,
    ): TextOutputOutcome {
        val completion = CompletableDeferred<TextOutputOutcome>()
        val deliveryWorker = scope.launch {
            val outcome = try {
                operationMutex.withLock {
                    deliverLocked(operationId, deliveryOps)
                }
            } catch (_: CancellationException) {
                TextOutputOutcome.Failed(operationId, TextOutputFailureReason.CANCELLED)
            } catch (_: Exception) {
                TextOutputOutcome.Failed(operationId, TextOutputFailureReason.HOST_FAILURE)
            }
            completion.complete(outcome)
        }
        return try {
            completion.await()
        } catch (cancelled: CancellationException) {
            deliveryWorker.cancel()
            throw cancelled
        }
    }

    private suspend fun deliverLocked(
        operationId: TextOutputOperationId,
        deliveryOps: List<io.sleepwalker.core.hid.LowLevelOp>,
    ): TextOutputOutcome {
        lifecycleMutex.withLock {
            if (closed) {
                return TextOutputOutcome.Failed(operationId, TextOutputFailureReason.UNAVAILABLE)
            }
        }
        when (prepare()) {
            TextOutputPreparation.Available -> Unit
            is TextOutputPreparation.Failed ->
                return TextOutputOutcome.Failed(operationId, TextOutputFailureReason.UNAVAILABLE)
            TextOutputPreparation.TimedOut ->
                return TextOutputOutcome.Failed(operationId, TextOutputFailureReason.TIMED_OUT)
        }
        lifecycleMutex.withLock {
            if (closed) {
                return TextOutputOutcome.Failed(operationId, TextOutputFailureReason.UNAVAILABLE)
            }
            activeDelivery = checkNotNull(currentCoroutineContext()[Job])
        }

        Log.i(CHANNEL_EFFECT_TAG, "TEXT_OUTPUT_DELIVERY_START operation=${operationId.value} delivery_ops=${deliveryOps.size}")
        var armed = false
        var transmissionMayHaveBegun = false
        var terminal: TextOutputOutcome? = null
        try {
            withTimeout(deliveryTimeoutMs) {
                connection.sendOp(hid.arm())
                armed = true
                for (op in deliveryOps) {
                    transmissionMayHaveBegun = true
                    connection.sendOp(op)
                    delay(15)
                }
                val finalOp = deliveryOps.lastOrNull()
                terminal = if (finalOp != null && !connection.awaitAck(finalOp.seqId)) {
                    TextOutputOutcome.Indeterminate(
                        operationId,
                        TextOutputIndeterminateReason.ACKNOWLEDGEMENT_LOST,
                    )
                } else {
                    TextOutputOutcome.Delivered(operationId)
                }
            }
        } catch (_: TimeoutCancellationException) {
            terminal = if (transmissionMayHaveBegun) {
                TextOutputOutcome.Indeterminate(operationId, TextOutputIndeterminateReason.TIMED_OUT)
            } else {
                TextOutputOutcome.Failed(operationId, TextOutputFailureReason.TIMED_OUT)
            }
        } catch (_: CancellationException) {
            terminal = if (transmissionMayHaveBegun) {
                TextOutputOutcome.Indeterminate(operationId, TextOutputIndeterminateReason.CANCELLED)
            } else {
                TextOutputOutcome.Failed(operationId, TextOutputFailureReason.CANCELLED)
            }
        } catch (_: Exception) {
            terminal = if (transmissionMayHaveBegun) {
                TextOutputOutcome.Indeterminate(operationId, TextOutputIndeterminateReason.DISCONNECTED)
            } else {
                TextOutputOutcome.Failed(operationId, TextOutputFailureReason.HOST_FAILURE)
            }
        } finally {
            if (armed) {
                withContext(NonCancellable) {
                    if (terminal !is TextOutputOutcome.Delivered) {
                        runCatching { connection.sendOp(hid.kill()) }
                    }
                    runCatching { connection.sendOp(hid.disarm()) }
                }
            }
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    activeDelivery = null
                }
            }
        }
        Log.i(
            CHANNEL_EFFECT_TAG,
            "TEXT_OUTPUT_DELIVERY_END operation=${operationId.value} outcome=${checkNotNull(terminal).diagnosticName()}",
        )
        return checkNotNull(terminal)
    }

    private fun nextOperationId(instanceId: String): TextOutputOperationId =
        TextOutputOperationId("$instanceId:${operationCounter.incrementAndGet()}")

}

private fun TextOutputOutcome.diagnosticName(): String = when (this) {
    is TextOutputOutcome.Delivered -> "delivered"
    is TextOutputOutcome.Rejected -> "rejected:$reason"
    is TextOutputOutcome.Failed -> "failed:$reason"
    is TextOutputOutcome.Indeterminate -> "indeterminate:$reason"
}

private const val CHANNEL_EFFECT_TAG = "SubspaceChannel"

data class TextOutputOperationId(val value: String)

sealed interface TextOutputAvailability {
    data object Available : TextOutputAvailability
    data object Preparing : TextOutputAvailability
    data object Unavailable : TextOutputAvailability
    data object Closed : TextOutputAvailability
}

sealed interface TextOutputPreparation {
    data object Available : TextOutputPreparation
    data object TimedOut : TextOutputPreparation
    data class Failed(val reason: String) : TextOutputPreparation
}


sealed interface TextOutputOutcome {
    val operationId: TextOutputOperationId

    data class Delivered(override val operationId: TextOutputOperationId) : TextOutputOutcome
    data class Rejected(
        override val operationId: TextOutputOperationId,
        val reason: TextOutputRejectionReason,
    ) : TextOutputOutcome
    data class Failed(
        override val operationId: TextOutputOperationId,
        val reason: TextOutputFailureReason,
    ) : TextOutputOutcome
    data class Indeterminate(
        override val operationId: TextOutputOperationId,
        val reason: TextOutputIndeterminateReason,
    ) : TextOutputOutcome
}

private fun TextOutputProfile.toHostProfile(): HostProfile? {
    val parts = id.split(":")
    if (parts.size !in 2..3 || parts.any { it.isBlank() }) return null
    return HostProfile(
        hostOs = parts[0],
        layout = parts[1],
        variant = parts.getOrNull(2),
    )
}

private fun TextOutputOutcome.toCapabilityOutcome(): TextDeliveryOutcome = when (this) {
    is TextOutputOutcome.Delivered -> TextDeliveryOutcome.Delivered(operationId.value)
    is TextOutputOutcome.Rejected -> TextDeliveryOutcome.Rejected(operationId.value, reason)
    is TextOutputOutcome.Failed -> TextDeliveryOutcome.Failed(operationId.value, reason)
    is TextOutputOutcome.Indeterminate -> TextDeliveryOutcome.Indeterminate(operationId.value, reason)
}
