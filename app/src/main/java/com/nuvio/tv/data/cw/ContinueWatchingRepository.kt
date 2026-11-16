package com.nuvio.tv.data.cw

import com.nuvio.tv.data.tvdb.isNativeTrackerPause
import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** App-scoped lifetime, profile/source-scoped records. Home is an enrichment producer only. */
@Singleton
class ContinueWatchingRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: ProfileManager,
    private val settings: TraktSettingsDataStore,
    private val progressProvider: Provider<WatchProgressRepository>,
    private val trackingProviders: Provider<com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry>? = null,
    private val tvdbOrders: com.nuvio.tv.data.tvdb.TvdbOrderStore? = null,
    private val tvdbTracking: Provider<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>? = null
) {
    private val store = CwProjectionStore(File(context.filesDir, "cw_enrichment"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e("ContinueWatching", "Projection operation failed", error)
    })
    private val started = AtomicBoolean()
    private var current: CwProjection? = null
    // Disk supplies the first frame. Rebuild each restored series as its tracker data arrives.
    // Accessed under mutex, scoped to the currently loaded profile and source.
    private val restoredSeries = mutableSetOf<String>()
    private var epoch = 0L
    private val _projection = MutableStateFlow<CwProjection?>(null)
    val projection: StateFlow<CwProjection?> = _projection.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            observeProjection().map { it.profileId to it.source }.distinctUntilChanged()
                .collectLatest {
                    val lease = snapshot().lease
                    val progress = progressProvider.get()
                    progress.observeRemoteProgressLoaded().distinctUntilChanged().collectLatest { loaded ->
                        // On readiness, subscribe to the current rows again. The loaded flag
                        // can overtake an older list still being processed by a collector.
                        combine(progress.allProgress, progress.observeNextUpSeeds(), movieCompletionTimes(lease)) { items, seeds, movies ->
                            val active = snapshot().lease
                            if (active.profileId == lease.profileId && active.source == lease.source)
                                publishTrackerProgress(active, items, seeds, progress, movies, loaded)
                        }.collect()
                    }
                }
        }
    }

    /** Loading the durable projection is independent of progress, Home visibility and enrichment. */
    fun observeProjection(): Flow<CwProjection> = scopedCwProjections(
        profiles.resolvedActiveProfileId,
        { profile -> settings.resolvedWatchProgressSource(profile).map { it.name } },
        { profile, source -> withContext(Dispatchers.IO) { mutex.withLock { load(profile, source) } } },
        projection
    )

    suspend fun snapshot(): CwProjection = withContext(Dispatchers.IO) {
        val profile = profiles.resolvedActiveProfileId.first()
        val source = settings.getWatchProgressSource(profile).name
        mutex.withLock { load(profile, source) }
    }

    private suspend fun load(profile: Int, source: String): CwProjection {
        val old = current
        if (old?.profileId == profile && old.source == source) return old
        val saved = store.read(profile, source)
        val loaded = saved.copy(members = saved.members.mapValues { (_, member) ->
            val pause = member.progress
            val offer = member.nextUp
            if (pause != null && pause.isNativeTrackerPause() && !pause.isCompleted() &&
                offer?.tvdbContext != null && offer.lastWatched != pause.lastWatched) {
                // Older releases could save an offer from history alongside a newer tracker pause.
                member.copy(inProgress = pause.cached(), nextUp = null)
            } else member
        })
        restoredSeries.clear()
        restoredSeries.addAll(loaded.members.filterValues { member ->
            (member.inProgress?.contentType ?: member.nextUp?.contentType) in setOf("series", "tv", "anime")
        }.keys)
        epoch = maxOf(epoch, old?.epoch ?: 0, loaded.epoch) + 1
        return approveIdentity(loaded.copy(epoch = epoch)).also { current = it; _projection.value = it }
    }

    private suspend fun mutate(lease: CwLease, afterCommit: () -> Unit = {},
        transform: (CwProjection) -> CwProjection): CwProjection =
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                val state = current ?: load(lease.profileId, lease.source)
                if (profiles.activeProfileId.value != lease.profileId ||
                    settings.getWatchProgressSource(lease.profileId).name != lease.source ||
                    !CwMembershipReducer.accepts(state, lease)) return@withLock state
                val next = approveIdentity(transform(state))
                if (next != state) {
                    store.write(next)
                    current = next
                    _projection.value = next // Publish only after durable commit.
                }
                afterCommit()
                next
            }
        }

    suspend fun enrich(lease: CwLease, inProgress: List<CachedInProgressItem> = emptyList(),
        nextUp: List<CachedNextUpItem> = emptyList()): CwProjection =
        mutate(lease) { CwMembershipReducer.enrich(it, lease, inProgress, nextUp) }

    /** Called after the existing progress store commits; never infers completion from lifecycle. */
    suspend fun committedProgress(profileId: Int, updates: List<WatchProgress>) {
        val source = settings.getWatchProgressSource(profileId).name
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                val active = current?.takeIf { it.profileId == profileId && it.source == source }
                val state = active ?: store.read(profileId, source)
                val next = approveIdentity(CwMembershipReducer.progress(state, updates))
                if (next != state) store.write(next)
                if (active != null) {
                    updates.forEach { restoredSeries.remove(it.contentId) }
                    current = next
                    _projection.value = next
                }
            }
        }
    }

    suspend fun remove(contentIds: Set<String>, profileId: Int = profiles.activeProfileId.value, nextUpOnly: Boolean = false) {
        val state = snapshot()
        if (state.profileId != profileId) return
        mutate(state.lease) { current ->
            val ids = if (nextUpOnly) contentIds.filterTo(mutableSetOf()) { current.members[it]?.inProgress == null } else contentIds
            CwMembershipReducer.remove(current, state.lease,
                CwRemovalProof(CwRemovalReason.USER_REMOVAL, ids, System.currentTimeMillis()))
        }
    }

    suspend fun historyRemoved(profileId: Int, contentId: String, episodes: Set<Pair<Int, Int>>, tvdbIds: Set<Long> = emptySet()) {
        var state = snapshot()
        val source = state.source
        val removedAt = System.currentTimeMillis()
        // The tracker may have emitted its remaining history before its write returned.
        // Read the current values even when there will be no further flow emission.
        val progress = progressProvider.get()
        while (state.profileId == profileId && state.source == source) {
            val seeds = progress.observeNextUpSeeds().first().filter { it.contentId == contentId }
            val items = progress.allProgress.first().filter { it.contentId == contentId }
            var committed = false
            val updated = mutate(state.lease, afterCommit = { restoredSeries.remove(contentId) }) { current ->
                val revoked = CwMembershipReducer.historyRemoved(current, contentId, episodes, removedAt, tvdbIds)
                committed = true
                reduceProgress(revoked, items, seeds, progress, emptyMap())
            }
            if (committed) {
                updated.members[contentId]?.let { resolvePendingPauses(updated, listOf(it)) }
                return
            }
            // Another history edit or order change committed while the history was being read.
            state = snapshot()
        }
    }

    suspend fun invalidate(profileId: Int, contentIds: Set<String>) {
        val state = snapshot()
        if (state.profileId != profileId) return
        mutate(state.lease) { CwMembershipReducer.invalidate(it, contentIds) }
    }

    /** A successful Nuvio sync supplies explicit deletion evidence, not a metadata list.
     * An authenticated tracker without a complete membership generation remains unknown;
     * it cannot be treated as absent merely because the local sync deleted an entry. */
    suspend fun pruneAfterSync(lease: CwLease, supportedContentIds: Set<String>) {
        if (lease.source != "NUVIO_SYNC") return
        if (trackingProviders?.get()?.providers()?.any { it.isAuthenticated.first() } == true) return
        mutate(lease) { state -> CwMembershipReducer.remove(state, lease,
            CwRemovalProof(CwRemovalReason.AUTHORITATIVE_ABSENCE,
                state.members.keys - supportedContentIds, System.currentTimeMillis())) }
    }

    /** All return/refresh triggers converge here; a missing player result changes no progress. */
    suspend fun refreshProjection() {
        val state = snapshot()
        val progress = progressProvider.get()
        val loaded = progress.observeRemoteProgressLoaded().first()
        val seeds = progress.observeNextUpSeeds().first()
        val items = progress.allProgress.first()
        val movies = movieCompletionTimes(state.lease).first()
        publishTrackerProgress(state.lease, items, seeds, progress, movies, loaded)
    }

    private suspend fun publishTrackerProgress(lease: CwLease, items: List<WatchProgress>,
        seeds: List<WatchProgress>, progress: WatchProgressRepository, movies: Map<String, Long?>, loaded: Boolean) {
        // Publish membership immediately. Resolve only the winning pauses for ordered shows,
        // outside the store lock, so a slow catalogue cannot delay playback or unrelated cards.
        var replace = emptySet<String>()
        val recheckPauses = mutableSetOf<String>()
        val state = mutate(lease, afterCommit = { restoredSeries.removeAll(replace) }) { current ->
            val restoringTracker = restoredSeries.isNotEmpty() && progress.hasActiveTrackingProgressProvider()
            if (restoringTracker && !loaded) {
                // Startup may emit yesterday's provider cache before the network result. It cannot
                // invalidate a resolved episode. A local playback write may have reached its own
                // store immediately before process death, without reaching the projection.
                val applicable = items.filter { row ->
                    row.contentId !in restoredSeries || (row.source == WatchProgress.SOURCE_LOCAL &&
                        row.trackingProviderId == null && row.lastWatched >
                        (current.members[row.contentId]?.progress?.lastWatched ?: Long.MAX_VALUE))
                }
                replace = applicable.mapNotNullTo(mutableSetOf()) { it.contentId.takeIf(restoredSeries::contains) }
                return@mutate reduceProgress(current, applicable,
                    seeds.filterNot { it.contentId in restoredSeries }, progress, movies)
            }
            if (restoringTracker) {
                val playback = items.asSequence().filter { it.isCompleted() || it.position > 0 || (it.progressPercent ?: 0f) > 0 }
                replace = (seeds.asSequence() + playback).filter {
                    it.source != WatchProgress.SOURCE_LOCAL || it.trackingProviderId != null
                }.map { it.contentId }.filter(restoredSeries::contains).toSet()
            }
            if (replace.isEmpty()) return@mutate reduceProgress(current, items, seeds, progress, movies, recheckPauses::add)
            val base = current.copy(
                members = current.members - replace, completed = current.completed - replace,
                epoch = current.epoch + 1, revision = current.revision + 1)
            val rebuilt = reduceProgress(base, items, seeds, progress, movies, recheckPauses::add)
            // Keep a verified card on screen while the resolver recalculates the same seed.
            // A different winning seed must acquire its own episode and progress.
            val resumes = ArrayList<CachedInProgressItem>()
            val offers = ArrayList<CachedNextUpItem>()
            val members = rebuilt.members.toMutableMap()
            for (id in replace) {
                val old = current.members[id] ?: continue
                val member = rebuilt.members[id] ?: continue
                val winner = member.progress ?: continue
                val ordered = CwMembershipReducer.restoreOrderedPause(rebuilt, old, member)
                if (ordered !== member) {
                    members[id] = ordered
                    recheckPauses.add(id)
                    continue
                }
                if (old.progress == winner) old.inProgress?.takeUnless { it.chooseEpisode }?.let(resumes::add)
                val offer = old.nextUp?.takeUnless { it.chooseEpisode } ?: continue
                if (old.progress?.sameEpisodeSeed(winner) == true) {
                    offers += offer.copy(lastWatched = maxOf(offer.lastWatched, winner.lastWatched))
                }
            }
            CwMembershipReducer.enrich(rebuilt.copy(members = members), rebuilt.lease, inProgress = resumes, nextUp = offers)
        }
        if (state.profileId != lease.profileId || state.source != lease.source) return
        resolvePendingPauses(state, state.members.values, recheckPauses)
    }

    private suspend fun resolvePendingPauses(state: CwProjection, members: Collection<CwMember>, recheck: Set<String> = emptySet()) {
        val lease = state.lease
        val resolver = tvdbTracking ?: return
        for (member in members) {
            val raw = member.progress ?: continue
            val previouslyResolved = raw.contentId in recheck
            if ((!previouslyResolved && member.inProgress?.chooseEpisode != true && member.nextUp?.chooseEpisode != true) || !raw.isNativeTrackerPause()) continue
            val resolved = try {
                resolver.get().continueWatchingResume(lease.profileId, raw, state.ignoredProgress) {
                    progressProvider.get().getAllEpisodeProgress(raw.contentId, lease.profileId).first { it.isNotEmpty() }.values
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ContinueWatching", "Episode catalogue unavailable (${e.javaClass.simpleName})")
                null
            } ?: continue
            mutate(lease) { current ->
                val latest = current.members[raw.contentId]
                if (latest?.progress != raw || (latest.inProgress?.chooseEpisode != true && latest.nextUp?.chooseEpisode != true &&
                    (!previouslyResolved || latest != member))) current
                else current.copy(revision = current.revision + 1, members = current.members +
                    (raw.contentId to latest.copy(
                        inProgress = resolved.takeIf { it.progressPercentage > 0 }?.cached(latest.inProgress),
                        nextUp = resolved.takeIf { it.progressPercentage == 0f }?.let {
                            raw.seriesChoice().copy(videoId = it.videoId, season = it.season!!, episode = it.episode!!,
                                episodeTitle = it.episodeTitle, tvdbEpisodeId = it.tvdbEpisodeId, tvdbContext = it.tvdbContext, chooseEpisode = false)
                        },
                        changedAtRevision = current.revision + 1)))
            }
        }
    }

    private fun movieCompletionTimes(lease: CwLease): Flow<Map<String, Long?>> {
        val provider = trackingProviders?.get()?.providers()?.firstOrNull { it.providerId.name == lease.source }
            ?: return flowOf(emptyMap())
        return combine(provider.isAuthenticated, provider.movieCompletionTimes(lease.profileId)) { connected, history ->
            if (connected) history else emptyMap()
        }
    }

    private fun reduceProgress(state: CwProjection, items: List<WatchProgress>, seeds: List<WatchProgress>,
        progress: WatchProgressRepository, movies: Map<String, Long?>, recheckOrderedPause: (String) -> Unit = {}): CwProjection {
        val updated = CwMembershipReducer.refresh(state, seeds, items, recheckOrderedPause)
        val next = CwMembershipReducer.reconcileWatchedMovies(updated, movies)
        val hidden = next.members.keys.filterTo(mutableSetOf()) { progress.isDroppedShow(it) }
        return if (hidden == next.hiddenByProvider) next else next.copy(hiddenByProvider = hidden, revision = next.revision + 1)
    }

    private suspend fun approveIdentity(state: CwProjection): CwProjection {
        val orders = tvdbOrders?.exportSelections(state.profileId) ?: return state
        fun unresolved(id: String, context: com.nuvio.tv.domain.model.TvdbPlaybackContext?): Boolean {
            val series = orders["alias:$id"]?.toLongOrNull()
            val order = series?.let { orders["order:$it"] }
            return if (order == null) context != null else context == null || context.seriesId != series || context.orderType != order
        }
        val members = state.members.mapValues { (id, member) ->
            member.copy(
                inProgress = member.inProgress?.let { if (unresolved(id, it.tvdbContext)) it.copy(chooseEpisode = true) else it },
                nextUp = member.nextUp?.let { if (unresolved(id, it.tvdbContext)) it.copy(chooseEpisode = true) else it })
        }
        return if (members == state.members) state else state.copy(members = members, revision = state.revision + 1)
    }
}
