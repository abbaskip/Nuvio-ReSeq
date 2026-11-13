package com.nuvio.tv.data.cw

import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.WatchProgress

/** Namespace + publication lease. A metadata task cannot publish across a source/order switch. */
data class CwLease(val profileId: Int, val source: String, val epoch: Long, val revision: Long)

data class CwMember(
    val progress: WatchProgress? = null,
    val inProgress: CachedInProgressItem? = null,
    val nextUp: CachedNextUpItem? = null,
    val changedAtRevision: Long = 0
)

/** Compact positive completion evidence; identities are never derived from display order. */
data class CwCompletion(
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val contentIds: Set<Long>,
    val watchedIds: Set<Long>,
    val at: Long,
    val historyKey: String
)

data class CwProjection(
    val schema: Int = 1,
    val profileId: Int,
    val source: String,
    val epoch: Long = 0,
    val revision: Long = 0,
    val members: Map<String, CwMember> = emptyMap(),
    val removals: Map<String, Long> = emptyMap(),
    val ignoredProgress: Map<String, Long> = emptyMap(),
    val hiddenByProvider: Set<String> = emptySet(),
    val completed: Map<String, Map<String, CwCompletion>> = emptyMap()
) {
    val lease get() = CwLease(profileId, source, epoch, revision)
    val inProgress get() = members.values.mapNotNull { it.inProgress }
    val nextUp get() = members.values.mapNotNull { it.nextUp }
}

enum class CwRemovalReason { USER_REMOVAL, AUTHORITATIVE_ABSENCE }
data class CwRemovalProof(val reason: CwRemovalReason, val contentIds: Set<String>, val at: Long)

/**
 * The only membership removal owner. Enrichment, missing identity and incomplete refreshes
 * cannot express deletion. Progress and the offered card are committed as one projection.
 * No next-episode arithmetic is performed here: only the existing resolver can supply one.
 */
object CwMembershipReducer {
    /** Visibility is a named policy decision, never deletion from durable membership. */
    fun visible(state: CwProjection, daysCap: Int, dismissed: Set<String>, showUnaired: Boolean,
        now: Long, launcher: Boolean = false): CwProjection {
        val cutoff = com.nuvio.tv.domain.model.continueWatchingCutoff(daysCap, now)
        val candidates = state.members.mapValues { (_, member) ->
            val next = member.nextUp
            if (next != null && !next.hasAired && next.releaseTimestamp == null && next.released == null)
                member.copy(nextUp = next.copy(chooseEpisode = true)) else member
        }
        return state.copy(members = candidates.filter { (id, member) ->
            if (id in state.hiddenByProvider) return@filter false
            val ip = member.inProgress
            val next = member.nextUp
            when {
                ip != null -> cutoff == null || ip.lastWatched >= cutoff
                next != null -> {
                    val aired = next.chooseEpisode || next.releaseTimestamp?.let { now >= it } ?: next.hasAired
                    val legacyDismiss = "${id}_s${next.seedSeason}e${next.seedEpisode}"
                    id !in dismissed && legacyDismiss !in dismissed &&
                        (aired || (!launcher && showUnaired)) &&
                        (cutoff == null || maxOf(next.lastWatched, next.latestSeriesActivity ?: 0) >= cutoff ||
                            (!launcher && next.isReleaseAlert))
                }
                else -> false
            }
        })
    }

    fun accepts(state: CwProjection, lease: CwLease): Boolean =
        state.profileId == lease.profileId && state.source == lease.source && state.epoch == lease.epoch

    fun progress(state: CwProjection, updates: List<WatchProgress>, seeds: Boolean = false): CwProjection {
        val members = state.members.toMutableMap()
        val removals = state.removals.toMutableMap()
        val completed = state.completed.toMutableMap()
        updates.sortedBy { it.lastWatched }.forEach { p ->
            // Read old episode-specific replay markers too. A legacy series-only videoId
            // is ambiguous and must never suppress every episode in that show's history.
            val legacyIgnored = if (p.videoId != p.contentId) state.ignoredProgress[p.legacyHistoryKey()] else null
            if (p.lastWatched <= maxOf(state.ignoredProgress[p.historyKey()] ?: Long.MIN_VALUE, legacyIgnored ?: Long.MIN_VALUE) ||
                p.tvdbContentIds.any { p.lastWatched <= (state.ignoredProgress[tvdbRemovalKey(p.contentId, it)] ?: Long.MIN_VALUE) }) return@forEach
            if (p.lastWatched <= (removals[p.contentId] ?: Long.MIN_VALUE)) return@forEach
            if (p.isCompleted()) {
                val records = completed[p.contentId].orEmpty()
                val record = CwCompletion(p.videoId, p.season, p.episode, p.tvdbContentIds,
                    p.tvdbWatchedIds, p.lastWatched, p.historyKey())
                if (record.at >= (records[record.historyKey]?.at ?: Long.MIN_VALUE))
                    completed[p.contentId] = records + (record.historyKey to record)
            }
            val old = members[p.contentId]
            // A retained series placeholder (including one saved by local.1 after unwatch)
            // has presentation recency, not committed progress that can reject older history.
            val priorActivity = old?.progress?.lastWatched ?: old?.inProgress?.lastWatched ?:
                old?.nextUp?.takeUnless { it.chooseEpisode }?.lastWatched
            if (priorActivity != null && p.lastWatched < priorActivity) return@forEach
            if (old?.progress != null && p.lastWatched < old.progress.lastWatched) return@forEach
            // An identical remote replay cannot undo a locally committed completion.
            if (old?.progress?.isCompleted() == true && p.lastWatched == old.progress.lastWatched &&
                !p.isCompleted()) return@forEach
            val series = p.contentType in setOf("series", "tv", "anime")
            val member = when {
                p.isCompleted() && !series -> null
                p.isCompleted() || seeds -> {
                    // An offer was resolved from a particular seed. Completing a different
                    // episode invalidates it even when the offer is not that new completion.
                    val sameSeed = old?.progress?.let { it.historyKey() == p.historyKey() } == true
                    val next = old?.nextUp?.takeIf { sameSeed && (it.chooseEpisode ||
                        completed[p.contentId].orEmpty().values.none { done -> matches(it, done) }) }
                    CwMember(p, nextUp = next?.copy(lastWatched = maxOf(next.lastWatched, p.lastWatched))
                        ?: p.seriesChoice(), changedAtRevision = state.revision + 1)
                }
                p.isInProgress() || (!p.isCompleted() && (p.position > 0 || (p.progressPercent ?: 0f) > 0) &&
                    p.source !in setOf(WatchProgress.SOURCE_TRAKT_HISTORY, WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS)) -> CwMember(p, inProgress = p.cached(old?.inProgress),
                    changedAtRevision = state.revision + 1)
                else -> old
            }
            if (member == null && p.isCompleted()) {
                members.remove(p.contentId)
                removals[p.contentId] = p.lastWatched
            } else if (member != null) {
                // Avoid revision churn for duplicate provider/foreground refreshes.
                members[p.contentId] = if (member.copy(changedAtRevision = old?.changedAtRevision ?: 0) == old)
                    old else member
                removals.remove(p.contentId)
            }
        }
        return state.changed(members, removals, completed)
    }

    fun enrich(state: CwProjection, lease: CwLease,
        inProgress: List<CachedInProgressItem> = emptyList(), nextUp: List<CachedNextUpItem> = emptyList()
    ): CwProjection {
        if (!accepts(state, lease)) return state
        val members = state.members.toMutableMap()
        inProgress.forEach { candidate ->
            val old = members[candidate.contentId] ?: return@forEach
            if (old.changedAtRevision > lease.revision || old.progress?.isCompleted() == true) return@forEach
            val current = old.inProgress ?: return@forEach
            if (current.videoId != candidate.videoId || current.tvdbContext != candidate.tvdbContext) return@forEach
            // Enrichment cannot rewind playback position or change the progress identity.
            members[candidate.contentId] = old.copy(inProgress = candidate.copy(position = current.position,
                duration = current.duration, progressPercent = current.progressPercent, lastWatched = current.lastWatched))
        }
        nextUp.forEach { candidate ->
            val old = members[candidate.contentId] ?: return@forEach
            if (old.changedAtRevision > lease.revision || old.inProgress != null) return@forEach
            val p = old.progress
            if (p?.isCompleted() == true && !candidate.chooseEpisode && sameEpisode(candidate, p)) return@forEach
            if (!candidate.chooseEpisode && state.completed[candidate.contentId].orEmpty().values.any { matches(candidate, it) }) return@forEach
            members[candidate.contentId] = old.copy(nextUp = candidate)
        }
        return state.changed(members)
    }

    fun remove(state: CwProjection, lease: CwLease, proof: CwRemovalProof): CwProjection {
        if (!accepts(state, lease)) return state
        val ids = proof.contentIds.filter { id -> proof.reason == CwRemovalReason.USER_REMOVAL ||
            (state.members[id]?.changedAtRevision ?: 0) <= lease.revision }.toSet()
        return state.changed(state.members - ids, state.removals + ids.associateWith { proof.at })
    }

    fun invalidate(state: CwProjection, ids: Set<String>): CwProjection = state.copy(
        epoch = state.epoch + 1, revision = state.revision + 1,
        members = state.members.mapValues { (id, member) ->
            if (id !in ids) member else member.copy(nextUp = member.nextUp?.copy(chooseEpisode = true))
        })

    /** Unwatching an episode revokes its completion evidence, not the whole series. */
    fun historyRemoved(state: CwProjection, contentId: String, episodes: Set<Pair<Int, Int>>, at: Long,
        tvdbIds: Set<Long> = emptySet()): CwProjection {
        fun removed(season: Int?, episode: Int?, ids: Set<Long>) =
            if (tvdbIds.isNotEmpty()) ids.any { it in tvdbIds }
            else ids.isEmpty() && season to episode in episodes
        val records = state.completed[contentId].orEmpty()
        val revoked = records.filterValues { removed(it.season, it.episode, it.contentIds) }
        val remaining = records.toMutableMap()
        revoked.forEach { (key, done) ->
            val keptIds = done.watchedIds - tvdbIds
            if (tvdbIds.isNotEmpty() && keptIds.isNotEmpty()) remaining[key] = done.copy(watchedIds = keptIds)
            else remaining.remove(key)
        }
        val member = state.members[contentId]
        val p = member?.progress
        val reset = p != null && removed(p.season, p.episode, p.tvdbContentIds)
        // A provider may use the series videoId for every seed. Revoke episode identities,
        // not that shared videoId, and cover stale replays absent from our compact evidence.
        val removedKeys = if (tvdbIds.isNotEmpty()) tvdbIds.map { tvdbRemovalKey(contentId, it) }
            else episodes.map { (season, episode) -> episodeHistoryKey(contentId, season, episode) }
        val ignored = state.ignoredProgress + revoked.keys.associateWith { at } +
            removedKeys.associateWith { at } + if (reset) mapOf(p!!.historyKey() to at) else emptyMap()
        // The revoked seed's activity is not a watermark for the remaining history. Let
        // the existing committed seed flow and normal resolver rebuild this member (including
        // selected TVDB order/furthest rules). No remaining seeds means no card, as in 9.7.
        // Do not install a whole-show removal tombstone: older episodes remain valid support.
        val members = if (reset) state.members - contentId else state.members
        return invalidate(state.copy(ignoredProgress = ignored,
            completed = state.completed + (contentId to remaining), members = members), setOf(contentId))
    }

    private fun CwProjection.changed(next: Map<String, CwMember>, hidden: Map<String, Long> = removals,
        evidence: Map<String, Map<String, CwCompletion>> = completed) =
        if (members == next && removals == hidden && completed == evidence) this
        else copy(revision = revision + 1, members = next, removals = hidden, completed = evidence)

    private fun matches(next: CachedNextUpItem, done: CwCompletion): Boolean {
        val nextIds = next.tvdbContext?.episodeIds?.toSet() ?: setOfNotNull(next.tvdbEpisodeId)
        if (nextIds.isNotEmpty() && done.contentIds.isNotEmpty()) return nextIds.any { it in done.watchedIds }
        return next.videoId == done.videoId || (nextIds.isEmpty() && done.contentIds.isEmpty() &&
            next.season == done.season && next.episode == done.episode)
    }

    private fun sameEpisode(next: CachedNextUpItem, p: WatchProgress): Boolean {
        val nextIds = next.tvdbContext?.episodeIds?.toSet() ?: setOfNotNull(next.tvdbEpisodeId)
        if (nextIds.isNotEmpty() && p.tvdbContentIds.isNotEmpty()) return nextIds.any { it in p.tvdbWatchedIds }
        return next.videoId == p.videoId || (next.season == p.season && next.episode == p.episode)
    }
}

private fun episodeHistoryKey(contentId: String, season: Int, episode: Int) = "$contentId|episode:$season:$episode|"
private fun tvdbRemovalKey(contentId: String, id: Long) = "$contentId|tvdb:$id|"
private fun WatchProgress.historyKey() = if (tvdbContentIds.isEmpty() && season != null && episode != null)
    episodeHistoryKey(contentId, season, episode)
else legacyHistoryKey()
private fun WatchProgress.legacyHistoryKey() = "$contentId|$videoId|${tvdbContentIds.sorted().joinToString(",")}"

internal fun WatchProgress.cached(old: CachedInProgressItem? = null) = CachedInProgressItem(
    contentId, contentType, name, poster ?: old?.poster, backdrop ?: old?.backdrop, logo ?: old?.logo,
    videoId, season, episode, episodeTitle, position, duration, lastWatched, progressPercent,
    episodeThumbnail = old?.episodeThumbnail, episodeDescription = old?.episodeDescription,
    episodeImdbRating = old?.episodeImdbRating, genres = old?.genres ?: emptyList(),
    releaseInfo = old?.releaseInfo, contentLanguage = old?.contentLanguage,
    ratingVisible = old?.ratingVisible ?: true, tvdbContext = tvdbContext)

internal fun WatchProgress.seriesChoice() = CachedNextUpItem(
    contentId, contentType, name, poster, backdrop, logo, contentId, 0, 0, null,
    thumbnail = null, lastWatched = lastWatched, sortTimestamp = lastWatched,
    seedSeason = season, seedEpisode = episode, latestSeriesActivity = latestSeriesActivity,
    chooseEpisode = true)
