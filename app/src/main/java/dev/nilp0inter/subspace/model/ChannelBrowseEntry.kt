package dev.nilp0inter.subspace.model

/**
 * Media-session-facing projection of a [Channel] for the Android Auto Media
 * browse tree (see `car-media-channel-browse` capability).
 *
 * The browse tree renders one row per entry; the row's mediaId is [id], title
 * is [name], and the subtitle combines [statusKind] with [pendingCount] (the
 * count is omitted from the subtitle when zero).
 *
 * The projection is produced by [projectChannelBrowseEntries] from an [AppState]
 * plus a per-channel pending-unheard count map (today always empty; a future
 * inbound-backlog tracker will feed the map without changing this data class).
 */
data class ChannelBrowseEntry(
    val id: String,
    val name: String,
    val statusKind: ChannelStatusKind,
    val pendingCount: Int,
)

enum class ChannelStatusKind { Active, Ready, Standby }

/**
 * Stable ordering key for [Channel]. Channels are ordered ascending by this
 * index on both the phone dashboard and the Android Auto browse tree so
 * "Next/Prev" steering-wheel semantics and dashboard tap-to-activate share the
 * same mental model (see `car-media-channel-browse` spec: "Channel ordering is
 * stable across surfaces"). Defaults give [JournalChannel] index 0 and
 * [DebugChannel] index 1.
 */

/**
 * Pure projection from [AppState] to a [ChannelBrowseEntry] list ordered by
 * [Channel.orderIndex], suitable for binding to Android Auto `onLoadChildren`.
 *
 * Active/Ready/Standby rule:
 *  - [ChannelStatusKind.Active]: channel id matches [AppState.activeChannelId].
 *  - [ChannelStatusKind.Ready]: channel [Channel.isReady] but not active.
 *  - [ChannelStatusKind.Standby]: channel not ready and not active.
 *
 * [pendingCounts] lets a future inbound-backlog tracker feed per-channel
 * pending counts without touching this function; missing entries default to 0.
 */
fun projectChannelBrowseEntries(
    appState: AppState,
    pendingCounts: Map<String, Int> = emptyMap(),
): List<ChannelBrowseEntry> {
    return appState.channels.map { channel ->
        ChannelBrowseEntry(
            id = channel.id,
            name = channel.name,
            statusKind = when {
                appState.activeChannelId == channel.id -> ChannelStatusKind.Active
                channel.isReady -> ChannelStatusKind.Ready
                else -> ChannelStatusKind.Standby
            },
            pendingCount = pendingCounts[channel.id] ?: 0,
        )
    }
}

/**
 * Stable ordered list of channel ids, sorted by [Channel.orderIndex]. Consumed
 * by both the phone dashboard and the Android Auto Media surface so
 * `Next/Prev` and tap-to-activate share the same ordering.
 */
fun orderedChannelIds(appState: AppState): List<String> =
    projectChannelBrowseEntries(appState).map { it.id }

/**
 * Selects a channel id in the stable [Channel.orderIndex] ordering, saturating
 * at the bounds (no wraparound — see design D5 / spec
 * `car-contextual-skip-controls` "Previous saturates at the first channel
 * rather than wrap").
 *
 * [orderedChannelIds] MUST be in [Channel.orderIndex] order (use
 * [orderedChannelIds]). Returns null when the list is empty (the
 * empty-channel-list edge case in `car-contextual-skip-controls`).
 */
fun selectChannelByOffset(
    orderedChannelIds: List<String>,
    currentChannelId: String,
    offset: Int,
): String? {
    if (orderedChannelIds.isEmpty()) return null
    val currentIndex = orderedChannelIds.indexOf(currentChannelId).let {
        if (it < 0) 0 else it
    }
    val target = (currentIndex + offset).coerceIn(0, orderedChannelIds.lastIndex)
    return orderedChannelIds[target]
}