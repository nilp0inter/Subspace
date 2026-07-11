package dev.nilp0inter.subspace.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelCardPresentationTest {
    @Test
    fun `channel cards present selection availability and PTT state with intentional precedence`() {
        val cases = listOf(
            Case(
                name = "selected available",
                isActive = true,
                isAvailable = true,
                isPttActive = false,
                isLocked = false,
                expectedLabel = "ACTIVE",
                expectedTone = ChannelCardTone.Primary,
            ),
            Case(
                name = "selected unavailable remains active",
                isActive = true,
                isAvailable = false,
                isPttActive = false,
                isLocked = false,
                expectedLabel = "ACTIVE",
                expectedTone = ChannelCardTone.Primary,
            ),
            Case(
                name = "unselected available",
                isActive = false,
                isAvailable = true,
                isPttActive = false,
                isLocked = false,
                expectedLabel = "READY",
                expectedTone = ChannelCardTone.Secondary,
            ),
            Case(
                name = "unselected unavailable",
                isActive = false,
                isAvailable = false,
                isPttActive = false,
                isLocked = false,
                expectedLabel = "UNAVAILABLE",
                expectedTone = ChannelCardTone.Secondary,
            ),
            Case(
                name = "PTT overrides selected unavailable state",
                isActive = true,
                isAvailable = false,
                isPttActive = true,
                isLocked = false,
                expectedLabel = "PTT",
                expectedTone = ChannelCardTone.Secondary,
            ),
            Case(
                name = "LOCKED overrides PTT selected unavailable state",
                isActive = true,
                isAvailable = false,
                isPttActive = true,
                isLocked = true,
                expectedLabel = "LOCKED",
                expectedTone = ChannelCardTone.Secondary,
            ),
        )

        cases.forEach { case ->
            val presentation = channelCardPresentation(
                isActive = case.isActive,
                isAvailable = case.isAvailable,
                isPttActive = case.isPttActive,
                isLocked = case.isLocked,
            )

            assertEquals("${case.name} label", case.expectedLabel, presentation.statusLabel)
            assertEquals("${case.name} tone", case.expectedTone, presentation.tone)
        }
    }

    private data class Case(
        val name: String,
        val isActive: Boolean,
        val isAvailable: Boolean,
        val isPttActive: Boolean,
        val isLocked: Boolean,
        val expectedLabel: String,
        val expectedTone: ChannelCardTone,
    )
}
