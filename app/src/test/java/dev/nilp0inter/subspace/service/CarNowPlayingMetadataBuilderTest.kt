package dev.nilp0inter.subspace.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNowPlayingMetadataBuilderTest {
    private val drawables = CarNowPlayingDrawables(
        notReadyResId = 1001,
        readyResId = 1002,
        recordingResId = 1003,
        finalizingResId = 1004,
    )
    private val artist = "Subspace"
    private val notReadyTitle = "Subspace not ready"

    @Test
    fun readyWithActiveChannelEmitsActivePillAndChannelTitle() {
        val meta = buildCarNowPlayingMetadata(
            activeChannelName = "Captain's Log",
            state = CarMediaPttState.Ready,
            pendingCount = 0,
            appArtist = artist,
            notReadyFallbackTitle = notReadyTitle,
            drawables = drawables,
        )

        assertEquals("Captain's Log", meta.title)
        assertEquals("Subspace", meta.artist)
        assertEquals("ACTIVE", meta.subtitle)
        assertEquals(1002, meta.drawableResId)
    }

    @Test
    fun recordingWithActiveChannelEmitsRecordingPillAndChannelTitle() {
        val meta = buildCarNowPlayingMetadata(
            activeChannelName = "Captain's Log",
            state = CarMediaPttState.Recording,
            pendingCount = 0,
            appArtist = artist,
            notReadyFallbackTitle = notReadyTitle,
            drawables = drawables,
        )

        assertEquals("Captain's Log", meta.title)
        assertEquals("RECORDING", meta.subtitle)
        assertEquals(1003, meta.drawableResId)
    }

    @Test
    fun finalizingEmitsFinalizingPillAndFinalizingDrawable() {
        val meta = buildCarNowPlayingMetadata(
            activeChannelName = "Journal",
            state = CarMediaPttState.Finalizing,
            pendingCount = 0,
            appArtist = artist,
            notReadyFallbackTitle = notReadyTitle,
            drawables = drawables,
        )

        assertEquals("FINALIZING", meta.subtitle)
        assertEquals(1004, meta.drawableResId)
    }

    @Test
    fun notReadyWithNullChannelFallsBackToNotReadyTitle() {
        val meta = buildCarNowPlayingMetadata(
            activeChannelName = null,
            state = CarMediaPttState.NotReady,
            pendingCount = 0,
            appArtist = artist,
            notReadyFallbackTitle = notReadyTitle,
            drawables = drawables,
        )

        assertEquals(notReadyTitle, meta.title)
        assertEquals("NOT READY", meta.subtitle)
        assertEquals(1001, meta.drawableResId)
    }

    @Test
    fun notReadyWithActiveChannelKeepsChannelTitleAndNotReadyPill() {
        val meta = buildCarNowPlayingMetadata(
            activeChannelName = "Captain's Log",
            state = CarMediaPttState.NotReady,
            pendingCount = 0,
            appArtist = artist,
            notReadyFallbackTitle = notReadyTitle,
            drawables = drawables,
        )

        assertEquals("Captain's Log", meta.title)
        assertEquals("NOT READY", meta.subtitle)
    }

    @Test
    fun pendingGreaterThanZeroAppendsPendingSummary() {
        val meta = buildCarNowPlayingMetadata(
            activeChannelName = "Captain's Log",
            state = CarMediaPttState.Ready,
            pendingCount = 3,
            appArtist = artist,
            notReadyFallbackTitle = notReadyTitle,
            drawables = drawables,
        )

        assertEquals("ACTIVE · 3 pending", meta.subtitle)
    }

    @Test
    fun pendingZeroOmitsPendingSummaryAcrossAllStates() {
        CarMediaPttState.values().forEach { state ->
            val meta = buildCarNowPlayingMetadata(
                activeChannelName = "Channel",
                state = state,
                pendingCount = 0,
                appArtist = artist,
                notReadyFallbackTitle = notReadyTitle,
                drawables = drawables,
            )
            assertEquals(statePillText(state), meta.subtitle)
        }
    }

    @Test
    fun subtitleTruncatesPendingPortionFirstWhenBudgetExceeded() {
        // Today's Int-typed active-channel pending count cannot overflow a
        // <= 9-char state pill, so we drive the rule directly with an oversized
        // pill (the spec's truncation guarantee applies to future inactive-
        // channel suffixes such as "3 pending on Captain's Log").
        val longPillThatWouldOverflow = "X".repeat(CAR_NOW_PLAYING_SUBTITLE_LIMIT + 5)
        val wouldOverflow = longPillThatWouldOverflow +
            CAR_NOW_PLAYING_PENDING_PREFIX_SEPARATOR + "3 pending"
        assertTrue(wouldOverflow.length > CAR_NOW_PLAYING_SUBTITLE_LIMIT)

        assertEquals(longPillThatWouldOverflow, appendPending(longPillThatWouldOverflow, 3))

        assertTrue("ACTIVE · 3 pending".length <= CAR_NOW_PLAYING_SUBTITLE_LIMIT)
        assertEquals("ACTIVE · 3 pending", appendPending("ACTIVE", 3))
        assertEquals("ACTIVE", appendPending("ACTIVE", 0))
    }

    @Test
    fun drawableSelectionSwitchesByState() {
        CarMediaPttState.values().forEach { state ->
            val meta = buildCarNowPlayingMetadata(
                activeChannelName = "Channel",
                state = state,
                pendingCount = 0,
                appArtist = artist,
                notReadyFallbackTitle = notReadyTitle,
                drawables = drawables,
            )
            val expected = when (state) {
                CarMediaPttState.NotReady -> 1001
                CarMediaPttState.Ready -> 1002
                CarMediaPttState.Recording -> 1003
                CarMediaPttState.Finalizing -> 1004
            }
            assertEquals("drawable for $state", expected, meta.drawableResId)
        }
    }
}