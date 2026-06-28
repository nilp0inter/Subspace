package dev.nilp0inter.subspace.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CarSkipDecisionTest {
    @Test
    fun notReadyIsNoOpForBothNextAndPrev() {
        val (next, prev) = CarSkipDecision.fromState(CarMediaPttState.NotReady)
        assertEquals(CarSkipAction.NoOp, next)
        assertEquals(CarSkipAction.NoOp, prev)
    }

    @Test
    fun readyAdvancesActiveChannelOnNextAndRetreatsOnPrev() {
        val (next, prev) = CarSkipDecision.fromState(CarMediaPttState.Ready)
        assertEquals(CarSkipAction.NextChannel, next)
        assertEquals(CarSkipAction.PrevChannel, prev)
    }

    @Test
    fun recordingIsNoOpToProtectCaptureLifecycle() {
        val (next, prev) = CarSkipDecision.fromState(CarMediaPttState.Recording)
        assertEquals(CarSkipAction.NoOp, next)
        assertEquals(CarSkipAction.NoOp, prev)
    }

    @Test
    fun finalizingSkipsCurrentMessageOnNextAndReplaysLastHeardOnPrev() {
        val (next, prev) = CarSkipDecision.fromState(CarMediaPttState.Finalizing)
        assertEquals(CarSkipAction.SkipMessage, next)
        assertEquals(CarSkipAction.ReplayMessage, prev)
    }

    @Test
    fun fromStateCoversEveryStateValue() {
        CarMediaPttState.values().forEach { state ->
            val (next, prev) = CarSkipDecision.fromState(state)
            // Every state must produce a non-null action pair; no exceptions.
            assert(next in CarSkipAction.values().toSet()) { "unexpected next: $next for $state" }
            assert(prev in CarSkipAction.values().toSet()) { "unexpected prev: $prev for $state" }
        }
    }

    @Test
    fun emptyChannelListEdgeCaseHandledAtOffsetSelectionTime() {
        // The decision function itself is state-only; the empty-channel-list
        // edge case lives in the offset-selection helper. Verify it returns
        // null saturating signal which the MediaSession callback no-ops on.
        val emptyOrderedIds = emptyList<String>()
        val selected = selectChannelByOffsetForTest(emptyOrderedIds, "anything", +1)
        assertEquals(null, selected)
    }

    @Test
    fun offsetSelectionSaturatesAtBoundsWithoutWraparound() {
        val orderedIds = listOf("captains-log", "debug-channel")
        // Next beyond last index saturates — no wraparound.
        assertEquals("debug-channel", selectChannelByOffsetForTest(orderedIds, "debug-channel", +1))
        // Prev before first index saturates — no wraparound.
        assertEquals("captains-log", selectChannelByOffsetForTest(orderedIds, "captains-log", -1))
        // Standard advance.
        assertEquals("debug-channel", selectChannelByOffsetForTest(orderedIds, "captains-log", +1))
        assertEquals("captains-log", selectChannelByOffsetForTest(orderedIds, "debug-channel", -1))
    }

    private fun selectChannelByOffsetForTest(
        orderedIds: List<String>,
        currentChannelId: String,
        offset: Int,
    ): String? {
        // The pure selection helper lives in the model package and is exercised
        // here through the same surface used by PttForegroundService.
        return dev.nilp0inter.subspace.model.selectChannelByOffset(orderedIds, currentChannelId, offset)
    }
}