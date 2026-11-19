package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.LiveTvSettings

/** The parts of the saved user state that [buildUi] reads; keep in step with it. */
internal fun sameForLists(a: LiveUserState, b: LiveUserState): Boolean =
    a.favorites == b.favorites && a.favoriteGroups == b.favoriteGroups && a.recent == b.recent &&
        a.hiddenChannels == b.hiddenChannels && a.hiddenGroups == b.hiddenGroups &&
        a.lockedGroups == b.lockedGroups && a.customGroups == b.customGroups &&
        a.channelOrder == b.channelOrder && a.groupOrder == b.groupOrder &&
        a.channelCopies == b.channelCopies

internal fun LiveTvRepository.buildUi(
    channels: List<LiveChannel>,
    user: LiveUserState,
    s: LiveTvSettings,
    groupIdRaw: String?,
    query: String,
    hasSrc: Boolean,
    /**
     * Home row: the asked-for group or nothing. Favorites / Recently watched work even when
     * hidden from the guide's group list, and a group that's gone shows no channels instead
     * of falling back to All channels (thousands of cards on the home screen).
     */
    exactGroup: Boolean = false,
    providerRanks: Map<String, Int> = providerGroupRanks.value
): LiveTvUiState {
    if (exactGroup && (groupIdRaw == ChannelGroup.FAVORITES && user.favorites.isEmpty() ||
            groupIdRaw == ChannelGroup.RECENT && user.recent.isEmpty())) return LiveTvUiState()
    val shown = channels.filter { it.key !in user.hiddenChannels && it.groupId !in user.hiddenGroups }
    // Parental controls: channels in locked groups stay out of every list until unlocked.
    val lockedIds = HashSet<String>()
    shown.distinctBy { it.groupId }.forEach { c ->
        if (com.nuvio.tv.livetv.parental.ParentalControls.isLocked(c.groupId, c.group, s, user.lockedGroups)) lockedIds += c.groupId
    }
    user.customGroups.forEach { g ->
        if (com.nuvio.tv.livetv.parental.ParentalControls.isLocked(g.id, g.name, s, user.lockedGroups)) lockedIds += g.id
    }
    val visible = shown.filter { it.groupId !in lockedIds }
    val byKey = visible.associateBy { it.key }
    val favorites = user.favorites.mapNotNull { byKey[it] }
    val recent = user.recent.mapNotNull { byKey[it] }

    fun sorted(list: List<LiveChannel>): List<LiveChannel> = when (s.channelSort) {
        ChannelSort.PLAYLIST -> list
        ChannelSort.NUMBER -> list.sortedBy { it.number }
        ChannelSort.NAME -> list.sortedBy { it.name.lowercase() }
    }

    /** Your order from "Reorder channels" wins; channels you haven't placed follow as sorted. */
    fun ordered(groupId: String, list: List<LiveChannel>): List<LiveChannel> {
        val sourceId = visible.firstOrNull { it.groupId == groupId }?.sourceId
        val order = user.channelOrderFor(groupId, sourceId) ?: return sorted(list)
        val byKeyHere = list.associateBy { it.key }
        val placed = order.mapNotNull { byKeyHere[it] }
        val placedKeys = placed.mapTo(HashSet()) { it.key }
        return placed + sorted(list.filter { it.key !in placedKeys })
    }

    /** A playlist group's own channels plus channels copied into it. */
    fun groupChannels(groupId: String): List<LiveChannel> {
        val own = visible.filter { it.groupId == groupId }
        val ownKeys = own.mapTo(HashSet()) { it.key }
        val copies = user.channelCopies[groupId].orEmpty().mapNotNull { byKey[it] }.filter { it.key !in ownKeys }
        return own + copies
    }

    fun channelsFor(groupId: String): List<LiveChannel> {
        val rawList = when {
            groupId == ChannelGroup.ALL && s.showAllChannelsGroup -> ordered(ChannelGroup.ALL, visible)
            // A saved manual order takes precedence for this group alone.
            groupId == ChannelGroup.FAVORITES -> ordered(groupId, favorites)
            groupId == ChannelGroup.RECENT -> recent
            groupId == ChannelGroup.SEARCH -> {
                val q = query.trim()
                if (q.isEmpty()) emptyList() else sorted(visible.filter {
                    it.name.contains(q, ignoreCase = true) || it.number.toString() == q
                })
            }
            groupId in lockedIds -> emptyList()
            groupId.startsWith(ChannelGroup.CUSTOM_PREFIX) ->
                ordered(groupId, user.customGroups.firstOrNull { it.id == groupId }?.channelKeys?.mapNotNull { byKey[it] }.orEmpty())
            else -> ordered(groupId, groupChannels(groupId)).distinctBy { it.key }
        }
        // TiviMate's number override: 1, 2, 3… in the order the group shows them.
        return if (s.sequentialNumbers && groupId != ChannelGroup.SEARCH) {
            rawList.mapIndexed { i, c -> c.copy(number = i + 1) }
        } else rawList
    }

    if (exactGroup) return LiveTvUiState(channels = channelsFor(groupIdRaw.orEmpty()))

    val groups = mutableListOf<ChannelGroup>()
    groups += ChannelGroup(ChannelGroup.SEARCH, "Search", 0, special = true)
    if (s.showFavoritesGroup) groups += ChannelGroup(ChannelGroup.FAVORITES, "Favorites", favorites.size, special = true)
    if (s.showRecentGroup) groups += ChannelGroup(ChannelGroup.RECENT, "Recently watched", recent.size, special = true)
    if (s.showAllChannelsGroup) groups += ChannelGroup(ChannelGroup.ALL, "All channels", visible.size, special = true)

    // Your own groups first, then the playlist's groups, then the saved order on top.
    val regular = mutableListOf<ChannelGroup>()
    val custom = user.customGroups
        .filter { it.id !in user.hiddenGroups }
        .map { g -> ChannelGroup(g.id, g.name, g.channelKeys.count { it in byKey }, locked = g.id in lockedIds) }
    regular += custom
    val playlistGroups = LinkedHashMap<String, Pair<String, Int>>()
    shown.forEach { c ->
        val cur = playlistGroups[c.groupId]
        playlistGroups[c.groupId] = (cur?.first ?: c.group) to ((cur?.second ?: 0) + 1)
    }
    val groupSource = HashMap<String, String>()
    shown.forEach { c -> groupSource.putIfAbsent(c.groupId, c.sourceId) }
    playlistGroups.forEach { (id, v) ->
        val copied = user.channelCopies[id].orEmpty().count { k -> byKey[k]?.let { it.groupId != id } == true }
        regular += ChannelGroup(id, v.first, v.second + copied, sourceId = groupSource[id], locked = id in lockedIds)
    }
    val orderedRegular = orderLiveGroups(regular, user.groupOrder, s.groupSort, s.groupPlaylistHeadings, providerRanks)
    groups += orderedRegular.map { it.copy(favorite = user.isFavoriteGroup(it)) }
        .sortedByDescending { it.favorite }
    // Keep a long-press recovery route even if every regular group is hidden and
    // the user has switched off all optional built-in group rows.
    if (groups.none { it.id != ChannelGroup.SEARCH }) {
        groups += ChannelGroup(ChannelGroup.ALL, "All channels", visible.size, special = true)
    }

    val defaultGroupId = when {
        s.showAllChannelsGroup -> ChannelGroup.ALL
        orderedRegular.isNotEmpty() -> orderedRegular.first().id
        s.showFavoritesGroup -> ChannelGroup.FAVORITES
        s.showRecentGroup -> ChannelGroup.RECENT
        else -> ChannelGroup.ALL
    }
    val groupId = groupIdRaw?.takeIf { id -> groups.any { it.id == id } } ?: defaultGroupId
    val list = channelsFor(groupId)
    return LiveTvUiState(
        groups = groups,
        selectedGroupId = groupId,
        channels = list,
        allVisibleChannels = sorted(visible),
        hasSources = hasSrc,
        searchQuery = query,
        orderableGroupIds = orderedRegular.map { it.id },
        customGroups = custom,
        defaultGroupId = defaultGroupId
    )
}

