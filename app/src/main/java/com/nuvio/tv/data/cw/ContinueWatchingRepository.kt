package com.nuvio.tv.data.cw

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
    private val tvdbOrders: com.nuvio.tv.data.tvdb.TvdbOrderStore? = null
) {
    private val store = CwProjectionStore(File(context.filesDir, "cw_enrichment"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e("ContinueWatching", "Projection operation failed", error)
    })
    private val started = AtomicBoolean()
    private var current: CwProjection? = null
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
                    combine(progress.allProgress, progress.observeNextUpSeeds()) { items, seeds -> items to seeds }
                        .collect { (items, seeds) ->
                            val active = snapshot().lease
                            if (active.profileId != lease.profileId || active.source != lease.source) return@collect
                            mutate(active) { state ->
                                reduceProgress(state, items, seeds, progress)
                            }
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
        val loaded = store.read(profile, source)
        epoch = maxOf(epoch + 1, loaded.epoch + 1)
        return approveIdentity(loaded.copy(epoch = epoch)).also { current = it; _projection.value = it }
    }

    private suspend fun mutate(lease: CwLease, transform: (CwProjection) -> CwProjection): CwProjection =
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
                if (active != null) { current = next; _projection.value = next }
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
        val state = snapshot()
        if (state.profileId != profileId) return
        mutate(state.lease) { CwMembershipReducer.historyRemoved(it, contentId, episodes, System.currentTimeMillis(), tvdbIds) }
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
        val seeds = progress.observeNextUpSeeds().first()
        val items = progress.allProgress.first()
        mutate(state.lease) { reduceProgress(it, items, seeds, progress) }
    }

    private fun reduceProgress(state: CwProjection, items: List<WatchProgress>, seeds: List<WatchProgress>,
        progress: WatchProgressRepository): CwProjection {
        val next = CwMembershipReducer.progress(CwMembershipReducer.progress(state, seeds, seeds = true), items)
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
