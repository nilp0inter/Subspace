package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.OpaqueSynthesizedAudio
import dev.nilp0inter.subspace.channel.capability.SynthesizedAudioArtifact
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason
import dev.nilp0inter.subspace.model.DelayedPlaybackOutcome
import dev.nilp0inter.subspace.model.DelayedPlaybackRequest
import java.io.File
import java.util.ArrayDeque
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelayedPlaybackCoordinatorTest {
    @Test
    fun unselectedResponsesRemainPendingUntilTheirChannelIsSelected() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val response = pendingResponse(store, "unselected", channel = "bravo", text = "reply", createdAtMillis = 10)
            var selected = "alpha"
            val synthesis = RecordingSynthesis(
                outcomes = ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(opaqueAudio("audio-reply")))),
                onSynthesize = { _, _ -> },
            )
            val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
            val coordinator = coordinator(this, store, { selected }, synthesis, audio)

            val scheduled = coordinator.schedule(response.context, DelayedPlaybackRequest(response.messageId, "reply"))
            advanceUntilIdle()

            assertTrue(scheduled is DelayedPlaybackOutcome.Pending)
            assertTrue(synthesis.texts.isEmpty())
            assertTrue(audio.requests.isEmpty())
            assertEquals(DurableMessageLifecycle.PENDING, lifecycle(store, response.messageId))

            selected = "bravo"
            coordinator.onChannelSelected("bravo")
            advanceUntilIdle()

            assertEquals(listOf("reply"), synthesis.texts)
            assertEquals(listOf("bravo"), audio.requests.map(PlaybackRequest::channelInstanceId))
            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response.messageId))
        }
    }

    @Test
    fun selectedResponsesAreSynthesizedAndPlayedInDurableTimestampOrderWithOpaqueArtifacts() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val later = pendingResponse(store, "later", channel = "alpha", text = "later reply", createdAtMillis = 20)
            val first = pendingResponse(store, "first", channel = "alpha", text = "first reply", createdAtMillis = 10)
            val firstAudio = opaqueAudio("first-audio")
            val laterAudio = opaqueAudio("later-audio")
            val synthesis = RecordingSynthesis(
                outcomes = ArrayDeque(
                    listOf(
                        DelayedPlaybackSynthesisResult.Success(firstAudio),
                        DelayedPlaybackSynthesisResult.Success(laterAudio),
                    ),
                ),
                onSynthesize = { _, _ -> },
            )
            val audio = RecordingAudio(
                ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
            )
            val coordinator = coordinator(this, store, { "alpha" }, synthesis, audio)

            coordinator.schedule(later.context, DelayedPlaybackRequest(later.messageId, "later reply"))
            coordinator.schedule(first.context, DelayedPlaybackRequest(first.messageId, "first reply"))
            advanceUntilIdle()

            assertEquals(listOf("first reply", "later reply"), synthesis.texts)
            assertEquals(listOf("alpha", "alpha"), audio.requests.map(PlaybackRequest::channelInstanceId))
            assertSame(firstAudio, audio.requests[0].audio)
            assertSame(laterAudio, audio.requests[1].audio)
            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, first.messageId))
            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, later.messageId))
        }
    }

    @Test
    fun busyAudioReturnsTextToPendingAndRetriesTheSameOpaqueArtifactOnlyWhenAdmissionReopens() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val response = pendingResponse(store, "busy", channel = "alpha", text = "retry me", createdAtMillis = 10)
            val artifact = opaqueAudio("retry-artifact")
            val synthesis = RecordingSynthesis(
                outcomes = ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(artifact))),
                onSynthesize = { _, _ -> },
            )
            val audio = RecordingAudio(
                ArrayDeque(listOf(DelayedPlaybackAudioResult.Busy, DelayedPlaybackAudioResult.Completed)),
            )
            val coordinator = coordinator(this, store, { "alpha" }, synthesis, audio)

            coordinator.schedule(response.context, DelayedPlaybackRequest(response.messageId, "retry me"))
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.PENDING, lifecycle(store, response.messageId))
            assertEquals(listOf("retry me"), synthesis.texts)
            assertEquals(1, audio.requests.size)

            coordinator.onAudioAvailable()
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response.messageId))
            assertEquals(listOf("retry me"), synthesis.texts)
            assertEquals(2, audio.requests.size)
            assertSame(artifact, audio.requests[0].audio)
            assertSame(artifact, audio.requests[1].audio)
        }
    }

    @Test
    fun selectionChangeAfterSynthesisPreventsRouteAdmissionUntilTheResponseIsSelectedAgain() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val response = pendingResponse(store, "selection-change", channel = "alpha", text = "hold", createdAtMillis = 10)
            var selected = "alpha"
            val artifact = opaqueAudio("held-artifact")
            val synthesis = RecordingSynthesis(
                outcomes = ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(artifact))),
                onSynthesize = { _, _ -> selected = "bravo" },
            )
            val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
            val coordinator = coordinator(this, store, { selected }, synthesis, audio)

            coordinator.schedule(response.context, DelayedPlaybackRequest(response.messageId, "hold"))
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.PENDING, lifecycle(store, response.messageId))
            assertEquals(listOf("hold"), synthesis.texts)
            assertTrue(audio.requests.isEmpty())

            selected = "alpha"
            coordinator.onChannelSelected("alpha")
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response.messageId))
            assertEquals(listOf("hold"), synthesis.texts)
            assertEquals(1, audio.requests.size)
            assertSame(artifact, audio.requests.single().audio)
        }
    }

    @Test
    fun synthesisAndPlaybackFailureOrCancellationLeaveTheAuthoritativeResponsePending() = runTest {
        data class FailureCase(
            val name: String,
            val synthesis: DelayedPlaybackSynthesisResult,
            val audio: List<DelayedPlaybackAudioResult>,
            val expectedAudioCalls: Int,
        )
        val cases = listOf(
            FailureCase(
                name = "synthesis failure",
                synthesis = DelayedPlaybackSynthesisResult.Failed(DelayedPlaybackFailureReason.SYNTHESIS_FAILED),
                audio = emptyList(),
                expectedAudioCalls = 0,
            ),
            FailureCase(
                name = "synthesis cancelled",
                synthesis = DelayedPlaybackSynthesisResult.Cancelled,
                audio = emptyList(),
                expectedAudioCalls = 0,
            ),
            FailureCase(
                name = "playback failure",
                synthesis = DelayedPlaybackSynthesisResult.Success(opaqueAudio("failed-playback-artifact")),
                audio = listOf(DelayedPlaybackAudioResult.Failed(DelayedPlaybackFailureReason.PLAYBACK_FAILED)),
                expectedAudioCalls = 1,
            ),
            FailureCase(
                name = "playback cancelled",
                synthesis = DelayedPlaybackSynthesisResult.Success(opaqueAudio("cancelled-playback-artifact")),
                audio = listOf(DelayedPlaybackAudioResult.Cancelled),
                expectedAudioCalls = 1,
            ),
        )

        for ((index, failure) in cases.withIndex()) {
            withTemporaryDirectory { directory ->
                val store = DurableAgentRunStore(File(directory, "ledger.json"))
                val response = pendingResponse(store, "failure-$index", channel = "alpha", text = failure.name, createdAtMillis = 10)
                val synthesis = RecordingSynthesis(ArrayDeque(listOf(failure.synthesis)), { _, _ -> })
                val audio = RecordingAudio(ArrayDeque(failure.audio))
                val coordinator = coordinator(this, store, { "alpha" }, synthesis, audio)

                coordinator.schedule(response.context, DelayedPlaybackRequest(response.messageId, failure.name))
                advanceUntilIdle()

                assertEquals(failure.name, DurableMessageLifecycle.PENDING, lifecycle(store, response.messageId))
                assertEquals(failure.expectedAudioCalls, audio.requests.size)
            }
        }
    }

    @Test
    fun staleOperationAndMismatchedResponseAreIgnoredWithoutSynthesisOrPlayback() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val response = pendingResponse(store, "stale", channel = "alpha", text = "authoritative", createdAtMillis = 10)
            val synthesis = RecordingSynthesis(ArrayDeque(), { _, _ -> })
            val audio = RecordingAudio(ArrayDeque())
            val staleOperation = coordinator(
                this,
                store,
                { "alpha" },
                synthesis,
                audio,
                operationIsCurrent = { false },
            )
            val mismatchedText = coordinator(this, store, { "alpha" }, synthesis, audio)

            assertEquals(
                DelayedPlaybackOutcome.Stale,
                staleOperation.schedule(response.context, DelayedPlaybackRequest(response.messageId, "authoritative")),
            )
            assertEquals(
                DelayedPlaybackOutcome.Stale,
                mismatchedText.schedule(response.context, DelayedPlaybackRequest(response.messageId, "substituted")),
            )
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.PENDING, lifecycle(store, response.messageId))
            assertTrue(synthesis.texts.isEmpty())
            assertTrue(audio.requests.isEmpty())
        }
    }

    @Test
    fun thrownAudioBoundaryKeepsTextPendingAndRetriesTheCachedOpaqueArtifact() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val response = pendingResponse(store, "audio-exception", channel = "alpha", text = "retry exception", createdAtMillis = 10)
            val artifact = opaqueAudio("exception-artifact")
            val synthesis = RecordingSynthesis(
                ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(artifact))),
                { _, _ -> },
            )
            val audio = ThrowingThenCompletingAudio()
            val coordinator = coordinator(this, store, { "alpha" }, synthesis, audio)

            coordinator.schedule(response.context, DelayedPlaybackRequest(response.messageId, "retry exception"))
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.PENDING, lifecycle(store, response.messageId))
            assertEquals(listOf("retry exception"), synthesis.texts)
            assertEquals(1, audio.requests.size)

            coordinator.onAudioAvailable()
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response.messageId))
            assertEquals(listOf("retry exception"), synthesis.texts)
            assertEquals(2, audio.requests.size)
            assertSame(artifact, audio.requests[0].audio)
            assertSame(artifact, audio.requests[1].audio)
        }
    }

    @Test
    fun restartNormalizesInterruptedPlaybackButNeverReplaysAlreadyHeardResponses() = runTest {
        withTemporaryDirectory { directory ->
            val file = File(directory, "ledger.json")
            val original = DurableAgentRunStore(file)
            val interrupted = pendingResponse(original, "interrupted", channel = "alpha", text = "recover", createdAtMillis = 10)
            requireSuccess(original.beginPlayback(interrupted.messageId))

            val recoveredStore = DurableAgentRunStore(file)
            requireSuccess(recoveredStore.load())
            val recoveredSynthesis = RecordingSynthesis(
                ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(opaqueAudio("recovered-artifact")))),
                { _, _ -> },
            )
            val recoveredAudio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
            val recovered = coordinator(this, recoveredStore, { "alpha" }, recoveredSynthesis, recoveredAudio)

            requireSuccess(recovered.reconcileAfterRestart())
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(recoveredStore, interrupted.messageId))
            assertEquals(listOf("recover"), recoveredSynthesis.texts)
            assertEquals(1, recoveredAudio.requests.size)

            val afterHeardRestart = DurableAgentRunStore(file)
            requireSuccess(afterHeardRestart.load())
            val noReplaySynthesis = RecordingSynthesis(ArrayDeque(), { _, _ -> })
            val noReplayAudio = RecordingAudio(ArrayDeque())
            val afterHeard = coordinator(this, afterHeardRestart, { "alpha" }, noReplaySynthesis, noReplayAudio)

            requireSuccess(afterHeard.reconcileAfterRestart())
            val outcome = afterHeard.schedule(interrupted.context, DelayedPlaybackRequest(interrupted.messageId, "recover"))
            advanceUntilIdle()

            assertTrue(outcome is DelayedPlaybackOutcome.Heard)
            assertTrue(noReplaySynthesis.texts.isEmpty())
            assertTrue(noReplayAudio.requests.isEmpty())
        }
    }

    @Test
    fun explicitSkipThroughAudioPortMarksHeardAndPausesTheChannelDrain() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val response = pendingResponse(store, "skip", channel = "alpha", text = "skip me", createdAtMillis = 10)
            val synthesis = RecordingSynthesis(
                outcomes = ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(opaqueAudio("skip-audio")))),
                onSynthesize = { _, _ -> },
            )
            val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.ExplicitlySkipped)))
            val coordinator = coordinator(this, store, { "alpha" }, synthesis, audio)

            coordinator.schedule(response.context, DelayedPlaybackRequest(response.messageId, "skip me"))
            advanceUntilIdle()

            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response.messageId))
            assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))
        }
    }

    @Test
    fun pausedChannelDrainPreventsPlaybackOfPendingResponses() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val response = pendingResponse(store, "paused", channel = "alpha", text = "queued", createdAtMillis = 10)
            // Pause the drain before scheduling.
            requireSuccess(store.skipPlaybackAndPause("alpha", response.messageId))
            assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))

            val synthesis = RecordingSynthesis(ArrayDeque(), { _, _ -> })
            val audio = RecordingAudio(ArrayDeque())
            val coordinator = coordinator(this, store, { "alpha" }, synthesis, audio)

            coordinator.schedule(response.context, DelayedPlaybackRequest(response.messageId, "queued"))
            advanceUntilIdle()

            // The paused drain prevents synthesis and playback.
            assertTrue(synthesis.texts.isEmpty())
            assertTrue(audio.requests.isEmpty())
            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response.messageId))
        }
    }

    @Test
    fun passiveAudioAvailableDoesNotResumeAPausedChannelButExplicitChannelSelectionDoes() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val response = pendingResponse(store, "paused-resume", channel = "alpha", text = "resume me", createdAtMillis = 10)
            val artifact = opaqueAudio("resume-artifact")
            val synthesis = RecordingSynthesis(
                outcomes = ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(artifact))),
                onSynthesize = { _, _ -> },
            )
            val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
            val coordinator = coordinator(this, store, { "alpha" }, synthesis, audio)

            coordinator.schedule(response.context, DelayedPlaybackRequest(response.messageId, "resume me"))
            advanceUntilIdle()

            // The response played and was heard.
            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response.messageId))

            // Now schedule a second response and pause the drain via an explicit skip.
            val second = pendingResponse(store, "second", channel = "alpha", text = "second reply", createdAtMillis = 20)
            requireSuccess(store.skipPlaybackAndPause("alpha", response.messageId))
            assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))

            val secondSynthesis = RecordingSynthesis(
                outcomes = ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(opaqueAudio("second-artifact")))),
                onSynthesize = { _, _ -> },
            )
            val secondAudio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
            val secondCoordinator = coordinator(this, store, { "alpha" }, secondSynthesis, secondAudio)

            secondCoordinator.schedule(second.context, DelayedPlaybackRequest(second.messageId, "second reply"))
            advanceUntilIdle()

            // Passive onAudioAvailable does NOT resume a paused drain.
            assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))
            assertTrue(secondSynthesis.texts.isEmpty())

            // Explicit same-channel selection resumes the drain and plays the pending response.
            secondCoordinator.onChannelSelected("alpha")
            advanceUntilIdle()

            assertEquals(PlaybackDrainState.ENABLED, store.playbackDrainState("alpha"))
            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, second.messageId))
            assertEquals(listOf("second reply"), secondSynthesis.texts)
        }
    }

    @Test
    fun explicitChannelSelectionDoesNotResumeAnotherChannelPausedDrain() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val alphaResponse = pendingResponse(store, "alpha-resp", channel = "alpha", text = "alpha", createdAtMillis = 10)
            val bravoResponse = pendingResponse(store, "bravo-resp", channel = "bravo", text = "bravo", createdAtMillis = 10)

            // Pause alpha's drain via an explicit skip.
            requireSuccess(store.skipPlaybackAndPause("alpha", alphaResponse.messageId))
            assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))

            val bravoSynthesis = RecordingSynthesis(
                outcomes = ArrayDeque(listOf(DelayedPlaybackSynthesisResult.Success(opaqueAudio("bravo-audio")))),
                onSynthesize = { _, _ -> },
            )
            val bravoAudio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
            val coordinator = coordinator(this, store, { "bravo" }, bravoSynthesis, bravoAudio)

            coordinator.schedule(bravoResponse.context, DelayedPlaybackRequest(bravoResponse.messageId, "bravo"))
            advanceUntilIdle()

            // Bravo plays normally.
            assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, bravoResponse.messageId))
            // Alpha's drain is still paused.
            assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))
        }
    }

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        store: DurableAgentRunStore,
        selectedChannel: suspend () -> String?,
        synthesis: DelayedPlaybackSynthesisPort,
        audio: DelayedPlaybackAudioPort,
        operationIsCurrent: suspend (AgentOperationContext) -> Boolean = { true },
    ) = DelayedPlaybackCoordinator(
        scope = scope,
        store = store,
        selectedChannel = selectedChannel,
        operationIsCurrent = operationIsCurrent,
        synthesis = synthesis,
        audio = audio,
    )

    private fun pendingResponse(
        store: DurableAgentRunStore,
        suffix: String,
        channel: String,
        text: String,
        createdAtMillis: Long,
    ): ResponseFixture {
        val run = AgentRunId("run-$suffix")
        val message = AgentMessageId("message-$suffix")
        val response = AgentMessageId("response-$suffix")
        requireSuccess(
            store.admit(
                messageId = message,
                runId = run,
                channelInstanceId = channel,
                conversationEpoch = 4,
                configurationEpoch = 6,
                configuration = DurableAgentConfiguration("profile", "model", "system", "fingerprint"),
                text = "request-$suffix",
                admittedAtMillis = createdAtMillis - 1,
            ),
        )
        requireSuccess(store.beginRun(run))
        requireSuccess(store.commitInboundResponse(run, response, text, createdAtMillis))
        return ResponseFixture(
            messageId = response,
            context = AgentOperationContext(
                scope = CapabilityScopeIdentity(channel, RuntimeGeneration(2)),
                runId = run,
                operationId = AgentOperationId("operation-$suffix"),
            ),
        )
    }

    private fun lifecycle(store: DurableAgentRunStore, messageId: AgentMessageId): DurableMessageLifecycle =
        store.snapshot().messages.single { it.id == messageId }.lifecycle

    private fun <T> requireSuccess(result: DurableAgentStoreResult<T>): T = when (result) {
        is DurableAgentStoreResult.Success -> result.value
        is DurableAgentStoreResult.Failure -> throw AssertionError("Expected success, got $result")
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("delayed-playback-coordinator-test-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class ResponseFixture(
        val messageId: AgentMessageId,
        val context: AgentOperationContext,
    )


    private data class PlaybackRequest(
        val channelInstanceId: String,
        val audio: OpaqueSynthesizedAudio,
    )

    private class RecordingSynthesis(
        private val outcomes: ArrayDeque<DelayedPlaybackSynthesisResult>,
        private val onSynthesize: (String, DelayedPlaybackSynthesisResult) -> Unit,
    ) : DelayedPlaybackSynthesisPort {
        val texts = mutableListOf<String>()

        override suspend fun synthesize(text: String): DelayedPlaybackSynthesisResult {
            texts += text
            val outcome = outcomes.removeFirst()
            onSynthesize(text, outcome)
            return outcome
        }
    }

    private class RecordingAudio(
        private val outcomes: ArrayDeque<DelayedPlaybackAudioResult>,
    ) : DelayedPlaybackAudioPort {
        val requests = mutableListOf<PlaybackRequest>()

        override suspend fun playIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueSynthesizedAudio,
        ): DelayedPlaybackAudioResult {
            requests += PlaybackRequest(channelInstanceId, audio)
            return outcomes.removeFirst()
        }
    }

    private class ThrowingThenCompletingAudio : DelayedPlaybackAudioPort {
        val requests = mutableListOf<PlaybackRequest>()
        private var firstAttempt = true

        override suspend fun playIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueSynthesizedAudio,
        ): DelayedPlaybackAudioResult {
            requests += PlaybackRequest(channelInstanceId, audio)
            if (firstAttempt) {
                firstAttempt = false
                throw IllegalStateException("audio acknowledgement lost")
            }
            return DelayedPlaybackAudioResult.Completed
        }
    }
    private fun opaqueAudio(operationId: String): OpaqueSynthesizedAudio =
        SynthesizedAudioArtifact(floatArrayOf(0.25f), operationId)
}
