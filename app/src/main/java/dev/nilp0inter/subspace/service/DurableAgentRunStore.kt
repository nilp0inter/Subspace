package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.AgentToolCallId
import dev.nilp0inter.subspace.model.AgentOperationId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-owned, crash-safe ledger for asynchronous agent work.
 *
 * This is deliberately a durable execution ledger, not a conversation transcript.  The
 * coordinator owns the in-memory conversation; [reconcileAfterRestart] never returns message
 * text as context and retains an active envelope only until the associated run terminates.
 */
class DurableAgentRunStore(
    private val file: File,
) {
    private val lock = Any()
    private var snapshot: DurableAgentStoreSnapshot = DurableAgentStoreSnapshot.empty()

    fun load(): DurableAgentStoreResult<DurableAgentStoreSnapshot> = synchronized(lock) {
        if (!file.exists() || file.length() == 0L) {
            snapshot = DurableAgentStoreSnapshot.empty()
            return DurableAgentStoreResult.Success(snapshot)
        }
        val decoded = try {
            decode(file.readText()).also { loaded ->
                validate(loaded)?.let { failure -> throw IllegalArgumentException(failure.toString()) }
            }
        } catch (error: Exception) {
            return DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Malformed(error.message ?: "Unable to read agent ledger"))
        }
        snapshot = decoded
        DurableAgentStoreResult.Success(snapshot)
    }

    fun snapshot(): DurableAgentStoreSnapshot = synchronized(lock) { snapshot }

    /** Commits the user message and its queued run as one durable admission transaction. */
    fun admit(
        messageId: AgentMessageId,
        runId: AgentRunId,
        channelInstanceId: String,
        conversationEpoch: Long,
        configurationEpoch: Long,
        configuration: DurableAgentConfiguration,
        text: String,
        admittedAtMillis: Long,
    ): DurableAgentStoreResult<DurableAdmission> = transact { current ->
        requireNonBlank(channelInstanceId, "channel instance ID")
        require(conversationEpoch >= 0L) { "Conversation epoch must be non-negative" }
        require(configurationEpoch >= 0L) { "Configuration epoch must be non-negative" }
        require(text.isNotBlank()) { "Outbound message text must not be blank" }

        val existingMessage = current.messages.firstOrNull { it.id == messageId }
        val existingRun = current.runs.firstOrNull { it.id == runId }
        if (existingMessage != null || existingRun != null) {
            if (existingMessage?.runId == runId && existingRun?.sourceMessageId == messageId &&
                existingMessage.channelInstanceId == channelInstanceId && existingRun.channelInstanceId == channelInstanceId
            ) {
                return@transact DurableAgentStoreResult.Success(DurableMutation(current, DurableAdmission.Duplicate(existingRun)))
            }
            return@transact DurableAgentStoreResult.Failure(
                DurableAgentStoreFailure.Conflict("Admission identity is already associated with different durable work"),
            )
        }

        val nextSequence = (current.runs.asSequence()
            .filter { it.channelInstanceId == channelInstanceId }
            .maxOfOrNull { it.queueSequence } ?: -1L) + 1L
        val message = DurableAgentMessage(
            id = messageId,
            channelInstanceId = channelInstanceId,
            conversationEpoch = conversationEpoch,
            direction = DurableMessageDirection.OUTBOUND,
            text = text,
            createdAtMillis = admittedAtMillis,
            lifecycle = DurableMessageLifecycle.QUEUED,
            runId = runId,
        )
        val run = DurableAgentRun(
            id = runId,
            channelInstanceId = channelInstanceId,
            conversationEpoch = conversationEpoch,
            configurationEpoch = configurationEpoch,
            queueSequence = nextSequence,
            sourceMessageId = messageId,
            configuration = configuration,
            state = DurableRunState.QUEUED,
            terminalReason = null,
            recovery = DurableRecoveryEnvelope.empty(),
        )
        val next = current.copy(
            messages = current.messages + message,
            runs = current.runs + run,
        )
        DurableAgentStoreResult.Success(DurableMutation(next, DurableAdmission.Accepted(run)))
    }

    /** Marks the FIFO head active. A non-head run cannot start. */
    fun beginRun(runId: AgentRunId): DurableAgentStoreResult<DurableAgentRun> = updateRun(runId) { current, run ->
        if (run.state == DurableRunState.ACTIVE || run.state == DurableRunState.WAITING_FOR_TOOL) {
            return@updateRun DurableAgentStoreResult.Success(DurableMutation(current, run))
        }
        if (run.state != DurableRunState.QUEUED) return@updateRun invalidTransition(run, "begin")
        val head = current.runs.asSequence()
            .filter { it.channelInstanceId == run.channelInstanceId && !it.state.isTerminal }
            .minByOrNull { it.queueSequence }
        if (head?.id != run.id) {
            return@updateRun DurableAgentStoreResult.Failure(DurableAgentStoreFailure.NotQueueHead(run.id))
        }
        val updated = run.copy(state = DurableRunState.ACTIVE)
        val next = replaceRun(current, updated).mapSourceMessage(run.id) { it.copy(lifecycle = DurableMessageLifecycle.PROCESSING) }
        DurableAgentStoreResult.Success(DurableMutation(next, updated))
    }

    /** Persists the outbound request envelope before the remote submission can begin. */
    fun recordOutboundEnvelope(
        runId: AgentRunId,
        operationId: AgentOperationId,
        continuation: DurableContinuation,
    ): DurableAgentStoreResult<DurableAgentRun> = updateRun(runId) { current, run ->
        if (run.state != DurableRunState.ACTIVE && run.state != DurableRunState.WAITING_FOR_TOOL) {
            return@updateRun invalidTransition(run, "record outbound envelope")
        }
        val recovery = run.recovery
        if (recovery.remoteSubmission == DurableRemoteSubmission.SUBMITTED_OR_AMBIGUOUS) {
            return@updateRun DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Conflict("A remote submission is already in flight"))
        }
        val updated = run.copy(
            recovery = recovery.copy(
                operationId = operationId,
                continuation = continuation,
                remoteSubmission = DurableRemoteSubmission.NOT_SUBMITTED,
            ),
        )
        DurableAgentStoreResult.Success(DurableMutation(replaceRun(current, updated), updated))
    }

    /** Must be committed immediately before crossing the remote-effect boundary. */
    fun markRemoteSubmissionStarted(runId: AgentRunId): DurableAgentStoreResult<DurableAgentRun> = updateRun(runId) { current, run ->
        if (run.recovery.remoteSubmission == DurableRemoteSubmission.SUBMITTED_OR_AMBIGUOUS) {
            return@updateRun DurableAgentStoreResult.Success(DurableMutation(current, run))
        }
        if (run.recovery.remoteSubmission != DurableRemoteSubmission.NOT_SUBMITTED) {
            return@updateRun invalidTransition(run, "start remote submission")
        }
        val updated = run.copy(recovery = run.recovery.copy(remoteSubmission = DurableRemoteSubmission.SUBMITTED_OR_AMBIGUOUS))
        DurableAgentStoreResult.Success(DurableMutation(replaceRun(current, updated), updated))
    }

    /**
     * Commits receipt of a normalized remote completion before processing its next local stage.
     * The persisted continuation stays available, while the remote-effect ambiguity window closes.
     */
    fun acknowledgeRemoteCompletion(runId: AgentRunId): DurableAgentStoreResult<DurableAgentRun> = updateRun(runId) { current, run ->
        when (run.recovery.remoteSubmission) {
            DurableRemoteSubmission.NONE -> DurableAgentStoreResult.Success(DurableMutation(current, run))
            DurableRemoteSubmission.NOT_SUBMITTED -> invalidTransition(run, "acknowledge remote completion")
            DurableRemoteSubmission.SUBMITTED_OR_AMBIGUOUS -> {
                val updated = run.copy(recovery = run.recovery.copy(remoteSubmission = DurableRemoteSubmission.NONE))
                DurableAgentStoreResult.Success(DurableMutation(replaceRun(current, updated), updated))
            }
        }
    }

    /**
     * Reserves a call before its native effect. Same call ID and argument hash is idempotent;
     * different data for that identity is a typed conflict.
     */
    fun reserveToolCall(
        runId: AgentRunId,
        callId: AgentToolCallId,
        toolName: String,
        argumentHash: String,
        operationId: AgentOperationId,
    ): DurableAgentStoreResult<DurableToolReservation> = transact { current ->
        val run = current.runs.firstOrNull { it.id == runId }
            ?: return@transact DurableAgentStoreResult.Failure(DurableAgentStoreFailure.UnknownRun(runId))
        if (run.state != DurableRunState.ACTIVE && run.state != DurableRunState.WAITING_FOR_TOOL) {
            return@transact invalidTransition(run, "reserve tool call")
        }
        val existing = current.toolCalls.firstOrNull { it.runId == runId && it.callId == callId }
        if (existing != null) {
            return@transact if (existing.toolName == toolName && existing.argumentHash == argumentHash) {
                DurableAgentStoreResult.Success(DurableMutation(current, DurableToolReservation.Existing(existing)))
            } else {
                DurableAgentStoreResult.Failure(DurableAgentStoreFailure.ToolCallConflict(runId, callId))
            }
        }
        val entry = DurableToolCallLedgerEntry(
            runId = runId,
            channelInstanceId = run.channelInstanceId,
            callId = callId,
            toolName = toolName,
            argumentHash = argumentHash,
            operationId = operationId,
            state = DurableToolCallState.RESERVED,
            terminalResult = null,
        )
        val nextRun = run.copy(state = DurableRunState.WAITING_FOR_TOOL)
        val next = replaceRun(current.copy(toolCalls = current.toolCalls + entry), nextRun)
        DurableAgentStoreResult.Success(DurableMutation(next, DurableToolReservation.Reserved(entry)))
    }

    /** Must commit before beginning a native tool effect; recovery treats it as non-replayable. */
    fun markToolEffectStarted(
        runId: AgentRunId,
        callId: AgentToolCallId,
    ): DurableAgentStoreResult<DurableToolCallLedgerEntry> = updateToolCall(runId, callId) { current, entry ->
        when (entry.state) {
            DurableToolCallState.EFFECT_STARTED -> DurableAgentStoreResult.Success(DurableMutation(current, entry))
            DurableToolCallState.RESERVED -> {
                val updated = entry.copy(state = DurableToolCallState.EFFECT_STARTED)
                DurableAgentStoreResult.Success(DurableMutation(replaceTool(current, updated), updated))
            }
            DurableToolCallState.RESULT_RECORDED, DurableToolCallState.INDETERMINATE -> invalidToolTransition(entry, "start effect")
        }
    }

    /** Persists exactly one terminal model-safe result. */
    fun recordToolResult(
        runId: AgentRunId,
        callId: AgentToolCallId,
        result: DurableToolResult,
    ): DurableAgentStoreResult<DurableToolCallLedgerEntry> = updateToolCall(runId, callId) { current, entry ->
        if (entry.state == DurableToolCallState.RESULT_RECORDED) {
            return@updateToolCall if (entry.terminalResult == result) {
                DurableAgentStoreResult.Success(DurableMutation(current, entry))
            } else {
                DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Conflict("Tool call already has a different terminal result"))
            }
        }
        if (entry.state == DurableToolCallState.INDETERMINATE) return@updateToolCall invalidToolTransition(entry, "record result")
        val updated = entry.copy(state = DurableToolCallState.RESULT_RECORDED, terminalResult = result)
        val run = current.runs.first { it.id == runId }
        val next = replaceRun(replaceTool(current, updated), run.copy(state = DurableRunState.ACTIVE))
        DurableAgentStoreResult.Success(DurableMutation(next, updated))
    }

    /** Atomically records the final inbound text and terminal run; response text begins pending. */
    fun commitInboundResponse(
        runId: AgentRunId,
        messageId: AgentMessageId,
        text: String,
        createdAtMillis: Long,
    ): DurableAgentStoreResult<DurableAgentMessage> = transact { current ->
        require(text.isNotBlank()) { "Inbound message text must not be blank" }
        val run = current.runs.firstOrNull { it.id == runId }
            ?: return@transact DurableAgentStoreResult.Failure(DurableAgentStoreFailure.UnknownRun(runId))
        val existing = current.messages.firstOrNull { it.id == messageId }
        if (existing != null) {
            return@transact if (existing.runId == runId && existing.direction == DurableMessageDirection.INBOUND && existing.text == text) {
                DurableAgentStoreResult.Success(DurableMutation(current, existing))
            } else {
                DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Conflict("Inbound message ID conflicts with an existing record"))
            }
        }
        if (run.state.isTerminal) return@transact invalidTransition(run, "commit inbound response")
        val inbound = DurableAgentMessage(
            id = messageId,
            channelInstanceId = run.channelInstanceId,
            conversationEpoch = run.conversationEpoch,
            direction = DurableMessageDirection.INBOUND,
            text = text,
            createdAtMillis = createdAtMillis,
            lifecycle = DurableMessageLifecycle.PENDING,
            runId = runId,
        )
        val terminal = run.copy(state = DurableRunState.COMPLETED, terminalReason = null, recovery = DurableRecoveryEnvelope.empty())
        val next = replaceRun(
            current.copy(messages = current.messages + inbound).mapSourceMessage(runId) { it.copy(lifecycle = DurableMessageLifecycle.COMPLETED) },
            terminal,
        )
        DurableAgentStoreResult.Success(DurableMutation(next, inbound))
    }

    fun markRunTerminal(runId: AgentRunId, terminal: DurableRunTerminal): DurableAgentStoreResult<DurableAgentRun> = updateRun(runId) { current, run ->
        if (run.state.isTerminal) {
            return@updateRun if (run.state == terminal.state && run.terminalReason == terminal.reason) {
                DurableAgentStoreResult.Success(DurableMutation(current, run))
            } else {
                DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Conflict("Run already reached a different terminal outcome"))
            }
        }
        val updated = run.copy(state = terminal.state, terminalReason = terminal.reason, recovery = DurableRecoveryEnvelope.empty())
        val next = replaceRun(current, updated).mapSourceMessage(runId) { it.copy(lifecycle = terminal.messageLifecycle) }
        DurableAgentStoreResult.Success(DurableMutation(next, updated))
    }

    fun beginPlayback(messageId: AgentMessageId): DurableAgentStoreResult<DurableAgentMessage> = updateMessage(messageId) { current, message ->
        when (message.lifecycle) {
            DurableMessageLifecycle.PLAYING -> DurableAgentStoreResult.Success(DurableMutation(current, message))
            DurableMessageLifecycle.PENDING -> {
                val updated = message.copy(lifecycle = DurableMessageLifecycle.PLAYING)
                DurableAgentStoreResult.Success(DurableMutation(replaceMessage(current, updated), updated))
            }
            else -> invalidMessageTransition(message, "begin playback")
        }
    }

    /** Heard is monotonic: neither interruption nor duplicate callbacks can return it to pending. */
    fun markHeard(messageId: AgentMessageId): DurableAgentStoreResult<DurableAgentMessage> = updateMessage(messageId) { current, message ->
        when (message.lifecycle) {
            DurableMessageLifecycle.HEARD -> DurableAgentStoreResult.Success(DurableMutation(current, message))
            DurableMessageLifecycle.PENDING, DurableMessageLifecycle.PLAYING -> {
                val updated = message.copy(lifecycle = DurableMessageLifecycle.HEARD)
                DurableAgentStoreResult.Success(DurableMutation(replaceMessage(current, updated), updated))
            }
            else -> invalidMessageTransition(message, "mark heard")
        }
    }

    /** Playback interruption keeps authoritative text pending unless the heard commit already won. */
    fun returnPlaybackToPending(messageId: AgentMessageId): DurableAgentStoreResult<DurableAgentMessage> = updateMessage(messageId) { current, message ->
        when (message.lifecycle) {
            DurableMessageLifecycle.PENDING, DurableMessageLifecycle.HEARD -> DurableAgentStoreResult.Success(DurableMutation(current, message))
            DurableMessageLifecycle.PLAYING -> {
                val updated = message.copy(lifecycle = DurableMessageLifecycle.PENDING)
                DurableAgentStoreResult.Success(DurableMutation(replaceMessage(current, updated), updated))
            }
            else -> invalidMessageTransition(message, "interrupt playback")
        }
    }

    /**
     * Atomic explicit skip (SOS) for an addressed inbound response. In one durable transaction
     * the target inbound [DurableMessageLifecycle.PENDING], [DurableMessageLifecycle.PLAYING],
     * or already-[DurableMessageLifecycle.HEARD] response is marked HEARD and that channel's
     * playback drain is set to [PlaybackDrainState.PAUSED_BY_USER]. Later pending responses and
     * every other channel are left unchanged. Idempotent for duplicate SOS/reselection: re-skipping
     * an already-heard, already-paired channel succeeds without mutating further state.
     *
     * Fails typed when the message is unknown, does not belong to the named channel, is not
     * inbound, or is not in a skippable lifecycle.
     */
    fun skipPlaybackAndPause(
        channelInstanceId: String,
        messageId: AgentMessageId,
    ): DurableAgentStoreResult<DurableAgentMessage> = transact { current ->
        requireNonBlank(channelInstanceId, "channel instance ID")
        val message = current.messages.firstOrNull { it.id == messageId }
            ?: return@transact DurableAgentStoreResult.Failure(DurableAgentStoreFailure.UnknownMessage(messageId))
        if (message.channelInstanceId != channelInstanceId) {
            return@transact DurableAgentStoreResult.Failure(
                DurableAgentStoreFailure.Conflict("Message ${message.id} does not belong to channel $channelInstanceId"),
            )
        }
        if (message.direction != DurableMessageDirection.INBOUND) {
            return@transact invalidMessageTransition(message, "skip playback and pause")
        }
        when (message.lifecycle) {
            DurableMessageLifecycle.PENDING, DurableMessageLifecycle.PLAYING -> {
                val updated = message.copy(lifecycle = DurableMessageLifecycle.HEARD)
                val next = replaceMessage(current, updated).copy(
                    channelDrainStates = current.channelDrainStates + (channelInstanceId to PlaybackDrainState.PAUSED_BY_USER),
                )
                DurableAgentStoreResult.Success(DurableMutation(next, updated))
            }
            DurableMessageLifecycle.HEARD -> {
                val next = current.copy(
                    channelDrainStates = current.channelDrainStates + (channelInstanceId to PlaybackDrainState.PAUSED_BY_USER),
                )
                DurableAgentStoreResult.Success(DurableMutation(next, message))
            }
            else -> invalidMessageTransition(message, "skip playback and pause")
        }
    }

    /**
     * Deliberate same-channel reselection resume. Clears only the addressed channel's
     * [PlaybackDrainState.PAUSED_BY_USER] drain state, returning it to
     * [PlaybackDrainState.ENABLED]. Other channels' pause state is never touched. Idempotent:
     * resuming an already-enabled channel succeeds and persists no new pause state.
     */
    fun resumePlaybackDrain(channelInstanceId: String): DurableAgentStoreResult<PlaybackDrainState> = transact { current ->
        requireNonBlank(channelInstanceId, "channel instance ID")
        val next = current.copy(channelDrainStates = current.channelDrainStates - channelInstanceId)
        DurableAgentStoreResult.Success(DurableMutation(next, PlaybackDrainState.ENABLED))
    }

    /** Returns the durable playback-drain policy for a channel; absence means [PlaybackDrainState.ENABLED]. */
    fun playbackDrainState(channelInstanceId: String): PlaybackDrainState = synchronized(lock) {
        snapshot.channelDrainStates[channelInstanceId] ?: PlaybackDrainState.ENABLED
    }

    /** Advances a channel epoch durably; callers cancel old work separately through lifecycle ownership. */
    fun advanceConversationEpoch(channelInstanceId: String): DurableAgentStoreResult<Long> = transact { current ->
        requireNonBlank(channelInstanceId, "channel instance ID")
        val nextEpoch = (current.channelEpochs[channelInstanceId] ?: -1L) + 1L
        val next = current.copy(channelEpochs = current.channelEpochs + (channelInstanceId to nextEpoch))
        DurableAgentStoreResult.Success(DurableMutation(next, nextEpoch))
    }

    /**
     * Atomically opens a fresh conversation epoch and retires all old nonterminal work.
     * Work with a started remote or native effect is indeterminate rather than cancelled so
     * recovery cannot claim or replay an ambiguous effect. Terminal messages and tool results
     * remain untouched.
     */
    fun resetConversationEpoch(channelInstanceId: String): DurableAgentStoreResult<DurableConversationReset> = transact { current ->
        requireNonBlank(channelInstanceId, "channel instance ID")
        val nextEpoch = (current.channelEpochs[channelInstanceId] ?: -1L) + 1L
        val retired = current.runs.asSequence()
            .filter { it.channelInstanceId == channelInstanceId && !it.state.isTerminal }
            .associateWith { run ->
                val startedTool = current.toolCalls.any {
                    it.runId == run.id && (it.state == DurableToolCallState.RESERVED || it.state == DurableToolCallState.EFFECT_STARTED)
                }
                val ambiguousRemote = run.recovery.remoteSubmission == DurableRemoteSubmission.SUBMITTED_OR_AMBIGUOUS
                when {
                    startedTool -> run.copy(
                        state = DurableRunState.INDETERMINATE,
                        terminalReason = DurableRunTerminalReason.AMBIGUOUS_TOOL_EFFECT,
                        recovery = DurableRecoveryEnvelope.empty(),
                    )
                    ambiguousRemote -> run.copy(
                        state = DurableRunState.INDETERMINATE,
                        terminalReason = DurableRunTerminalReason.AMBIGUOUS_REMOTE_SUBMISSION,
                        recovery = DurableRecoveryEnvelope.empty(),
                    )
                    else -> run.copy(
                        state = DurableRunState.CANCELLED,
                        terminalReason = DurableRunTerminalReason.CANCELLED,
                        recovery = DurableRecoveryEnvelope.empty(),
                    )
                }
            }
        val next = current.copy(
            channelEpochs = current.channelEpochs + (channelInstanceId to nextEpoch),
            runs = current.runs.map { retired[it] ?: it },
            messages = current.messages.map { message ->
                val run = retired.entries.firstOrNull { it.key.id == message.runId }?.value
                if (run != null && message.direction == DurableMessageDirection.OUTBOUND) {
                    message.copy(lifecycle = run.messageLifecycleForTerminal())
                } else {
                    message
                }
            },
        )
        DurableAgentStoreResult.Success(
            DurableMutation(
                next,
                DurableConversationReset(
                    conversationEpoch = nextEpoch,
                    cancelledRunIds = retired.filterValues { it.state == DurableRunState.CANCELLED }.keys.map { it.id },
                    indeterminateRunIds = retired.filterValues { it.state == DurableRunState.INDETERMINATE }.keys.map { it.id },
                ),
            ),
        )
    }

    /** Advances the configuration epoch without retaining credentials or configuration objects. */
    fun advanceConfigurationEpoch(channelInstanceId: String): DurableAgentStoreResult<Long> = transact { current ->
        requireNonBlank(channelInstanceId, "channel instance ID")
        val nextEpoch = (current.configurationEpochs[channelInstanceId] ?: -1L) + 1L
        val next = current.copy(configurationEpochs = current.configurationEpochs + (channelInstanceId to nextEpoch))
        DurableAgentStoreResult.Success(DurableMutation(next, nextEpoch))
    }

    /**
     * Makes process-death recovery deterministic. It never resubmits an ambiguous remote effect
     * or replays a native tool effect. It only returns runs proven safe to resume.
     */
    fun reconcileAfterRestart(): DurableAgentStoreResult<DurableRecoveryPlan> = transact { current ->
        var next = current
        val resumable = mutableListOf<AgentRunId>()
        for (run in current.runs) {
            if (run.state.isTerminal) continue
            val tools = current.toolCalls.filter { it.runId == run.id }
            val unsafeTool = tools.firstOrNull { it.state == DurableToolCallState.RESERVED || it.state == DurableToolCallState.EFFECT_STARTED }
            when {
                unsafeTool != null -> {
                    val indeterminate = unsafeTool.copy(
                        state = DurableToolCallState.INDETERMINATE,
                        terminalResult = DurableToolResult(DurableToolOutcome.INDETERMINATE, "Interrupted during native effect"),
                    )
                    next = replaceTool(next, indeterminate)
                    next = replaceRun(next, run.copy(
                        state = DurableRunState.INDETERMINATE,
                        terminalReason = DurableRunTerminalReason.AMBIGUOUS_TOOL_EFFECT,
                        recovery = DurableRecoveryEnvelope.empty(),
                    )).mapSourceMessage(run.id) { it.copy(lifecycle = DurableMessageLifecycle.FAILED) }
                }
                run.recovery.remoteSubmission == DurableRemoteSubmission.SUBMITTED_OR_AMBIGUOUS -> {
                    next = replaceRun(next, run.copy(
                        state = DurableRunState.INDETERMINATE,
                        terminalReason = DurableRunTerminalReason.AMBIGUOUS_REMOTE_SUBMISSION,
                        recovery = DurableRecoveryEnvelope.empty(),
                    )).mapSourceMessage(run.id) { it.copy(lifecycle = DurableMessageLifecycle.FAILED) }
                }
                run.state == DurableRunState.WAITING_FOR_TOOL && tools.any { it.state == DurableToolCallState.RESULT_RECORDED } -> {
                    // The effect/result is committed; only the remote continuation remains. No native replay.
                    val resumed = run.copy(state = DurableRunState.ACTIVE)
                    next = replaceRun(next, resumed)
                    resumable += run.id
                }
                run.recovery.remoteSubmission == DurableRemoteSubmission.NOT_SUBMITTED || run.state == DurableRunState.QUEUED -> {
                    next = replaceRun(next, run.copy(state = DurableRunState.QUEUED))
                    resumable += run.id
                }
                else -> {
                    next = replaceRun(next, run.copy(
                        state = DurableRunState.INDETERMINATE,
                        terminalReason = DurableRunTerminalReason.UNRECOVERABLE_INTERRUPTION,
                        recovery = DurableRecoveryEnvelope.empty(),
                    )).mapSourceMessage(run.id) { it.copy(lifecycle = DurableMessageLifecycle.FAILED) }
                }
            }
        }
        next = next.copy(messages = next.messages.map { message ->
            if (message.lifecycle == DurableMessageLifecycle.PLAYING) message.copy(lifecycle = DurableMessageLifecycle.PENDING) else message
        })
        DurableAgentStoreResult.Success(DurableMutation(next, DurableRecoveryPlan(resumable)))
    }

    /** Durable message text is intentionally unavailable as post-restart conversation context. */
    fun freshConversationContext(channelInstanceId: String): List<Nothing> {
        requireNonBlank(channelInstanceId, "channel instance ID")
        return emptyList()
    }

    private fun updateRun(
        runId: AgentRunId,
        update: (DurableAgentStoreSnapshot, DurableAgentRun) -> DurableAgentStoreResult<DurableMutation<DurableAgentRun>>,
    ): DurableAgentStoreResult<DurableAgentRun> = transact { current ->
        val run = current.runs.firstOrNull { it.id == runId }
            ?: return@transact DurableAgentStoreResult.Failure(DurableAgentStoreFailure.UnknownRun(runId))
        update(current, run)
    }

    private fun updateToolCall(
        runId: AgentRunId,
        callId: AgentToolCallId,
        update: (DurableAgentStoreSnapshot, DurableToolCallLedgerEntry) -> DurableAgentStoreResult<DurableMutation<DurableToolCallLedgerEntry>>,
    ): DurableAgentStoreResult<DurableToolCallLedgerEntry> = transact { current ->
        val entry = current.toolCalls.firstOrNull { it.runId == runId && it.callId == callId }
            ?: return@transact DurableAgentStoreResult.Failure(DurableAgentStoreFailure.UnknownToolCall(runId, callId))
        update(current, entry)
    }

    private fun updateMessage(
        messageId: AgentMessageId,
        update: (DurableAgentStoreSnapshot, DurableAgentMessage) -> DurableAgentStoreResult<DurableMutation<DurableAgentMessage>>,
    ): DurableAgentStoreResult<DurableAgentMessage> = transact { current ->
        val message = current.messages.firstOrNull { it.id == messageId }
            ?: return@transact DurableAgentStoreResult.Failure(DurableAgentStoreFailure.UnknownMessage(messageId))
        update(current, message)
    }

    private fun <T> transact(
        action: (DurableAgentStoreSnapshot) -> DurableAgentStoreResult<DurableMutation<T>>,
    ): DurableAgentStoreResult<T> = synchronized(lock) {
        val result = try {
            action(snapshot)
        } catch (error: IllegalArgumentException) {
            return@synchronized DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Invalid(error.message ?: "Invalid durable agent state"))
        }
        when (result) {
            is DurableAgentStoreResult.Failure -> result
            is DurableAgentStoreResult.Success -> {
                val mutation = result.value
                val validated = validate(mutation.snapshot)
                if (validated != null) return@synchronized DurableAgentStoreResult.Failure(validated)
                when (val persisted = persist(mutation.snapshot)) {
                    is DurableAgentStoreResult.Failure -> persisted
                    is DurableAgentStoreResult.Success -> {
                        snapshot = mutation.snapshot
                        DurableAgentStoreResult.Success(mutation.value)
                    }
                }
            }
        }
    }

    private fun persist(next: DurableAgentStoreSnapshot): DurableAgentStoreResult<Unit> {
        val parent = file.absoluteFile.parentFile
        val temporary = File(parent, "${file.name}.tmp")
        return try {
            parent.mkdirs()
            FileOutputStream(temporary).use { output ->
                output.write(encode(next).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            DurableAgentStoreResult.Success(Unit)
        } catch (error: IOException) {
            if (temporary.exists()) temporary.delete()
            DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Storage(error.message ?: "Unable to persist agent ledger"))
        }
    }

    private fun validate(candidate: DurableAgentStoreSnapshot): DurableAgentStoreFailure? {
        if (candidate.schemaVersion != DurableAgentStoreSnapshot.SCHEMA_VERSION) {
            return DurableAgentStoreFailure.Invalid("Unexpected schema version")
        }
        if (candidate.messages.map { it.id }.toSet().size != candidate.messages.size) return DurableAgentStoreFailure.Invalid("Duplicate message ID")
        if (candidate.runs.map { it.id }.toSet().size != candidate.runs.size) return DurableAgentStoreFailure.Invalid("Duplicate run ID")
        if (candidate.toolCalls.map { it.runId to it.callId }.toSet().size != candidate.toolCalls.size) return DurableAgentStoreFailure.Invalid("Duplicate tool call")
        if (candidate.messages.any { message -> candidate.runs.none { it.id == message.runId } }) return DurableAgentStoreFailure.Invalid("Message references unknown run")
        if (candidate.runs.any { run -> candidate.messages.none { it.id == run.sourceMessageId && it.runId == run.id } }) return DurableAgentStoreFailure.Invalid("Run lacks source message")
        if (candidate.runs.groupBy { it.channelInstanceId }.values.any { runs -> runs.map { it.queueSequence }.toSet().size != runs.size }) {
            return DurableAgentStoreFailure.Invalid("Duplicate per-channel queue sequence")
        }
        if (candidate.messages.any { it.lifecycle == DurableMessageLifecycle.HEARD && it.direction != DurableMessageDirection.INBOUND }) {
            return DurableAgentStoreFailure.Invalid("Only inbound messages can be heard")
        }
        if (candidate.channelDrainStates.keys.any { it.isBlank() }) {
            return DurableAgentStoreFailure.Invalid("Blank channel instance ID in playback drain states")
        }
        return null
    }
}

private data class DurableMutation<T>(val snapshot: DurableAgentStoreSnapshot, val value: T)

data class DurableAgentStoreSnapshot(
    val schemaVersion: Int,
    val messages: List<DurableAgentMessage>,
    val runs: List<DurableAgentRun>,
    val toolCalls: List<DurableToolCallLedgerEntry>,
    val channelEpochs: Map<String, Long>,
    val configurationEpochs: Map<String, Long>,
    val channelDrainStates: Map<String, PlaybackDrainState>,
) {
    companion object {
        const val SCHEMA_VERSION = 2
        fun empty() = DurableAgentStoreSnapshot(SCHEMA_VERSION, emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap())
    }
}

data class DurableAgentConfiguration(
    val connectionProfileId: String,
    val modelId: String,
    val systemPrompt: String,
    val configurationFingerprint: String,
)

data class DurableAgentMessage(
    val id: AgentMessageId,
    val channelInstanceId: String,
    val conversationEpoch: Long,
    val direction: DurableMessageDirection,
    val text: String,
    val createdAtMillis: Long,
    val lifecycle: DurableMessageLifecycle,
    val runId: AgentRunId,
)

enum class DurableMessageDirection { OUTBOUND, INBOUND }
enum class DurableMessageLifecycle { QUEUED, PROCESSING, COMPLETED, PENDING, PLAYING, HEARD, FAILED, CANCELLED }

data class DurableAgentRun(
    val id: AgentRunId,
    val channelInstanceId: String,
    val conversationEpoch: Long,
    val configurationEpoch: Long,
    val queueSequence: Long,
    val sourceMessageId: AgentMessageId,
    val configuration: DurableAgentConfiguration,
    val state: DurableRunState,
    val terminalReason: DurableRunTerminalReason?,
    val recovery: DurableRecoveryEnvelope,
)

enum class DurableRunState { QUEUED, ACTIVE, WAITING_FOR_TOOL, COMPLETED, FAILED, CANCELLED, INDETERMINATE;
    val isTerminal get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == INDETERMINATE
}
enum class DurableRunTerminalReason { REMOTE_FAILURE, CANCELLED, AMBIGUOUS_REMOTE_SUBMISSION, AMBIGUOUS_TOOL_EFFECT, UNRECOVERABLE_INTERRUPTION }
data class DurableRunTerminal(val state: DurableRunState, val reason: DurableRunTerminalReason?) {
    init { require(state.isTerminal && state != DurableRunState.COMPLETED || state == DurableRunState.COMPLETED && reason == null) }
    val messageLifecycle: DurableMessageLifecycle get() = when (state) {
        DurableRunState.COMPLETED -> DurableMessageLifecycle.COMPLETED
        DurableRunState.CANCELLED -> DurableMessageLifecycle.CANCELLED
        DurableRunState.FAILED, DurableRunState.INDETERMINATE -> DurableMessageLifecycle.FAILED
        else -> error("Non-terminal state")
    }
}

data class DurableRecoveryEnvelope(
    val operationId: AgentOperationId?,
    val continuation: DurableContinuation?,
    val remoteSubmission: DurableRemoteSubmission,
) {
    companion object { fun empty() = DurableRecoveryEnvelope(null, null, DurableRemoteSubmission.NONE) }
}
enum class DurableRemoteSubmission { NONE, NOT_SUBMITTED, SUBMITTED_OR_AMBIGUOUS }
enum class DurableContinuation { INITIAL_REQUEST, TOOL_RESULT_CONTINUATION }

data class DurableToolCallLedgerEntry(
    val runId: AgentRunId,
    val channelInstanceId: String,
    val callId: AgentToolCallId,
    val toolName: String,
    val argumentHash: String,
    val operationId: AgentOperationId,
    val state: DurableToolCallState,
    val terminalResult: DurableToolResult?,
)
enum class DurableToolCallState { RESERVED, EFFECT_STARTED, RESULT_RECORDED, INDETERMINATE }
enum class DurableToolOutcome { DELIVERED, REJECTED, FAILED, CANCELLED, INDETERMINATE }
data class DurableToolResult(val outcome: DurableToolOutcome, val detail: String)

sealed interface DurableAdmission {
    data class Accepted(val run: DurableAgentRun) : DurableAdmission
    data class Duplicate(val run: DurableAgentRun) : DurableAdmission
}
sealed interface DurableToolReservation {
    data class Reserved(val entry: DurableToolCallLedgerEntry) : DurableToolReservation
    data class Existing(val entry: DurableToolCallLedgerEntry) : DurableToolReservation
}
data class DurableRecoveryPlan(val resumableRunIds: List<AgentRunId>)
data class DurableConversationReset(
    val conversationEpoch: Long,
    val cancelledRunIds: List<AgentRunId>,
    val indeterminateRunIds: List<AgentRunId>,
)

sealed interface DurableAgentStoreResult<out T> {
    data class Success<T>(val value: T) : DurableAgentStoreResult<T>
    data class Failure(val error: DurableAgentStoreFailure) : DurableAgentStoreResult<Nothing>
}

sealed interface DurableAgentStoreFailure {
    data class Storage(val detail: String) : DurableAgentStoreFailure
    data class Malformed(val detail: String) : DurableAgentStoreFailure
    data class Invalid(val detail: String) : DurableAgentStoreFailure
    data class Conflict(val detail: String) : DurableAgentStoreFailure
    data class UnknownRun(val runId: AgentRunId) : DurableAgentStoreFailure
    data class UnknownMessage(val messageId: AgentMessageId) : DurableAgentStoreFailure
    data class UnknownToolCall(val runId: AgentRunId, val callId: AgentToolCallId) : DurableAgentStoreFailure
    data class NotQueueHead(val runId: AgentRunId) : DurableAgentStoreFailure
    data class ToolCallConflict(val runId: AgentRunId, val callId: AgentToolCallId) : DurableAgentStoreFailure
}

private fun DurableAgentStoreSnapshot.mapSourceMessage(runId: AgentRunId, transform: (DurableAgentMessage) -> DurableAgentMessage): DurableAgentStoreSnapshot =
    copy(messages = messages.map { if (it.runId == runId && it.direction == DurableMessageDirection.OUTBOUND) transform(it) else it })
private fun replaceRun(snapshot: DurableAgentStoreSnapshot, replacement: DurableAgentRun): DurableAgentStoreSnapshot =
    snapshot.copy(runs = snapshot.runs.map { if (it.id == replacement.id) replacement else it })
private fun replaceTool(snapshot: DurableAgentStoreSnapshot, replacement: DurableToolCallLedgerEntry): DurableAgentStoreSnapshot =
    snapshot.copy(toolCalls = snapshot.toolCalls.map { if (it.runId == replacement.runId && it.callId == replacement.callId) replacement else it })
private fun replaceMessage(snapshot: DurableAgentStoreSnapshot, replacement: DurableAgentMessage): DurableAgentStoreSnapshot =
    snapshot.copy(messages = snapshot.messages.map { if (it.id == replacement.id) replacement else it })
private fun invalidTransition(run: DurableAgentRun, action: String): DurableAgentStoreResult.Failure =
    DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Conflict("Cannot $action run ${run.id} in state ${run.state}"))
private fun invalidToolTransition(entry: DurableToolCallLedgerEntry, action: String): DurableAgentStoreResult.Failure =
    DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Conflict("Cannot $action tool call ${entry.callId} in state ${entry.state}"))
private fun invalidMessageTransition(message: DurableAgentMessage, action: String): DurableAgentStoreResult.Failure =
    DurableAgentStoreResult.Failure(DurableAgentStoreFailure.Conflict("Cannot $action message ${message.id} in state ${message.lifecycle}"))
private fun requireNonBlank(value: String, label: String) = require(value.isNotBlank()) { "$label must not be blank" }
private fun DurableAgentRun.messageLifecycleForTerminal(): DurableMessageLifecycle = when (state) {
    DurableRunState.COMPLETED -> DurableMessageLifecycle.COMPLETED
    DurableRunState.CANCELLED -> DurableMessageLifecycle.CANCELLED
    DurableRunState.FAILED, DurableRunState.INDETERMINATE -> DurableMessageLifecycle.FAILED
    DurableRunState.QUEUED, DurableRunState.ACTIVE, DurableRunState.WAITING_FOR_TOOL -> error("Run is not terminal")
}

private fun encode(snapshot: DurableAgentStoreSnapshot): String = JSONObject().apply {
    put("schemaVersion", snapshot.schemaVersion)
    put("messages", JSONArray().also { array -> snapshot.messages.forEach { array.put(it.toJson()) } })
    put("runs", JSONArray().also { array -> snapshot.runs.forEach { array.put(it.toJson()) } })
    put("toolCalls", JSONArray().also { array -> snapshot.toolCalls.forEach { array.put(it.toJson()) } })
    put("channelEpochs", JSONObject().also { objectValue -> snapshot.channelEpochs.toSortedMap().forEach { (key, value) -> objectValue.put(key, value) } })
    put("configurationEpochs", JSONObject().also { objectValue -> snapshot.configurationEpochs.toSortedMap().forEach { (key, value) -> objectValue.put(key, value) } })
    put("channelDrainStates", JSONObject().also { objectValue -> snapshot.channelDrainStates.toSortedMap().forEach { (key, value) -> objectValue.put(key, value.name) } })
}.toString(2)

private fun decode(encoded: String): DurableAgentStoreSnapshot {
    val root = JSONObject(encoded)
    val schemaVersion = root.getInt("schemaVersion")
    require(schemaVersion == 1 || schemaVersion == DurableAgentStoreSnapshot.SCHEMA_VERSION) { "Unsupported durable agent schema" }
    fun <T> JSONArray.decodeEach(decode: (JSONObject) -> T): List<T> = List(length()) { decode(getJSONObject(it)) }
    val messages = root.getJSONArray("messages").decodeEach(::decodeMessage)
    val runs = root.getJSONArray("runs").decodeEach(::decodeRun)
    val toolCalls = root.getJSONArray("toolCalls").decodeEach(::decodeToolCall)
    val epochsObject = root.getJSONObject("channelEpochs")
    val epochs = epochsObject.keys().asSequence().associateWith { epochsObject.getLong(it) }
    val configurationEpochsObject = root.getJSONObject("configurationEpochs")
    val configurationEpochs = configurationEpochsObject.keys().asSequence().associateWith { configurationEpochsObject.getLong(it) }
    val channelDrainStates = if (root.has("channelDrainStates")) {
        val drainObject = root.getJSONObject("channelDrainStates")
        drainObject.keys().asSequence().associateWith { PlaybackDrainState.valueOf(drainObject.getString(it)) }
    } else {
        emptyMap()
    }
    return DurableAgentStoreSnapshot(DurableAgentStoreSnapshot.SCHEMA_VERSION, messages, runs, toolCalls, epochs, configurationEpochs, channelDrainStates).also {
        DurableAgentRunStoreValidation.validate(it)
    }
}

private object DurableAgentRunStoreValidation {
    fun validate(snapshot: DurableAgentStoreSnapshot) {
        require(snapshot.messages.map { it.id }.toSet().size == snapshot.messages.size) { "Duplicate message ID" }
        require(snapshot.runs.map { it.id }.toSet().size == snapshot.runs.size) { "Duplicate run ID" }
    }
}

private fun DurableAgentMessage.toJson() = JSONObject().apply {
    put("id", id.value); put("channelInstanceId", channelInstanceId); put("conversationEpoch", conversationEpoch)
    put("direction", direction.name); put("text", text); put("createdAtMillis", createdAtMillis)
    put("lifecycle", lifecycle.name); put("runId", runId.value)
}
private fun DurableAgentRun.toJson() = JSONObject().apply {
    put("id", id.value); put("channelInstanceId", channelInstanceId); put("conversationEpoch", conversationEpoch); put("configurationEpoch", configurationEpoch); put("queueSequence", queueSequence)
    put("sourceMessageId", sourceMessageId.value); put("configuration", configuration.toJson()); put("state", state.name)
    terminalReason?.let { put("terminalReason", it.name) }; put("recovery", recovery.toJson())
}
private fun DurableAgentConfiguration.toJson() = JSONObject().apply {
    put("connectionProfileId", connectionProfileId); put("modelId", modelId); put("systemPrompt", systemPrompt); put("configurationFingerprint", configurationFingerprint)
}
private fun DurableRecoveryEnvelope.toJson() = JSONObject().apply {
    operationId?.let { put("operationId", it.value) }; continuation?.let { put("continuation", it.name) }; put("remoteSubmission", remoteSubmission.name)
}
private fun DurableToolCallLedgerEntry.toJson() = JSONObject().apply {
    put("runId", runId.value); put("channelInstanceId", channelInstanceId); put("callId", callId.value); put("toolName", toolName)
    put("argumentHash", argumentHash); put("operationId", operationId.value); put("state", state.name); terminalResult?.let { put("terminalResult", it.toJson()) }
}
private fun DurableToolResult.toJson() = JSONObject().apply { put("outcome", outcome.name); put("detail", detail) }

private fun decodeMessage(json: JSONObject) = DurableAgentMessage(
    AgentMessageId(json.getString("id")), json.getString("channelInstanceId"), json.getLong("conversationEpoch"),
    DurableMessageDirection.valueOf(json.getString("direction")), json.getString("text"), json.getLong("createdAtMillis"),
    DurableMessageLifecycle.valueOf(json.getString("lifecycle")), AgentRunId(json.getString("runId")),
)
private fun decodeRun(json: JSONObject): DurableAgentRun {
    val configuration = json.getJSONObject("configuration")
    val recovery = json.getJSONObject("recovery")
    return DurableAgentRun(
        AgentRunId(json.getString("id")), json.getString("channelInstanceId"), json.getLong("conversationEpoch"), json.getLong("configurationEpoch"), json.getLong("queueSequence"),
        AgentMessageId(json.getString("sourceMessageId")),
        DurableAgentConfiguration(configuration.getString("connectionProfileId"), configuration.getString("modelId"), configuration.getString("systemPrompt"), configuration.getString("configurationFingerprint")),
        DurableRunState.valueOf(json.getString("state")),
        if (json.has("terminalReason")) DurableRunTerminalReason.valueOf(json.getString("terminalReason")) else null,
        DurableRecoveryEnvelope(
            if (recovery.has("operationId")) AgentOperationId(recovery.getString("operationId")) else null,
            if (recovery.has("continuation")) DurableContinuation.valueOf(recovery.getString("continuation")) else null,
            DurableRemoteSubmission.valueOf(recovery.getString("remoteSubmission")),
        ),
    )
}
private fun decodeToolCall(json: JSONObject): DurableToolCallLedgerEntry = DurableToolCallLedgerEntry(
    AgentRunId(json.getString("runId")), json.getString("channelInstanceId"), AgentToolCallId(json.getString("callId")), json.getString("toolName"),
    json.getString("argumentHash"), AgentOperationId(json.getString("operationId")), DurableToolCallState.valueOf(json.getString("state")),
    if (json.has("terminalResult")) json.getJSONObject("terminalResult").let { DurableToolResult(DurableToolOutcome.valueOf(it.getString("outcome")), it.getString("detail")) } else null,
)
