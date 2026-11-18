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
    private val tvdbTracking: Provider<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>? = null,
    private val deviceEvents: Provider<com.nuvio.tv.data.local.DeviceWatchEventStore>? = null
) {
    private val store = CwProjectionStore(File(context.filesDir, "cw_enrichment"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e("ContinueWatching", "Projection operation failed", error)
    })
    private val started = AtomicBoolean()
    private val refreshMutex = Mutex()
    private val publication = CwCardPublication()
    private val unresolvedPauses = mutableMapOf<String, WatchProgress>()
    data class SettledProgress(val lease: CwLease, val items: List<WatchProgress>, val seeds: List<WatchProgress>,
        val watched: List<com.nuvio.tv.domain.model.WatchedItem>)
    private val _settledProgress = MutableStateFlow<SettledProgress?>(null)
    val settledProgress: Flow<SettledProgress> = _settledProgress.filterNotNull()
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
                    refreshSelected(com.nuvio.tv.core.tracking.TrackingRefreshIntent.INVALIDATED)
                }
        }
    }

    /** Loading the durable projection is independent of progress, Home visibility and enrichment. */
    fun observeProjection(): Flow<CwProjection> = scopedCwProjections(
        profiles.resolvedActiveProfileId,
        { profile -> settings.resolvedWatchProgressSource(profile).map { it.name } },
        { profile, source -> withContext(Dispatchers.IO) { mutex.withLock { load(profile, source).forPresentation() } } },
        projection
    )

    suspend fun snapshot(): CwProjection = withContext(Dispatchers.IO) {
        val profile = profiles.resolvedActiveProfileId.first()
        val source = settings.getWatchProgressSource(profile).name
        mutex.withLock { load(profile, source) }
    }

    internal suspend fun nextUpLease(snapshot: CwProjection, furthest: Boolean): CwLease? = mutex.withLock {
        val state = current ?: return@withLock null
        if (!CwMembershipReducer.accepts(state, snapshot.lease)) return@withLock null
        publication.beginNextUp(snapshot.lease, furthest)
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
        publication.reset()
        unresolvedPauses.clear()
        _settledProgress.value = null
        val working = approveIdentity(loaded.copy(epoch = epoch, presentedMembers = saved.presentedMembers ?: saved.members))
        current = working
        _projection.value = working.forPresentation()
        return working
    }

    private suspend fun mutate(lease: CwLease, afterCommit: () -> Unit = {}, background: Boolean = false, settledIds: Set<String>? = null,
        transform: (CwProjection) -> CwProjection): CwProjection =
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                val state = current ?: load(lease.profileId, lease.source)
                if (profiles.activeProfileId.value != lease.profileId ||
                    settings.getWatchProgressSource(lease.profileId).name != lease.source ||
                    !CwMembershipReducer.accepts(state, lease) || !publication.isCurrent(lease)) return@withLock state
                val approved = approveIdentity(transform(state))
                val readyIds = settledIds?.filterTo(mutableSetOf()) { id ->
                    unresolvedPauses[id]?.let { it != approved.members[id]?.progress } ?: true
                }
                val transformed = if (readyIds == null) approved else publication.settle(approved, lease, readyIds)
                val next = if (background) transformed else transformed.presentChangesFrom(state)
                if (next != state) {
                    store.write(next)
                    current = next
                    _projection.value = next.forPresentation() // Publish only after durable commit.
                }
                if (readyIds != null) publication.committed(next, lease, readyIds)
                afterCommit()
                next
            }
        }

    suspend fun enrich(lease: CwLease, inProgress: List<CachedInProgressItem> = emptyList(),
        nextUp: List<CachedNextUpItem> = emptyList(), settled: Boolean = true): CwProjection =
        mutate(lease, background = true, settledIds = if (settled)
            (inProgress.map { it.contentId } + nextUp.map { it.contentId }).toSet() else null) {
            CwMembershipReducer.enrich(it, lease, inProgress, nextUp)
        }

    /** Called after the existing progress store commits; never infers completion from lifecycle. */
    suspend fun committedProgress(profileId: Int, updates: List<WatchProgress>) {
        val source = settings.getWatchProgressSource(profileId).name
        var newlyCompleted = emptySet<String>()
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                val active = current?.takeIf { it.profileId == profileId && it.source == source }
                val state = active ?: store.read(profileId, source)
                newlyCompleted = updates.filter { row ->
                    val previous = state.members[row.contentId]?.progress
                    row.season != null && row.episode != null && row.isCompleted() &&
                        (previous?.isCompleted() != true || previous.videoId != row.videoId ||
                            previous.tvdbContentIds != row.tvdbContentIds || previous.deviceWatchEventId != row.deviceWatchEventId)
                }.mapTo(mutableSetOf()) { it.contentId }
                val next = approveIdentity(CwMembershipReducer.progress(state, updates)).presentChangesFrom(state)
                if (next != state) store.write(next)
                if (active != null) {
                    updates.forEach { restoredSeries.remove(it.contentId) }
                    current = next
                    _projection.value = next.forPresentation()
                }
            }
        }
        // Resolve committed receipts immediately, independently of returning to Home.
        // Scrobble delivery never waits for catalogue/network enrichment.
        if (started.get() && newlyCompleted.isNotEmpty()) scope.launch { publishCommittedInputs(profileId, newlyCompleted) }
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
            val updated = mutate(state.lease, afterCommit = { restoredSeries.remove(contentId) }, background = true) { current ->
                val revoked = CwMembershipReducer.historyRemoved(current, contentId, episodes, removedAt, tvdbIds)
                committed = true
                reduceProgress(revoked, items, seeds, progress, emptyMap())
            }
            if (committed) {
                updated.members[contentId]?.let { resolvePendingPauses(updated, listOf(it)) }
                publishCommittedInputs(profileId, setOf(contentId))
                return
            }
            // Another history edit or order change committed while the history was being read.
            state = snapshot()
        }
    }

    suspend fun lastWatchRemoved(profileId: Int, event: WatchProgress) {
        val state = snapshot()
        if (state.profileId != profileId || state.source != "TRAKT") return
        mutate(state.lease) { CwMembershipReducer.removeWatchEvent(it, event) }
        publishCommittedInputs(profileId, setOf(event.contentId))
    }

    suspend fun deviceWatchRemoved(profileId: Int, event: com.nuvio.tv.data.local.DeviceWatchEvent) {
        val state = snapshot()
        if (state.profileId != profileId) return
        mutate(state.lease, afterCommit = { restoredSeries.remove(event.progress.contentId) }, background = true) {
            CwMembershipReducer.removeDeviceWatchEvent(it, event)
        }
        publishCommittedInputs(profileId, setOf(event.progress.contentId))
        hideFinishedSeries(profileId, event.progress.contentId, before = event.startedAt)
    }

    suspend fun partialWatchRemoved(profileId: Int, event: WatchProgress) {
        val state = snapshot()
        if (state.profileId != profileId) return
        mutate(state.lease, afterCommit = { restoredSeries.remove(event.contentId) }) {
            CwMembershipReducer.removePause(it, event)
        }
        publishCommittedInputs(profileId, setOf(event.contentId))
        hideFinishedSeries(profileId, event.contentId)
    }

    private suspend fun hideFinishedSeries(profileId: Int, contentId: String, before: Long = Long.MAX_VALUE) {
        val refreshed = snapshot()
        if (refreshed.profileId != profileId) return
        val member = refreshed.members[contentId] ?: return
        if (member.progress?.isCompleted() != true || member.inProgress != null) return
        if ((member.progress.lastWatched) >= before) return
        val resolver = tvdbTracking?.get() ?: return
        val complete = withTimeoutOrNull(8_000) {
            val history = progressProvider.get().getAllEpisodeProgress(contentId, profileId).first { it.isNotEmpty() }
            resolver.isFullyWatched(profileId, contentId, history.values)
        } ?: false
        if (!complete) return
        val finished = mutate(refreshed.lease, background = true) { current ->
            if (current.members[contentId] != member) current else current.copy(
                revision = current.revision + 1, members = current.members + (contentId to
                    member.copy(inProgress = null, nextUp = null, changedAtRevision = current.revision + 1)))
        }
        mutate(finished.lease, background = true, settledIds = setOf(contentId)) { it }
    }

    suspend fun invalidate(profileId: Int, contentIds: Set<String>) {
        val state = snapshot()
        if (state.profileId != profileId) return
        mutate(state.lease) { CwMembershipReducer.invalidate(it, contentIds) }
    }

    /** A successful sync supplies deletion evidence for the selected Nuvio source. */
    suspend fun pruneAfterSync(lease: CwLease, supportedContentIds: Set<String>) {
        if (lease.source != "NUVIO_SYNC") return
        mutate(lease) { state -> CwMembershipReducer.remove(state, lease,
            CwRemovalProof(CwRemovalReason.AUTHORITATIVE_ABSENCE,
                state.members.keys - supportedContentIds, System.currentTimeMillis())) }
    }

    /** All return/refresh triggers converge here; a missing player result changes no progress. */
    suspend fun refreshSelected(intent: com.nuvio.tv.core.tracking.TrackingRefreshIntent): List<com.nuvio.tv.core.tracking.TrackingProgressRefreshFailure> = refreshMutex.withLock {
        val state = snapshot()
        deviceEvents?.get()?.reconcile(state.profileId)
        val provider = trackingProviders?.get()?.providers()?.firstOrNull { it.providerId.name == state.source }
        if (provider != null) {
            if (!provider.isAuthenticated.first()) return@withLock emptyList()
            try { provider.refreshForContinueWatching(intent) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                return@withLock listOf(com.nuvio.tv.core.tracking.TrackingProgressRefreshFailure(provider.providerId, error))
            }
        }
        refreshProjectionLocked()
        emptyList()
    }

    suspend fun refreshProjection() {
        refreshSelected(com.nuvio.tv.core.tracking.TrackingRefreshIntent.AUTOMATIC)
    }

    suspend fun historyCommitted(profileId: Int, contentId: String, before: Long) {
        publishCommittedInputs(profileId, setOf(contentId))
        hideFinishedSeries(profileId, contentId, before)
    }

    /** Recompute from the selected provider's committed episode history, including
     * the durable undo journal, before network reconciliation or Home navigation. */
    private suspend fun publishCommittedInputs(profileId: Int, contentIds: Set<String>) {
        val state = snapshot()
        if (state.profileId != profileId) return
        val progress = progressProvider.get()
        val items = progress.allProgress.first()
        val sourceSeeds = progress.observeNextUpSeeds().first()
        val remaining = mutableListOf<WatchProgress>()
        for (id in contentIds) {
            // The provider's single latest seed may itself be the revoked event. Read
            // remaining history, not a filtered latest seed that loses the whole show.
            progress.getAllEpisodeProgress(id, profileId).first().values
                .filter { it.isCompleted() }.maxByOrNull { it.lastWatched }?.let(remaining::add)
        }
        val seeds = sourceSeeds.filterNot { it.contentId in contentIds } + remaining
        val watched = progress.watchedItems.first()
        var applied = false
        val updated = mutate(state.lease, background = true) {
            applied = true
            val committed = reduceProgress(it, items, sourceSeeds, progress, emptyMap())
            // A selected-order view is an offer seed, not another tracker completion.
            // Keep the source ledger; only rebuild missing membership from that view.
            CwMembershipReducer.progress(committed, remaining.filter { seed -> seed.contentId !in committed.members }, seeds = true)
        }
        if (!applied || updated.profileId != state.profileId || updated.source != state.source) return
        resolvePendingPauses(updated, updated.members.filterKeys { it in contentIds }.values)
        val ready = snapshot()
        if (ready.profileId != state.profileId || ready.source != state.source || ready.epoch != updated.epoch) return
        val absent = contentIds.filterTo(mutableSetOf()) { it !in ready.members }
        if (absent.isNotEmpty()) mutate(ready.lease, background = true, settledIds = absent) { it }
        _settledProgress.value = SettledProgress(ready.lease, items, seeds, watched)
    }

    private suspend fun refreshProjectionLocked() {
        val state = snapshot()
        val progress = progressProvider.get()
        val loaded = progress.observeRemoteProgressLoaded().first()
        if (!loaded && state.source != "NUVIO_SYNC") {
            // A device receipt may have committed just before process death. Stage it;
            // it still cannot replace the durable card until its episode is resolved.
            val own = progress.allProgress.first().filter { it.source == WatchProgress.SOURCE_LOCAL && it.trackingProviderId == null }
            publishTrackerProgress(state.lease, own, emptyList(), progress, emptyMap(), false)
            return
        }
        progress.confirmSelectedDeviceProgress()
        val seeds = progress.observeNextUpSeeds().first()
        val items = progress.allProgress.first()
        val movies = movieCompletionTimes(state.lease).first()
        publishTrackerProgress(state.lease, items, seeds, progress, movies, loaded)
        for (event in deviceEvents?.get()?.events(state.profileId).orEmpty().filter { it.removed })
            hideFinishedSeries(state.profileId, event.progress.contentId, before = event.startedAt)
        val ready = snapshot()
        if (ready.profileId != state.profileId || ready.source != state.source) return
        val absent = ready.presentedMembers.orEmpty().keys - ready.members.keys
        if (absent.isNotEmpty()) mutate(ready.lease, background = true, settledIds = absent) { it }
        _settledProgress.value = SettledProgress(ready.lease, items, seeds, progress.watchedItems.first())
    }

    private suspend fun publishTrackerProgress(lease: CwLease, items: List<WatchProgress>,
        seeds: List<WatchProgress>, progress: WatchProgressRepository, movies: Map<String, Long?>, loaded: Boolean) {
        // Stage membership and resolve winning pauses outside the store lock. The saved
        // presentation stays intact until Home finishes this show's episode enrichment.
        var replace = emptySet<String>()
        val recheckPauses = mutableSetOf<String>()
        val provider = if (loaded && lease.source == "TRAKT")
            trackingProviders?.get()?.provider(com.nuvio.tv.core.tracking.TrackingProviderId.TRAKT) else null
        val state = mutate(lease, afterCommit = { restoredSeries.removeAll(replace) }, background = true) { previous ->
            var current = previous
            if (provider != null) for (member in previous.members.values) {
                val event = member.progress ?: continue
                if (provider.confirmsRemovedWatch(lease.profileId, event))
                    current = CwMembershipReducer.removeWatchEvent(current, event)
            }
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
            if (loaded && progress.hasActiveTrackingProgressProvider()) {
                // The fresh selected snapshot owns membership, including the expiry or
                // acknowledgement of a device receipt. Don't let an old local winner
                // veto it forever. Preserve writes committed during this refresh.
                replace = (current.members.keys + seeds.map { it.contentId } + items.map { it.contentId })
                    .filterTo(mutableSetOf()) { (current.members[it]?.changedAtRevision ?: 0) <= lease.revision }
            }
            if (replace.isEmpty()) return@mutate reduceProgress(current, items, seeds, progress, movies, recheckPauses::add)
            val base = current.copy(
                members = current.members - replace,
                completed = if (restoringTracker) current.completed - replace else current.completed,
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
                val prior = old.progress
                if (old.inProgress == null && old.nextUp == null && prior != null &&
                    prior.videoId == winner.videoId && prior.lastWatched == winner.lastWatched &&
                    prior.progressPercentage == winner.progressPercentage && prior.tvdbContentIds == winner.tvdbContentIds &&
                    old.latestCompletionAt == member.latestCompletionAt && current.completed[id] == rebuilt.completed[id]) {
                    // A conclusively completed series stays absent on an identical refresh.
                    members[id] = old.copy(progress = winner)
                    continue
                }
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
            }
            if (resolved == null) {
                mutate(lease, afterCommit = { unresolvedPauses[raw.contentId] = raw }, background = true) { it }
                continue
            }
            mutate(lease, afterCommit = { if (unresolvedPauses[raw.contentId] == raw) unresolvedPauses.remove(raw.contentId) }, background = true) { current ->
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
