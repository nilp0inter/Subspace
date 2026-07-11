package dev.nilp0inter.subspace.model

import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelPreparationReason
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBrowseEntryProjectionTest {
    @Test
    fun `every persisted instance remains Auto-playable while unavailable states retain recovery metadata`() {
        val state = AppState(
            channels = listOf(
                snapshot("instance-alpha", "Alpha", "test:shared", ChannelPreparationAvailability.Available),
                snapshot(
                    "instance-missing",
                    "Missing implementation",
                    "package:missing",
                    ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                ),
                snapshot(
                    "instance-recovering",
                    "Recovering implementation",
                    "test:shared",
                    ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising),
                ),
                snapshot(
                    "instance-disabled",
                    "Disabled channel",
                    "test:shared",
                    ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled),
                    enabled = false,
                ),
            ),
            activeChannelId = "instance-alpha",
        )

        val entries = projectChannelBrowseEntries(state)

        assertEquals(
            listOf("instance-alpha", "instance-missing", "instance-recovering", "instance-disabled"),
            entries.map(ChannelBrowseEntry::id),
        )
        assertEquals(
            listOf("Alpha", "Missing implementation", "Recovering implementation", "Disabled channel"),
            entries.map(ChannelBrowseEntry::name),
        )
        assertTrue(entries.all(ChannelBrowseEntry::isPlayable))
        assertEquals(ChannelStatusKind.Active, entries[0].statusKind)
        assertEquals(ChannelStatusKind.Unavailable, entries[1].statusKind)
        assertEquals(
            "Channel is no longer available. Recover or remove this channel on your phone.",
            entries[1].recoveryMessage,
        )
        assertEquals(ChannelStatusKind.Unavailable, entries[2].statusKind)
        assertEquals(
            "Channel is initialising. Recover or remove this channel on your phone.",
            entries[2].recoveryMessage,
        )
        assertEquals(ChannelStatusKind.Unavailable, entries[3].statusKind)
        assertEquals(
            "Channel is disabled. Recover or remove this channel on your phone.",
            entries[3].recoveryMessage,
        )
    }

    @Test
    fun `multiple instances of one provider remain distinct and active selection is instance scoped`() {
        val state = AppState(
            channels = listOf(
                snapshot("first-instance", "Shared provider one", "test:shared", ChannelPreparationAvailability.Available),
                snapshot("second-instance", "Shared provider two", "test:shared", ChannelPreparationAvailability.Available),
            ),
            activeChannelId = "second-instance",
        )

        val entries = projectChannelBrowseEntries(state)

        assertEquals(listOf("first-instance", "second-instance"), entries.map(ChannelBrowseEntry::id))
        assertEquals(listOf(ChannelStatusKind.Ready, ChannelStatusKind.Active), entries.map(ChannelBrowseEntry::statusKind))
        assertTrue(entries.all(ChannelBrowseEntry::isPlayable))
    }

    @Test
    fun `availability recovery invalidates only projection state without changing media identity`() {
        val unavailable = AppState(
            channels = listOf(
                snapshot(
                    "retained-instance",
                    "Retained channel",
                    "test:recovering",
                    ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.ProviderInitialising),
                ),
            ),
            activeChannelId = "retained-instance",
        )
        val recovered = unavailable.copy(
            channels = listOf(
                snapshot(
                    "retained-instance",
                    "Retained channel",
                    "test:recovering",
                    ChannelPreparationAvailability.Available,
                ),
            ),
        )

        val before = projectChannelBrowseEntries(unavailable).single()
        val after = projectChannelBrowseEntries(recovered).single()

        assertTrue(before.isPlayable)
        assertEquals(ChannelStatusKind.Unavailable, before.statusKind)
        assertTrue(after.isPlayable)
        assertEquals(ChannelStatusKind.Active, after.statusKind)
        assertNull(after.recoveryMessage)
    }

    @Test
    fun `pending count follows stable instance identity rather than provider identity`() {
        val state = AppState(
            channels = listOf(
                snapshot("shared-one", "First", "test:shared", ChannelPreparationAvailability.Available),
                snapshot("shared-two", "Second", "test:shared", ChannelPreparationAvailability.Available),
            ),
            activeChannelId = "shared-one",
        )

        val entries = projectChannelBrowseEntries(state, pendingCounts = mapOf("shared-two" to 4))

        assertEquals(listOf(0, 4), entries.map(ChannelBrowseEntry::pendingCount))
    }

    private fun snapshot(
        id: String,
        name: String,
        implementationId: String,
        preparation: ChannelPreparationAvailability,
        enabled: Boolean = true,
    ) = ChannelRuntimeSnapshot(
        id = id,
        name = name,
        implementationId = ChannelImplementationId(implementationId),
        enabled = enabled,
        preparation = preparation,
        executionStatus = ChannelExecutionStatus.IDLE,
    )
}
