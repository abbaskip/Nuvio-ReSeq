package com.nuvio.tv.data.cw

/** Per-show publication, independent of the order/duration of network and metadata work. */
internal class CwCardPublication {
    private val settledHistory = mutableMapOf<String, String>()

    private var preferFurthest: Boolean? = null
    private var nextUpGeneration = 0L

    fun reset() {
        settledHistory.clear()
        preferFurthest = null
    }

    fun beginNextUp(lease: CwLease, furthest: Boolean): CwLease {
        if (preferFurthest != furthest) {
            preferFurthest = furthest
            nextUpGeneration++
        }
        return lease.copy(nextUpGeneration = nextUpGeneration)
    }

    fun isCurrent(lease: CwLease) = lease.nextUpGeneration == null || lease.nextUpGeneration == nextUpGeneration

    fun settle(state: CwProjection, lease: CwLease, ids: Set<String>): CwProjection {
        if (!CwMembershipReducer.accepts(state, lease)) return state
        val shown = (state.presentedMembers ?: state.members).toMutableMap()
        for (id in ids) {
            val member = state.members[id]
            if ((member?.changedAtRevision ?: 0) > lease.revision) continue
            // Failure/loading is not a settled answer, including when history has changed.
            if (member?.inProgress?.chooseEpisode == true || member?.nextUp?.chooseEpisode == true) continue
            val history = history(state, id)
            if (settledHistory[id] == history) continue
            if (member == null) shown.remove(id) else shown[id] = member
        }
        return state.copy(presentedMembers = shown)
    }

    /** Called only after the presentation's durable commit succeeds. */
    fun committed(state: CwProjection, lease: CwLease, ids: Set<String>) {
        if (!CwMembershipReducer.accepts(state, lease)) return
        for (id in ids) {
            val member = state.members[id]
            if ((member?.changedAtRevision ?: 0) <= lease.revision &&
                member?.inProgress?.chooseEpisode != true && member?.nextUp?.chooseEpisode != true &&
                state.presentedMembers?.get(id) == member) settledHistory[id] = history(state, id)
        }
    }

    private fun history(state: CwProjection, id: String): String {
        val member = state.members[id]
        val raw = member?.progress?.let { p -> listOf(p.videoId, p.season, p.episode, p.position,
            p.progressPercentage, p.lastWatched, p.source, p.traktPlaybackId, p.simklPlaybackId,
            p.tvdbContentIds.sorted(), p.tvdbWatchedIds.sorted(), p.trackingProviderId) }
        return listOf(preferFurthest.takeIf { member?.nextUp != null }, raw, member?.latestCompletionAt, state.completed[id], state.removals[id],
            id in state.hiddenByProvider).toString()
    }
}

internal fun CwProjection.forPresentation(): CwProjection = copy(members = presentedMembers ?: members)

internal fun CwProjection.presentChangesFrom(previous: CwProjection): CwProjection {
    val changed = (members.keys + previous.members.keys).filter { members[it] != previous.members[it] }
    val shown = (previous.presentedMembers ?: previous.members).toMutableMap()
    for (id in changed) members[id]?.let { shown[id] = it } ?: shown.remove(id)
    return copy(presentedMembers = shown)
}
