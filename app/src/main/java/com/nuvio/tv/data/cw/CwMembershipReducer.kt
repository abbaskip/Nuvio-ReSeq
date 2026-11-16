package com.nuvio.tv.data.cw

import android.util.Log
import com.nuvio.tv.data.tvdb.isNativeTrackerPause
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.WatchProgress

private const val CW_DECISION_TAG = "CwDecision"

/** Namespace + publication lease. A metadata task cannot publish across a source/order switch. */
data class CwLease(val profileId: Int, val source: String, val epoch: Long, val revision: Long)

data class CwMember(
    val progress: WatchProgress? = null,
    val inProgress: CachedInProgressItem? = null,
    val nextUp: CachedNextUpItem? = null,
    val changedAtRevision: Long = 0,
    // A furthest-episode seed can predate another completion. Persist its history
    // timestamp separately from presentation recency and the episode's own watch time.
    val latestCompletionAt: Long? = null
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

    /** The seed and playback lists describe one snapshot. A transient competitor between
     * the two reductions must not invalidate an offer for the same final history. */
    fun refresh(state: CwProjection, seeds: List<WatchProgress>, items: List<WatchProgress>,
        recheckOrderedPause: (String) -> Unit = {}): CwProjection {
        val updated = progress(progress(state, seeds, seeds = true), items)
        if (updated === state) return state
        val members = updated.members.mapValues { (id, member) ->
            val old = state.members[id]
            if (old != null && old.progress == member.progress && old.latestCompletionAt == member.latestCompletionAt &&
                state.completed[id] == updated.completed[id]) old else {
                // Additional history and playback rows can arrive in different emissions.
                // A replay of the same native pause still has its verified selected-order target.
                val ordered = if (old == null) member else restoreOrderedPause(updated, old, member)
                if (ordered !== member) recheckOrderedPause(id)
                ordered.copy(changedAtRevision = state.revision + 1)
            }
        }
        return state.changed(members, updated.removals, updated.completed)
    }

    fun progress(state: CwProjection, updates: List<WatchProgress>, seeds: Boolean = false): CwProjection {
        val members = state.members.toMutableMap()
        val removals = state.removals.toMutableMap()
        val completed = state.completed.toMutableMap()
        updates.sortedBy { it.lastWatched }.forEach { p ->
            if (p.watchEventKeys().any(state.ignoredProgress::containsKey)) return@forEach
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
            // A duplicate tracker notification cannot undo the verified presentation of this same pause.
            if (!seeds && old?.progress == p && (old.inProgress?.tvdbContext != null || old.nextUp?.tvdbContext != null) && p.tvdbContext == null) return@forEach
            // Metadata aliases and artwork can differ between the history and playback feeds.
            // They describe the same watch and cannot invalidate an in-flight episode lookup.
            val priorProgress = old?.progress
            // The bulk watched list can arrive before the recent history endpoint on startup.
            // It has native coordinates only. The detailed replay of that same watch carries
            // verified IDs and may have different addon coordinates. Keep that richer record.
            if (seeds && priorProgress != null && priorProgress.isDetailedHistoryFor(p) &&
                old.latestCompletionAt == p.completionActivityAt()) return@forEach
            if (priorProgress != null && priorProgress.lastWatched == p.lastWatched &&
                priorProgress.historyKey() == p.historyKey() && priorProgress.tvdbContext == p.tvdbContext &&
                priorProgress.progressPercentage == p.progressPercentage && priorProgress.isCompleted() == p.isCompleted() &&
                priorProgress.tvdbWatchedIds == p.tvdbWatchedIds &&
                (!seeds || old.latestCompletionAt == p.completionActivityAt())) return@forEach
            // A retained series placeholder (including one saved by local.1 after unwatch)
            // has presentation recency, not committed progress that can reject older history.
            val priorActivity = old?.progress?.lastWatched ?: old?.inProgress?.lastWatched ?:
                old?.nextUp?.takeUnless { it.chooseEpisode }?.lastWatched
            val completedSeed = p.isCompleted() || seeds
            val seedActivity = if (completedSeed) p.completionActivityAt() else p.lastWatched
            val comparingResume = old?.inProgress != null || !completedSeed
            val incomingActivity = if (comparingResume) seedActivity else p.lastWatched
            val prior = if (!completedSeed) {
                old?.latestCompletionAt ?: completed[p.contentId]?.values?.maxOfOrNull { it.at }
            } else null
            val threshold = maxOf(priorActivity ?: Long.MIN_VALUE, prior ?: Long.MIN_VALUE)
            if (Log.isLoggable(CW_DECISION_TAG, Log.DEBUG) && (old?.inProgress != null || !completedSeed)) {
                val competitor = old?.progress
                val id = p.contentId.takeIf { it.matches(Regex("tt[0-9]+|(?:tmdb|trakt|tvdb):[0-9]+")) } ?: "other"
                val rejected = incomingActivity < threshold ||
                    (competitor?.isCompleted() == true && competitor.lastWatched == p.lastWatched && !completedSeed)
                Log.d(CW_DECISION_TAG, "show=$id candidate=${if (completedSeed) "completion" else "progress"} " +
                    "source=${p.decisionSource()} episode=${p.season}x${p.episode} percent=${p.progressPercentage * 100} " +
                    "watchedAt=${p.lastWatched} activity=$incomingActivity prior=${competitor?.decisionSource()} " +
                    "priorEpisode=${competitor?.season}x${competitor?.episode} threshold=$threshold " +
                    "decision=${if (rejected) "keep-prior" else "accept-activity"}")
            }
            if (incomingActivity < threshold) return@forEach
            // An identical remote replay cannot undo a locally committed completion.
            if (old?.progress?.isCompleted() == true && p.lastWatched == old.progress.lastWatched &&
                !p.isCompleted()) return@forEach
            val series = p.contentType in setOf("series", "tv", "anime")
            val member = when {
                p.isCompleted() && !series -> null
                p.isCompleted() || seeds -> {
                    // An offer was resolved from a particular seed. Completing a different
                    // episode invalidates it even when the offer is not that new completion.
                    val sameSeed = old?.progress?.sameEpisodeSeed(p) == true
                    val next = old?.nextUp?.takeIf { sameSeed && (it.chooseEpisode ||
                        completed[p.contentId].orEmpty().values.none { done -> matches(it, done) }) }
                    CwMember(p, nextUp = next?.copy(lastWatched = maxOf(next.lastWatched, p.lastWatched))
                        ?: p.seriesChoice(), changedAtRevision = state.revision + 1,
                        latestCompletionAt = if (seeds) seedActivity else
                            maxOf(seedActivity, old?.latestCompletionAt ?: Long.MIN_VALUE))
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

    /** Only positive dated history can retire a movie. Keep its cutoff for older playback replays. */
    fun reconcileWatchedMovies(state: CwProjection, history: Map<String, Long?>): CwProjection {
        if (history.isEmpty()) return state
        val finished = buildMap {
            for ((id, member) in state.members) {
                val resume = member.inProgress ?: continue
                if (!resume.contentType.equals("movie", ignoreCase = true)) continue
                val at = history[id] ?: continue
                if (at > 0 && at >= resume.lastWatched) put(id, at)
            }
        }
        if (finished.isEmpty()) return state
        return state.changed(state.members - finished.keys, state.removals + finished)
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
            if (old.changedAtRevision > lease.revision) return@forEach
            if (candidate.chooseEpisode && old.nextUp?.chooseEpisode == false) return@forEach
            if (old.inProgress != null) {
                if (Log.isLoggable(CW_DECISION_TAG, Log.DEBUG)) {
                    Log.d(CW_DECISION_TAG,
                        "decision=keep-resume source=${old.progress?.decisionSource()} episode=${old.inProgress.season}x${old.inProgress.episode} " +
                            "watchedAt=${old.inProgress.lastWatched} next=${candidate.season}x${candidate.episode}")
                }
                return@forEach
            }
            val p = old.progress
            // Home may finish enriching an older history suggestion after the pause resolver
            // has selected a different part. Only the resolver can change that episode.
            val offer = old.nextUp
            if (p?.isNativeTrackerPause() == true && !p.isCompleted() &&
                (offer?.tvdbContext != null || candidate.tvdbContext != null) &&
                (candidate.lastWatched != p.lastWatched || candidate.videoId != offer?.videoId ||
                    candidate.tvdbContext != offer?.tvdbContext)) return@forEach
            if (p?.isCompleted() == true && !candidate.chooseEpisode && sameEpisode(candidate, p)) return@forEach
            if (!candidate.chooseEpisode && state.completed[candidate.contentId].orEmpty().values.any { matches(candidate, it) }) return@forEach
            members[candidate.contentId] = old.copy(nextUp = candidate)
        }
        return state.changed(members)
    }

    /** A tracker pause can map to the beginning of the next segment in the selected order.
     * Reuse that interpretation only for the identical playback event until it is rechecked. */
    fun restoreOrderedPause(state: CwProjection, old: CwMember, rebuilt: CwMember): CwMember {
        val raw = rebuilt.progress ?: return rebuilt
        val prior = old.progress ?: return rebuilt
        if (rebuilt.inProgress == null || !raw.isNativeTrackerPause() || prior.source != raw.source ||
            prior.videoId != raw.videoId || prior.tvdbEpisodeId != raw.tvdbEpisodeId ||
            prior.traktEpisodeId != raw.traktEpisodeId || prior.lastWatched != raw.lastWatched ||
            prior.progressPercentage != raw.progressPercentage || prior.trackingProviderId != raw.trackingProviderId) return rebuilt
        val offer = old.nextUp
        if (offer?.tvdbContext != null && !offer.chooseEpisode && offer.lastWatched == raw.lastWatched &&
            state.completed[raw.contentId].orEmpty().values.none { matches(offer, it) }) {
            return rebuilt.copy(inProgress = null, nextUp = offer)
        }
        val resume = old.inProgress
        if (resume?.tvdbContext != null && !resume.chooseEpisode) return rebuilt.copy(inProgress = resume)
        return rebuilt
    }

    fun remove(state: CwProjection, lease: CwLease, proof: CwRemovalProof): CwProjection {
        if (!accepts(state, lease)) return state
        val ids = proof.contentIds.filter { id -> proof.reason == CwRemovalReason.USER_REMOVAL ||
            (state.members[id]?.changedAtRevision ?: 0) <= lease.revision }.toSet()
        return state.changed(state.members - ids, state.removals + ids.associateWith { proof.at })
    }

    fun removeWatchEvent(state: CwProjection, event: WatchProgress): CwProjection {
        val id = event.contentId
        val keys = event.watchEventKeys()
        if (keys.isEmpty()) return state
        val member = state.members[id]
        val removed = member?.progress?.watchEventKeys().orEmpty().any(keys::contains)
        val records = state.completed[id].orEmpty().filterValues { record ->
            record.at != event.lastWatched || event.source == WatchProgress.SOURCE_TRAKT_PLAYBACK ||
                !(record.contentIds.any(event.tvdbContentIds::contains) ||
                    event.traktHistoryEpisode?.let { it.season == record.season && it.episode == record.episode } == true)
        }
        return state.copy(epoch = state.epoch + 1, revision = state.revision + 1,
            members = if (removed) state.members - id else state.members,
            completed = state.completed + (id to records), ignoredProgress = state.ignoredProgress + keys.associateWith { event.lastWatched })
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
            if (ids.isNotEmpty()) ids.any { it in tvdbIds } else season to episode in episodes
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
        val removedKeys = tvdbIds.map { tvdbRemovalKey(contentId, it) } +
            episodes.map { (season, episode) -> episodeHistoryKey(contentId, season, episode) }
        val ignored = state.ignoredProgress + revoked.keys.associateWith { at } +
            removedKeys.associateWith { at } + if (reset) mapOf(p!!.historyKey() to at) else emptyMap()
        // The revoked seed's activity is not a watermark for the remaining history. Let
        // the existing committed seed flow and normal resolver rebuild this member (including
        // selected TVDB order/furthest rules). No remaining seeds means no card, as in 9.7.
        // Do not install a whole-show removal tombstone: older episodes remain valid support.
        val members = when {
            reset -> state.members - contentId
            tvdbIds.isNotEmpty() && p?.isNativeTrackerPause() == true && member.inProgress?.tvdbContext != null ->
                state.members + (contentId to member.copy(inProgress = member.inProgress.copy(chooseEpisode = true)))
            member?.latestCompletionAt != null -> state.members + (contentId to member.copy(latestCompletionAt = null))
            else -> state.members
        }
        return invalidate(state.copy(ignoredProgress = ignored,
            completed = state.completed + (contentId to remaining), members = members), setOf(contentId))
    }

    private fun CwProjection.changed(next: Map<String, CwMember>, hidden: Map<String, Long> = removals,
        evidence: Map<String, Map<String, CwCompletion>> = completed) =
        if (members == next && removals == hidden && completed == evidence) this
        else copy(revision = revision + 1, members = next, removals = hidden, completed = evidence)

    private fun matches(next: CachedNextUpItem, done: CwCompletion): Boolean {
        // Details can deliberately advance a rewatch to an episode watched in the past.
        // A later completion still invalidates it, as does a completion unseen by the resolver.
        if (next.offeredEpisodeWatchedAt?.let { done.at <= it && it < next.lastWatched } == true) return false
        val nextIds = next.tvdbContext?.episodeIds?.toSet() ?: setOfNotNull(next.tvdbEpisodeId)
        if (nextIds.isNotEmpty() && done.contentIds.isNotEmpty()) return nextIds.any { it in done.watchedIds }
        return (next.videoId != next.contentId && next.videoId == done.videoId) || (nextIds.isEmpty() && done.contentIds.isEmpty() &&
            next.season == done.season && next.episode == done.episode)
    }

    private fun sameEpisode(next: CachedNextUpItem, p: WatchProgress): Boolean {
        val nextIds = next.tvdbContext?.episodeIds?.toSet() ?: setOfNotNull(next.tvdbEpisodeId)
        if (nextIds.isNotEmpty() && p.tvdbContentIds.isNotEmpty()) return nextIds.any { it in p.tvdbWatchedIds }
        return (next.videoId != next.contentId && next.videoId == p.videoId) || (nextIds.isEmpty() && p.tvdbContentIds.isEmpty() && next.season == p.season && next.episode == p.episode)
    }
}

// Log only known source labels. Provider-controlled strings, titles and URLs never enter logcat.
private fun WatchProgress.decisionSource(): String = when (source) {
    WatchProgress.SOURCE_LOCAL, WatchProgress.SOURCE_TRAKT_PLAYBACK, WatchProgress.SOURCE_TRAKT_HISTORY,
    WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS, WatchProgress.SOURCE_SIMKL_PLAYBACK,
    WatchProgress.SOURCE_REMOTE_HISTORY, WatchProgress.SOURCE_REMOTE_PLAYBACK -> source
    else -> "other"
}

/** A tracker seed carries the latest remaining completion even when another episode is the starting point. */
internal fun WatchProgress.completionActivityAt(): Long = maxOf(lastWatched, latestSeriesActivity ?: lastWatched)

/** Artwork, titles and provider presentation timestamps can change without changing the watched episode. */
internal fun WatchProgress.sameEpisodeSeed(other: WatchProgress): Boolean =
    contentId == other.contentId && (
        isDetailedHistoryFor(other) || other.isDetailedHistoryFor(this) ||
        historyKey() == other.historyKey() ||
        (season != null && episode != null && season == other.season && episode == other.episode &&
            tvdbContentIds == other.tvdbContentIds && tvdbContext == other.tvdbContext))

private fun WatchProgress.isDetailedHistoryFor(seed: WatchProgress): Boolean =
    source == WatchProgress.SOURCE_TRAKT_HISTORY && seed.source == WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS &&
        lastWatched == seed.lastWatched && traktHistoryEpisode?.let {
            it.season == seed.season && it.episode == seed.episode
        } == true

private fun episodeHistoryKey(contentId: String, season: Int, episode: Int) = "$contentId|episode:$season:$episode|"
internal fun tvdbRemovalKey(contentId: String, id: Long) = "$contentId|tvdb:$id|"
private fun WatchProgress.watchEventKeys(): Set<String> {
    if (source == WatchProgress.SOURCE_TRAKT_PLAYBACK && traktPlaybackId != null)
        return setOf("$contentId|playback:$traktPlaybackId|$lastWatched")
    if (source != WatchProgress.SOURCE_TRAKT_HISTORY && source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS) return emptySet()
    return buildSet {
        traktEpisodeId?.let { add("$contentId|watch:$it|$lastWatched") }
        traktHistoryEpisode?.let { add("$contentId|watch:${it.season}:${it.episode}|$lastWatched") }
    }
}

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
