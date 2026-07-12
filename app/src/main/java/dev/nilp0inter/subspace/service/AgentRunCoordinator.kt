package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.AsynchronousConversationCapability
import dev.nilp0inter.subspace.channel.capability.CapabilityFailureReason
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.DelayedPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.OpenAiCompletionCapability
import dev.nilp0inter.subspace.model.AcceptedAgentRun
import dev.nilp0inter.subspace.model.AgentConversationEnqueueRequest
import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.AgentRunFailureReason
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.AgentRunIndeterminateReason
import dev.nilp0inter.subspace.model.AgentRunState
import dev.nilp0inter.subspace.model.AgentRunTerminalOutcome
import dev.nilp0inter.subspace.model.DelayedPlaybackOutcome
import dev.nilp0inter.subspace.model.DelayedPlaybackRequest
import dev.nilp0inter.subspace.model.OpenAiChatOutcome
import dev.nilp0inter.subspace.model.OpenAiChatRequest
import dev.nilp0inter.subspace.model.OpenAiMessage
import dev.nilp0inter.subspace.model.OpenAiToolCall
import dev.nilp0inter.subspace.model.OpenAiToolDefinition
import dev.nilp0inter.subspace.model.OpenAiToolResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Immutable host policy; no request, response, credential, or tool payload can enter diagnostics. */
data class AgentRunLimits(
    val maximumUserTextBytes: Int = 16 * 1024,
    val maximumRequestBytes: Int = 64 * 1024,
    val maximumAssistantTextBytes: Int = 32 * 1024,
    val maximumModelTurns: Int = 8,
    val maximumToolCalls: Int = 16,
    val operationTimeoutMillis: Long = 60_000,
    val maximumRunElapsedMillis: Long = 180_000,
) {
    init {
        require(maximumUserTextBytes > 0)
        require(maximumRequestBytes > 0)
        require(maximumAssistantTextBytes > 0)
        require(maximumModelTurns > 0)
        require(maximumToolCalls > 0)
        require(operationTimeoutMillis > 0)
        require(maximumRunElapsedMillis >= operationTimeoutMillis)
    }
}

/** A resolver is host-owned; the coordinator retains only the durable configuration snapshot. */
fun interface AgentRunConfigurationResolver {
    fun resolve(scope: CapabilityScopeIdentity): AgentRunCoordinatorConfiguration?
}

data class AgentRunCoordinatorConfiguration(
    val durable: DurableAgentConfiguration,
    val tools: List<OpenAiToolDefinition>,
)

/** The coordinator invokes one semantic tool call at a time. */
fun interface AgentToolExecutionPort {
    suspend fun execute(context: AgentOperationContext, call: OpenAiToolCall): OpenAiToolResult
}

enum class AgentRunDiagnosticCode {
    ADMISSION_REJECTED,
    STORE_FAILURE,
    LIMIT_EXCEEDED,
    COMPLETION_UNAVAILABLE,
    COMPLETION_FAILED,
    COMPLETION_CANCELLED,
    COMPLETION_STALE,
    TOOL_LOOP_LIMIT,
    PLAYBACK_FAILED,
    PLAYBACK_CANCELLED,
    PLAYBACK_STALE,
    REPLACED,
    REMOVED,
    SHUTDOWN,
}

data class AgentRunDiagnostic(
    val channelInstanceId: String,
    val runId: AgentRunId?,
    val code: AgentRunDiagnosticCode,
)

fun interface AgentRunDiagnosticSink {
    fun record(diagnostic: AgentRunDiagnostic)
}

/** Privacy-safe, aggregate projection; message and tool text intentionally never appear here. */
data class AgentChannelRunStatus(
    val state: AgentRunState,
    val queuedTurnCount: Int,
    val pendingResponseCount: Int,
    val playbackPaused: Boolean = false,
    val terminalOutcome: AgentRunTerminalOutcome? = null,
)

/**
 * Host-owned serialized coordinator for durable agent work.
 *
 * One [Job] is admitted for each channel at a time; the durable store supplies the FIFO
 * head rule, while separate channel jobs allow independent progress. Conversation messages
 * are deliberately process-local and are never rebuilt from durable history.
 */
class AgentRunCoordinator(
    private val scope: CoroutineScope,
    private val store: DurableAgentRunStore,
    private val configurationResolver: AgentRunConfigurationResolver,
    private val completion: OpenAiCompletionCapability,
    private val tools: AgentToolExecutionPort,
    private val playback: DelayedPlaybackCapability,
    private val limits: AgentRunLimits = AgentRunLimits(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val diagnostics: AgentRunDiagnosticSink = AgentRunDiagnosticSink {},
) : AsynchronousConversationCapability {
    private val mutex = Mutex()
    private val channelWorkers = mutableMapOf<String, Job>()
    private val lifecycles = mutableMapOf<String, ChannelLifecycle>()
    private val conversations = mutableMapOf<String, MutableList<OpenAiMessage>>()
    private var closed = false

    private val _status = MutableStateFlow<Map<String, AgentChannelRunStatus>>(emptyMap())
    val status: StateFlow<Map<String, AgentChannelRunStatus>> = _status.asStateFlow()

    override suspend fun enqueue(
        scope: CapabilityScopeIdentity,
        request: AgentConversationEnqueueRequest,
    ): CapabilityOperationResult<AcceptedAgentRun> = mutex.withLock {
        if (closed) return CapabilityOperationResult.Closed
        val configuration = configurationResolver.resolve(scope)
            ?: return CapabilityOperationResult.Unavailable(dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.NOT_CONFIGURED)
        if (utf8Bytes(request.userText) > limits.maximumUserTextBytes) {
            diagnostic(scope.channelInstanceId, null, AgentRunDiagnosticCode.LIMIT_EXCEEDED)
            return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        }
        val lifecycle = lifecycleFor(scope, configuration.durable.configurationFingerprint)
        val runId = AgentRunId(newId())
        val messageId = AgentMessageId(newId())
        val operationId = AgentOperationId(newId())
        when (val admitted = store.admit(
            messageId = messageId,
            runId = runId,
            channelInstanceId = scope.channelInstanceId,
            conversationEpoch = lifecycle.conversationEpoch,
            configurationEpoch = lifecycle.configurationEpoch,
            configuration = configuration.durable,
            text = request.userText,
            admittedAtMillis = nowMillis(),
        )) {
            is DurableAgentStoreResult.Failure -> {
                diagnostic(scope.channelInstanceId, runId, AgentRunDiagnosticCode.STORE_FAILURE)
                return CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
            }
            is DurableAgentStoreResult.Success -> Unit
        }
        publishStatusLocked(scope.channelInstanceId, null)
        ensureWorkerLocked(scope.channelInstanceId, lifecycle.token)
        CapabilityOperationResult.Success(AcceptedAgentRun(runId, messageId, operationId))
    }

    /** Reconciles durable state and adopts only the store's explicitly resumable work. */
    suspend fun start() = mutex.withLock {
        if (closed) return
        when (val recovered = store.reconcileAfterRestart()) {
            is DurableAgentStoreResult.Failure -> diagnostic("host", null, AgentRunDiagnosticCode.STORE_FAILURE)
            is DurableAgentStoreResult.Success -> {
                val snapshot = store.snapshot()
                snapshot.runs.filter { it.id in recovered.value.resumableRunIds }.groupBy { it.channelInstanceId }.forEach { (channel, runs) ->
                    // A restart always opens fresh volatile context. Stored request snapshots remain durable.
                    val epoch = (snapshot.channelEpochs[channel] ?: -1L) + 1L
                    lifecycles[channel] = ChannelLifecycle(null, epoch, snapshot.configurationEpochs[channel] ?: 0L, "", newId())
                    conversations.remove(channel)
                    publishStatusLocked(channel, null)
                    ensureWorkerLocked(channel, lifecycles.getValue(channel).token)
                }
            }
        }
    }

    /** Replacement/removal revokes old publication authority before atomically retiring durable work. */
    suspend fun replace(scope: CapabilityScopeIdentity, removed: Boolean = false) = mutex.withLock {
        val channel = scope.channelInstanceId
        val previous = lifecycles[channel]
        previous?.let {
            lifecycles[channel] = it.copy(token = newId())
            channelWorkers.remove(channel)?.cancel()
            diagnostic(channel, null, if (removed) AgentRunDiagnosticCode.REMOVED else AgentRunDiagnosticCode.REPLACED)
        }
        val conversationEpoch = when (val retirement = store.resetConversationEpoch(channel)) {
            is DurableAgentStoreResult.Success -> retirement.value.conversationEpoch
            is DurableAgentStoreResult.Failure -> {
                diagnostic(channel, null, AgentRunDiagnosticCode.STORE_FAILURE)
                previous?.conversationEpoch?.plus(1) ?: 0L
            }
        }
        val configurationEpoch = store.advanceConfigurationEpoch(channel).valueOr(previous?.configurationEpoch?.plus(1) ?: 0L)
        val configuration = if (removed) null else configurationResolver.resolve(scope)
        lifecycles[channel] = ChannelLifecycle(
            scope = configuration?.let { scope },
            conversationEpoch = conversationEpoch,
            configurationEpoch = configurationEpoch,
            configurationFingerprint = configuration?.durable?.configurationFingerprint.orEmpty(),
            token = newId(),
        )
        conversations.remove(channel)
        publishStatusLocked(channel, null)
    }

    /** SOS is a conversation-only reset; terminal inbound messages stay durable and playable. */
    override suspend fun resetConversation(scope: CapabilityScopeIdentity) = resetConversation(scope.channelInstanceId)

    /** SOS is a conversation-only reset; terminal inbound messages stay durable and playable. */
    suspend fun resetConversation(channelInstanceId: String) = mutex.withLock {
        val previous = lifecycles[channelInstanceId] ?: return
        // Revoke publication and stop the worker first; the store atomically classifies every
        // old run as cancelled or indeterminate according to its exact-once effect envelope.
        lifecycles[channelInstanceId] = previous.copy(token = newId())
        channelWorkers.remove(channelInstanceId)?.cancel()
        when (val reset = store.resetConversationEpoch(channelInstanceId)) {
            is DurableAgentStoreResult.Success -> {
                lifecycles[channelInstanceId] = previous.copy(
                    conversationEpoch = reset.value.conversationEpoch,
                    token = newId(),
                )
                conversations.remove(channelInstanceId)
                diagnostic(channelInstanceId, null, AgentRunDiagnosticCode.REPLACED)
                publishStatusLocked(channelInstanceId, null)
            }
            is DurableAgentStoreResult.Failure -> {
                diagnostic(channelInstanceId, null, AgentRunDiagnosticCode.STORE_FAILURE)
            }
        }
    }

    /** Cancellation is intentionally non-terminal: the durable ledger remains recoverable at next start. */
    suspend fun shutdown() = mutex.withLock {
        if (closed) return
        closed = true
        channelWorkers.values.forEach { it.cancel() }
        channelWorkers.clear()
        lifecycles.keys.forEach { diagnostic(it, null, AgentRunDiagnosticCode.SHUTDOWN) }
        conversations.clear()
        publishAllLocked()
    }

    /** Reprojects durable state after host-owned playback commits without exposing message content. */
    suspend fun refreshProjection() = mutex.withLock { publishAllLocked() }

    private fun ensureWorkerLocked(channel: String, token: String) {
        if (channelWorkers[channel]?.isActive == true) return
        channelWorkers[channel] = scope.launch {
            try {
                drain(channel, token)
            } finally {
                mutex.withLock {
                    if (channelWorkers[channel] === coroutineContext[Job]) channelWorkers.remove(channel)
                    publishStatusLocked(channel, null)
                }
            }
        }
    }

    private suspend fun drain(channel: String, token: String) {
        while (isCurrent(channel, token)) {
            val run = store.snapshot().runs.asSequence()
                .filter { it.channelInstanceId == channel && !it.state.isTerminal }
                .minByOrNull { it.queueSequence } ?: return
            if (!process(run, token)) return
        }
    }


    private suspend fun process(run: DurableAgentRun, token: String): Boolean {
        if (!isCurrent(run.channelInstanceId, token)) return false
        val lifecycle = mutex.withLock { lifecycles[run.channelInstanceId] } ?: return false
        val resolved = lifecycle.scope?.let(configurationResolver::resolve)
        val configuration = resolved ?: AgentRunCoordinatorConfiguration(run.configuration, emptyList())
        if (lifecycle.scope != null && configuration.durable.configurationFingerprint != lifecycle.configurationFingerprint) return false
        if (store.beginRun(run.id) is DurableAgentStoreResult.Failure) return false
        publish(run.channelInstanceId, null)

        val conversation = mutex.withLock { conversations.getOrPut(run.channelInstanceId) { mutableListOf() } }
        if (conversation.isEmpty()) conversation += OpenAiMessage.System(run.configuration.systemPrompt)
        val source = store.snapshot().messages.firstOrNull { it.id == run.sourceMessageId } ?: return fail(run, token, AgentRunDiagnosticCode.STORE_FAILURE)
        conversation += OpenAiMessage.User(source.text)
        var toolCalls = 0
        val deadlineMillis = nowMillis() + limits.maximumRunElapsedMillis
        repeat(limits.maximumModelTurns) { turn ->
            if (!isCurrent(run.channelInstanceId, token)) return false
            if (nowMillis() >= deadlineMillis || requestBytes(conversation, configuration.tools) > limits.maximumRequestBytes) return fail(run, token, AgentRunDiagnosticCode.LIMIT_EXCEEDED)
            val operationId = AgentOperationId(newId())
            if (store.recordOutboundEnvelope(run.id, operationId, if (turn == 0) DurableContinuation.INITIAL_REQUEST else DurableContinuation.TOOL_RESULT_CONTINUATION) is DurableAgentStoreResult.Failure ||
                store.markRemoteSubmissionStarted(run.id) is DurableAgentStoreResult.Failure
            ) return fail(run, token, AgentRunDiagnosticCode.STORE_FAILURE)
            val outcome = try {
                withTimeout(minOf(limits.operationTimeoutMillis, (deadlineMillis - nowMillis()).coerceAtLeast(1L))) {
                    completion.complete(AgentOperationContext(lifecycle.scope ?: CapabilityScopeIdentity(run.channelInstanceId, dev.nilp0inter.subspace.channel.capability.RuntimeGeneration(0)), run.id, operationId),
                        OpenAiChatRequest(dev.nilp0inter.subspace.model.OpenAiConnectionProfileId(run.configuration.connectionProfileId), dev.nilp0inter.subspace.model.OpenAiModelId(run.configuration.modelId), conversation.toList(), configuration.tools, parallelToolCalls = false))
                }
            } catch (_: CancellationException) { throw CancellationException() }
              catch (_: Exception) { return indeterminate(run, token, AgentRunDiagnosticCode.COMPLETION_FAILED) }
            if (store.acknowledgeRemoteCompletion(run.id) is DurableAgentStoreResult.Failure) return fail(run, token, AgentRunDiagnosticCode.STORE_FAILURE)
            if (!isCurrent(run.channelInstanceId, token)) return false
            when (outcome) {
                is OpenAiChatOutcome.FinalAssistantMessage -> {
                    if (utf8Bytes(outcome.text) > limits.maximumAssistantTextBytes) return fail(run, token, AgentRunDiagnosticCode.LIMIT_EXCEEDED)
                    conversation += OpenAiMessage.Assistant(outcome.text)
                    return completeWithPlayback(run, lifecycle, token, outcome.text)
                }
                is OpenAiChatOutcome.ToolCalls -> {
                    conversation += OpenAiMessage.Assistant(null, outcome.calls)
                    for (call in outcome.calls) {
                        if (++toolCalls > limits.maximumToolCalls) return fail(run, token, AgentRunDiagnosticCode.TOOL_LOOP_LIMIT)
                        val result = try {
                            withTimeout(minOf(limits.operationTimeoutMillis, (deadlineMillis - nowMillis()).coerceAtLeast(1L))) {
                                tools.execute(AgentOperationContext(lifecycle.scope ?: CapabilityScopeIdentity(run.channelInstanceId, dev.nilp0inter.subspace.channel.capability.RuntimeGeneration(0)), run.id, AgentOperationId(newId())), call)
                            }
                        } catch (_: CancellationException) { throw CancellationException() }
                          catch (_: Exception) { return fail(run, token, AgentRunDiagnosticCode.STORE_FAILURE) }
                        if (!isCurrent(run.channelInstanceId, token)) return false
                        conversation += OpenAiMessage.Tool(result)
                    }
                }
                is OpenAiChatOutcome.Unavailable -> return fail(run, token, AgentRunDiagnosticCode.COMPLETION_UNAVAILABLE)
                is OpenAiChatOutcome.Failed -> return fail(run, token, AgentRunDiagnosticCode.COMPLETION_FAILED)
                OpenAiChatOutcome.Cancelled -> return cancel(run, token, AgentRunDiagnosticCode.COMPLETION_CANCELLED)
                OpenAiChatOutcome.Stale -> return cancel(run, token, AgentRunDiagnosticCode.COMPLETION_STALE)
            }
        }
        return fail(run, token, AgentRunDiagnosticCode.TOOL_LOOP_LIMIT)
    }
    private suspend fun completeWithPlayback(run: DurableAgentRun, lifecycle: ChannelLifecycle, token: String, text: String): Boolean {
        val responseId = AgentMessageId(newId())
        if (store.commitInboundResponse(run.id, responseId, text, nowMillis()) is DurableAgentStoreResult.Failure) return fail(run, token, AgentRunDiagnosticCode.STORE_FAILURE)
        if (!isCurrent(run.channelInstanceId, token)) return false
        val result = try {
            withTimeout(limits.operationTimeoutMillis) {
                playback.schedule(AgentOperationContext(lifecycle.scope ?: CapabilityScopeIdentity(run.channelInstanceId, dev.nilp0inter.subspace.channel.capability.RuntimeGeneration(0)), run.id, AgentOperationId(newId())), DelayedPlaybackRequest(responseId, text))
            }
        } catch (_: CancellationException) { throw CancellationException() }
          catch (_: Exception) { DelayedPlaybackOutcome.Failed(dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason.HOST_FAILURE) }
        if (!isCurrent(run.channelInstanceId, token)) return false
        when (result) {
            is DelayedPlaybackOutcome.Heard -> store.markHeard(responseId)
            is DelayedPlaybackOutcome.Pending, is DelayedPlaybackOutcome.Playing -> Unit
            is DelayedPlaybackOutcome.Failed -> diagnostic(run.channelInstanceId, run.id, AgentRunDiagnosticCode.PLAYBACK_FAILED)
            DelayedPlaybackOutcome.Cancelled -> diagnostic(run.channelInstanceId, run.id, AgentRunDiagnosticCode.PLAYBACK_CANCELLED)
            DelayedPlaybackOutcome.Stale -> diagnostic(run.channelInstanceId, run.id, AgentRunDiagnosticCode.PLAYBACK_STALE)
        }
        publish(run.channelInstanceId, AgentRunTerminalOutcome.Completed)
        return true
    }

    private suspend fun fail(run: DurableAgentRun, token: String, code: AgentRunDiagnosticCode): Boolean {
        if (!isCurrent(run.channelInstanceId, token)) return false
        store.markRunTerminal(run.id, DurableRunTerminal(DurableRunState.FAILED, DurableRunTerminalReason.REMOTE_FAILURE))
        diagnostic(run.channelInstanceId, run.id, code)
        publish(run.channelInstanceId, AgentRunTerminalOutcome.Failed(AgentRunFailureReason.COMPLETION_FAILED))
        return true
    }

    private suspend fun cancel(run: DurableAgentRun, token: String, code: AgentRunDiagnosticCode): Boolean {
        if (!isCurrent(run.channelInstanceId, token)) return false
        store.markRunTerminal(run.id, DurableRunTerminal(DurableRunState.CANCELLED, DurableRunTerminalReason.CANCELLED))
        diagnostic(run.channelInstanceId, run.id, code)
        publish(run.channelInstanceId, AgentRunTerminalOutcome.Cancelled)
        return true
    }

    private suspend fun indeterminate(run: DurableAgentRun, token: String, code: AgentRunDiagnosticCode): Boolean {
        if (!isCurrent(run.channelInstanceId, token)) return false
        store.markRunTerminal(run.id, DurableRunTerminal(DurableRunState.INDETERMINATE, DurableRunTerminalReason.AMBIGUOUS_REMOTE_SUBMISSION))
        diagnostic(run.channelInstanceId, run.id, code)
        publish(run.channelInstanceId, AgentRunTerminalOutcome.Indeterminate(AgentRunIndeterminateReason.REMOTE_EFFECT_UNCONFIRMED))
        return true
    }

    private fun lifecycleFor(scope: CapabilityScopeIdentity, fingerprint: String): ChannelLifecycle =
        lifecycles.getOrPut(scope.channelInstanceId) {
            val snapshot = store.snapshot()
            ChannelLifecycle(scope, snapshot.channelEpochs[scope.channelInstanceId] ?: 0L, snapshot.configurationEpochs[scope.channelInstanceId] ?: 0L, fingerprint, newId())
        }


    private fun isCurrent(channel: String, token: String): Boolean = !closed && lifecycles[channel]?.token == token
    private fun diagnostic(channel: String, run: AgentRunId?, code: AgentRunDiagnosticCode) = diagnostics.record(AgentRunDiagnostic(channel, run, code))
    private suspend fun publish(channel: String, terminal: AgentRunTerminalOutcome?) = mutex.withLock { publishStatusLocked(channel, terminal) }
    private fun publishAllLocked() = store.snapshot().runs.map { it.channelInstanceId }.toSet().forEach { publishStatusLocked(it, null) }
    private fun publishStatusLocked(channel: String, terminal: AgentRunTerminalOutcome?) {
        val snapshot = store.snapshot()
        val runs = snapshot.runs.filter { it.channelInstanceId == channel }
        val active = runs.filterNot { it.state.isTerminal }.minByOrNull { it.queueSequence }
        val durableTerminal = runs.asSequence().filter { it.state.isTerminal }.maxByOrNull { it.queueSequence }?.let { latest ->
            if (latest.state == DurableRunState.CANCELLED) {
                val significant = runs.asSequence()
                    .filter { it.state.isTerminal && (it.state == DurableRunState.INDETERMINATE || it.state == DurableRunState.FAILED) }
                    .maxByOrNull { it.queueSequence }
                significant?.toTerminalOutcome() ?: latest.toTerminalOutcome()
            } else {
                latest.toTerminalOutcome()
            }
        }
        val effectiveTerminal = terminal ?: durableTerminal
        val pending = snapshot.messages.count { it.channelInstanceId == channel && it.direction == DurableMessageDirection.INBOUND && it.lifecycle == DurableMessageLifecycle.PENDING }
        _status.value = _status.value + (channel to AgentChannelRunStatus(
            state = active?.let { if (it.state == DurableRunState.WAITING_FOR_TOOL) AgentRunState.WAITING_FOR_TOOL else if (it.state == DurableRunState.QUEUED) AgentRunState.QUEUED else AgentRunState.RUNNING } ?: effectiveTerminal?.toState() ?: AgentRunState.COMPLETED,
            queuedTurnCount = runs.count { it.state == DurableRunState.QUEUED },
            pendingResponseCount = pending,
            playbackPaused = snapshot.channelDrainStates[channel] == PlaybackDrainState.PAUSED_BY_USER,
            terminalOutcome = effectiveTerminal,
        ))
    }

    private fun DurableAgentRun.toTerminalOutcome(): AgentRunTerminalOutcome = when (state) {
        DurableRunState.COMPLETED -> AgentRunTerminalOutcome.Completed
        DurableRunState.FAILED -> AgentRunTerminalOutcome.Failed(AgentRunFailureReason.COMPLETION_FAILED)
        DurableRunState.CANCELLED -> AgentRunTerminalOutcome.Cancelled
        DurableRunState.INDETERMINATE -> AgentRunTerminalOutcome.Indeterminate(AgentRunIndeterminateReason.REMOTE_EFFECT_UNCONFIRMED)
        else -> error("A nonterminal run cannot provide a terminal outcome")
    }

    private fun AgentRunTerminalOutcome.toState(): AgentRunState = when (this) {
        AgentRunTerminalOutcome.Completed -> AgentRunState.COMPLETED
        is AgentRunTerminalOutcome.Failed -> AgentRunState.FAILED
        AgentRunTerminalOutcome.Cancelled -> AgentRunState.CANCELLED
        is AgentRunTerminalOutcome.Indeterminate -> AgentRunState.INDETERMINATE
    }

    private data class ChannelLifecycle(val scope: CapabilityScopeIdentity?, val conversationEpoch: Long, val configurationEpoch: Long, val configurationFingerprint: String, val token: String)
}

private fun DurableAgentStoreResult<Long>.valueOr(fallback: Long): Long = (this as? DurableAgentStoreResult.Success)?.value ?: fallback
private fun utf8Bytes(value: String): Int { var total = 0; for (c in value) total += when { c.code <= 0x7f -> 1; c.code <= 0x7ff -> 2; c.isSurrogate() -> 2; else -> 3 }; return total }
private fun requestBytes(messages: List<OpenAiMessage>, tools: List<OpenAiToolDefinition>): Int = messages.sumOf { when (it) { is OpenAiMessage.System -> utf8Bytes(it.text); is OpenAiMessage.User -> utf8Bytes(it.text); is OpenAiMessage.Assistant -> (it.text?.let(::utf8Bytes) ?: 0) + it.toolCalls.sumOf { call -> utf8Bytes(call.id.value) + utf8Bytes(call.name.value) }; is OpenAiMessage.Tool -> utf8Bytes(it.result.callId.value) } } + tools.sumOf { utf8Bytes(it.name.value) + utf8Bytes(it.description) }
