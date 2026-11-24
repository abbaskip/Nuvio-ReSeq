package com.nuvio.tv.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.Keep
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.mdblist.*
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.simkl.SimklAuthStorage
import com.nuvio.tv.data.tvdb.TvdbTrackingResolver
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface OfflineWatchScheduler {
    fun schedule(profile: Int)
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class OfflineWatchSchedulerModule {
    @dagger.BindsOptionalOf abstract fun scheduler(): OfflineWatchScheduler
}

@Keep
internal data class OfflineWatch(val provider: TrackingProviderId, val account: String, val progress: WatchProgress)

/** An offline completion belongs to the profile and tracker account which played it. */
@Singleton
class OfflineWatchQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: ProfileDataStoreFactory,
    private val profiles: ProfileManager,
    private val auth: MdbListAuthStore,
    private val sync: MdbListSyncRepository,
    private val history: MdbListHistoryService,
    private val mappings: MdbListEpisodeMappingService,
    private val tvdb: dagger.Lazy<TvdbTrackingResolver>,
    private val trakt: TraktAuthDataStore,
    private val simkl: SimklAuthStorage,
    private val providers: dagger.Lazy<TrackingProgressProviderRegistry>,
    private val writers: dagger.Lazy<TrackingHistoryWriterRegistry>,
    private val scheduler: java.util.Optional<OfflineWatchScheduler> = java.util.Optional.empty()
) {
    private val key = stringPreferencesKey("watches")
    private val gson = Gson()
    private val type = object : TypeToken<List<OfflineWatch>>() {}.type
    private val delivery = Mutex()
    private fun store(profile: Int) = factory.get(profile, "offline_tracker_watches")
    private fun decode(raw: String?): List<OfflineWatch> = raw?.let { gson.fromJson<List<OfflineWatch>>(it, type) } ?: emptyList()
    private fun account(profile: Int, state: MdbListAuthState) =
        state.takeIf { it.scope.profileId == profile && it.isAuthenticated }?.user?.id

    private fun accounts(profile: Int): Flow<Map<TrackingProviderId, String>> =
        combine(trakt.state, simkl.state, auth.state) { t, s, m ->
            buildMap {
                if (profiles.activeProfileId.value != profile) return@buildMap
                if (t.isAuthenticated) (t.userSlug ?: t.username)?.takeIf(String::isNotBlank)?.let { put(TrackingProviderId.TRAKT, it) }
                if (s.isAuthenticated && simkl.currentScope().profileId == profile)
                    (s.accountId?.toString() ?: s.username)?.takeIf(String::isNotBlank)?.let { put(TrackingProviderId.SIMKL, it) }
                account(profile, m)?.let { put(TrackingProviderId.MDBLIST, it.toString()) }
            }
        }

    internal fun pending(profile: Int, source: TrackingProviderId? = null): Flow<List<WatchProgress>> =
        combine(store(profile).data, accounts(profile)) { prefs, owners ->
            decode(prefs[key]).filter { (source == null || source == it.provider) && owners[it.provider] == it.account }
                .map { it.progress }.distinct()
        }.distinctUntilChanged()

    internal suspend fun recordIfOffline(profile: Int, progress: WatchProgress) {
        val network = context.getSystemService(ConnectivityManager::class.java)
        if (network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) return
        if (!progress.isCompleted() || progress.tvdbCoverageVerified == false ||
            progress.toTrackingHistoryItem(progress.lastWatched).isWholeSeriesMark) return
        val watches = accounts(profile).first().map { (id, owner) -> OfflineWatch(id, owner, progress) }
        if (watches.isEmpty()) return
        store(profile).edit { prefs ->
            val rows = decode(prefs[key])
            val fresh = watches.filterNot { watch -> rows.any { existing ->
                existing == watch || existing.account == watch.account && existing.provider == watch.provider &&
                    progress.deviceWatchEventId != null && existing.progress.deviceWatchEventId == progress.deviceWatchEventId
            } }
            if (fresh.isNotEmpty()) prefs[key] = gson.toJson(rows + fresh)
        }
        scheduler.orElse(null)?.schedule(profile)
    }

    internal suspend fun discard(profile: Int, matches: (WatchProgress) -> Boolean) = delivery.withLock {
        store(profile).edit { prefs ->
            val rows = decode(prefs[key])
            val remaining = rows.filterNot { matches(it.progress) }
            if (remaining.size != rows.size) prefs[key] = gson.toJson(remaining)
        }
        Unit
    }

    internal suspend fun deliver(profile: Int): Boolean = withContext(Dispatchers.IO) {
        delivery.withLock { deliverLocked(profile) }
    }

    private suspend fun deliverLocked(profile: Int): Boolean {
        val owners = accounts(profile).first()
        val rows = decode(store(profile).data.first()[key]).filter { owners[it.provider] == it.account }
        var changed = false
        for ((id, watches) in rows.groupBy { it.provider }) {
            try {
                val owner = watches.first().account
                suspend fun checkOwner() = check(accounts(profile).first()[id] == owner)
                checkOwner()
                val scope = auth.state.value.scope
                val provider = if (id == TrackingProviderId.MDBLIST) null else requireNotNull(providers.get().provider(id))
                if (provider == null) {
                    sync.invalidate(scope, setOf(MdbListSyncBucket.WATCHED))
                    sync.refresh(TrackingRefreshIntent.INVALIDATED)
                    check(sync.state.value.scope == scope && sync.state.value.error == null &&
                        sync.currentSnapshot()?.invalidatedBuckets?.contains(MdbListSyncBucket.WATCHED) == false)
                } else {
                    provider.refreshForContinueWatching(TrackingRefreshIntent.INVALIDATED)
                }
                for (watch in watches.sortedBy { it.progress.lastWatched }) {
                    checkOwner()
                    val row = watch.progress
                    val item = row.toTrackingHistoryItem(row.lastWatched, userSelectedWatchTime = true)
                    val targets = tvdb.get().offlineHistoryTargets(profile, id, item.media, row.lastWatched)
                    if (targets.isNullOrEmpty()) continue
                    for (target in targets) {
                        checkOwner()
                        val media = if (provider == null) mappings.toMdbListNumbering(target, sync.currentProjection().mediaIndex) else target
                        val dated = item.copy(media = media)
                        check(!dated.isWholeSeriesMark)
                        val saved = if (provider == null) {
                            val snapshot = requireNotNull(sync.currentSnapshot())
                            val native = requireNotNull(snapshot.mutationTarget(media))
                            snapshot.watched.any { native.matches(it) && mdbListTimestamp(it.watchedAt) / 1000 == row.lastWatched / 1000 }
                        } else confirmed(provider, profile, dated)
                        if (!saved) {
                            checkOwner()
                            val result = if (provider == null) history.add(scope, listOf(dated))
                                else requireNotNull(writers.get().writer(id)).addOfflineWatch(profile, dated)
                            check(result.isComplete && result.attemptedCount == 1)
                        }
                    }
                    checkOwner()
                    val nativeCoverage = targets.map { it.catalog?.tvdbEpisodeIds.orEmpty().toSet() }
                    store(profile).edit { prefs ->
                        val remaining = decode(prefs[key]).filterNot { older ->
                            older == watch || coveredEarlierPart(older, watch, nativeCoverage)
                        }
                        prefs[key] = gson.toJson(remaining)
                    }
                    changed = true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("OfflineHistory", "$id completion pending (${e.javaClass.simpleName})") }
        }
        return changed
    }

    private fun coveredEarlierPart(older: OfflineWatch, finished: OfflineWatch, targets: List<Set<Long>>): Boolean {
        val prior = older.progress
        val last = finished.progress
        if (older.provider != finished.provider || older.account != finished.account || prior.contentId != last.contentId ||
            prior.lastWatched >= last.lastWatched || prior.tvdbContext?.seriesId != last.tvdbContext?.seriesId) return false
        val ids = prior.tvdbWatchedIds
        return ids.isNotEmpty() && ids.intersect(last.tvdbWatchedIds).isEmpty() &&
            targets.any { it.containsAll(ids) && it.any(last.tvdbWatchedIds::contains) }
    }

    private suspend fun confirmed(provider: TrackingProgressProvider, profile: Int, item: TrackingHistoryItem): Boolean {
        val content = requireNotNull(item.media.catalog).contentId
        val episode = item.media.episode
        val second = requireNotNull(item.watchedAtEpochMs) / 1000
        if (episode == null) return provider.movieCompletionTimes(profile).first()[content]?.div(1000) == second
        return provider.completedNextUpHistory(content).any {
            it.lastWatched / 1000 == second && when {
                episode.tvdbId != null && it.tvdbEpisodeId != null -> episode.tvdbId == it.tvdbEpisodeId
                episode.traktId != null && it.traktEpisodeId != null -> episode.traktId == it.traktEpisodeId
                else -> episode.season == it.season && episode.number == it.episode
            }
        }
    }
}
