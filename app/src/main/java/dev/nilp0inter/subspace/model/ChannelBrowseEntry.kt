package dev.nilp0inter.subspace.model

data class ChannelBrowseEntry(
    val id: String,
    val name: String,
    val statusKind: ChannelStatusKind,
    val pendingCount: Int,
)

enum class ChannelStatusKind { Active, Ready, Standby }

val Channel.orderIndex: Int
    get() = position

fun projectChannelBrowseEntries(
    appState: AppState,
    pendingCounts: Map<String, Int> = emptyMap(),
): List<ChannelBrowseEntry> = appState.channels.map { channel ->
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

fun orderedChannelIds(appState: AppState): List<String> = appState.channels.map { it.id }

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
