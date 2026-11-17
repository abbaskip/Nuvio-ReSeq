package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import java.util.UUID

data class DeviceWatchEvent(
    val id: String,
    val progress: WatchProgress,
    val startedAt: Long,
    val lastAt: Long,
    val priorLocal: Map<String, WatchProgress> = emptyMap(),
    val priorWatched: List<WatchedItem> = emptyList(),
    val baselines: Map<String, List<TrackingWatchBaseline>> = emptyMap(),
    val removed: Boolean = false,
    val handled: Set<String> = emptySet(),
    val nativeEvent: WatchProgress? = null
) {
    fun matches(row: WatchProgress): Boolean {
        if (row.contentId != progress.contentId) return false
        val a = progress.tvdbContext; val b = row.tvdbContext
        return if (a != null && b != null) a.seriesId == b.seriesId && a.episodeId == b.episodeId && a.episodeIds == b.episodeIds
        else if (a != null && row.tvdbContentIds.isNotEmpty()) a.episodeIds.toSet() == row.tvdbContentIds
        else row.season == progress.season && row.episode == progress.episode
    }
    fun contains(at: Long) = at in (startedAt - 2_000)..(lastAt + 2_000)
    fun removes(row: WatchProgress, through: Long = lastAt + 2_000): Boolean = removed &&
        (if (row.deviceWatchEventId != null) row.deviceWatchEventId == id else
        row.lastWatched in (startedAt - 2_000)..through && (matches(row) ||
            progress.traktPlaybackId != null && row.traktPlaybackId == progress.traktPlaybackId ||
            nativeEvent?.traktPlaybackId != null && row.traktPlaybackId == nativeEvent.traktPlaybackId ||
            baselines.values.flatten().any { it.target.matchesEpisode(row) }))
}

/** Device-only event/undo journal. Its other-provider snapshots are ONLY used for
 * an explicit undo; they are never inputs to CW/Details episode selection. */
@Singleton
class DeviceWatchEventStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profiles: ProfileManager,
    private val local: WatchProgressPreferences,
    private val watched: WatchedItemsPreferences,
    private val providers: Provider<TrackingProgressProviderRegistry>,
    private val tvdb: Provider<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>
) {
    private val gson = Gson()
    private val key = stringPreferencesKey("events")
    private val lock = Mutex()
    internal var now: () -> Long = System::currentTimeMillis
    private fun store(profile: Int) = factory.get(profile, "device_watch_events")
    private fun decode(raw: String?): List<DeviceWatchEvent> = raw?.let {
        gson.fromJson<List<DeviceWatchEvent>>(it, object : TypeToken<List<DeviceWatchEvent>>() {}.type)
    }.orEmpty()
    fun observe(profile: Int): Flow<List<DeviceWatchEvent>> = store(profile).data.map { decode(it[key]) }.distinctUntilChanged()
    suspend fun events(profile: Int) = observe(profile).first()
    private suspend fun put(profile: Int, event: DeviceWatchEvent) {
        store(profile).edit { prefs ->
            val rows = decode(prefs[key]).filterNot { it.id == event.id } + event
            prefs[key] = gson.toJson(rows.filter { it.removed }.takeLast(256) + rows.filterNot { it.removed }.takeLast(256))
        }
    }

    suspend fun record(profile: Int, row: WatchProgress): WatchProgress = lock.withLock {
        if (row.source != WatchProgress.SOURCE_LOCAL || row.trackingProviderId != null || row.season == null || row.episode == null) return@withLock row
        val old = events(profile).filter { !it.removed && it.matches(row) }.maxByOrNull { it.startedAt }
        val fresh = old == null || (old.progress.isCompleted() && row.progressPercentage < .01f)
        val event = if (!fresh) old!! else {
            val candidate = DeviceWatchEvent(UUID.randomUUID().toString(), row, row.lastWatched, row.lastWatched)
            candidate.copy(priorLocal = local.getAllRawEntries(profile).filterValues(candidate::matches),
                priorWatched = watched.getAllItems(profile).filter { it.contentId == row.contentId && it.season == row.season && it.episode == row.episode })
        }
        val identified = row.copy(deviceWatchEventId = event.id)
        put(profile, event.copy(progress = identified, lastAt = maxOf(event.lastAt, row.lastWatched)))
        identified
    }

    suspend fun beforeStart(event: TrackingScrobbleEvent) {
        val catalog = event.media.catalog ?: return
        val episode = event.media.episode ?: return
        val profile = profiles.activeProfileId.value
        val row = WatchProgress(catalog.contentId, catalog.contentType, event.media.title.orEmpty(), null, null, null,
            catalog.videoId ?: "${catalog.contentId}:${episode.season}:${episode.number}", episode.season, episode.number,
            episode.title, 0, 0, now(), progressPercent = event.progressPercent.toFloat(),
            tvdbContext = catalog.tvdbContext, tvdbEpisodeIds = catalog.tvdbContext?.episodeIds)
        record(profile, row)
        val current = events(profile).filter { !it.removed && it.matches(row) }.maxByOrNull { it.startedAt } ?: return
        if (current.baselines.isNotEmpty()) return
        val baselines = mutableMapOf<String, List<TrackingWatchBaseline>>()
        for (provider in providers.get().providers()) {
            if (profiles.activeProfileId.value != profile) return
            if (!provider.isAuthenticated.first()) continue
            try {
                val targets = if (catalog.tvdbContext == null) listOf(event.media) else
                    requireNotNull(tvdb.get().resolveWatchEventTargets(profile, provider.providerId, event.media))
                val stops = if (catalog.tvdbContext == null) null else tvdb.get().scrobbleEvents(provider.providerId,
                    TrackingScrobbleAction.STOP, event.copy(progressPercent = 100.0))
                baselines[provider.providerId.name] = targets.map { target ->
                    val stop = stops?.firstOrNull { it.second.media.episode == target.episode }
                    provider.watchBaseline(target).copy(completionExpected =
                        catalog.tvdbContext == null || stop?.first == TrackingScrobbleAction.STOP && stop.second.allowCompletion)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unknown baseline must never become permission to unwatch. */ }
        }
        lock.withLock {
            if (profiles.activeProfileId.value != profile) return@withLock
            val latest = events(profile).firstOrNull { it.id == current.id } ?: return@withLock
            put(profile, latest.copy(baselines = baselines))
        }
    }

    suspend fun currentEvent(event: TrackingScrobbleEvent): Pair<Int, String>? {
        val catalog = event.media.catalog ?: return null
        val profile = profiles.activeProfileId.value
        val latest = events(profile).filter { !it.removed && it.progress.contentId == catalog.contentId &&
            (catalog.tvdbContext?.episodeId?.let { id -> it.progress.tvdbContext?.episodeId == id }
                ?: (it.progress.season == event.media.episode?.season && it.progress.episode == event.media.episode?.number)) }.maxByOrNull { it.startedAt }
        return latest?.let { profile to it.id }
    }

    suspend fun afterStop(identity: Pair<Int, String>?) = lock.withLock {
        val (profile, id) = identity ?: return@withLock
        val latest = events(profile).firstOrNull { it.id == id } ?: return@withLock
        // Include a slow server's confirmation, but through() clips at the next play so
        // a shared native episode can never make this undo delete the next selected part.
        put(profile, latest.copy(lastAt = maxOf(latest.lastAt, now())))
    }

    suspend fun requestRemoval(profile: Int, row: WatchProgress): DeviceWatchEvent = lock.withLock {
        val all = events(profile)
        val own = all.firstOrNull { it.id == row.deviceWatchEventId } ?: all.filter { !it.removed && it.matches(row) &&
            row.lastWatched in (it.startedAt - 2_000)..through(it, all) }.maxByOrNull { it.startedAt }
        val identity = DeviceWatchEvent(UUID.randomUUID().toString(), row, row.lastWatched, row.lastWatched)
        val legacy = if (own != null) null else local.devicePlayReceipts(profile).filter { receipt ->
            identity.matches(receipt.progress) && receipt.progress.source == WatchProgress.SOURCE_LOCAL &&
                receipt.progress.trackingProviderId == null &&
                row.lastWatched in (receipt.recordedAt - 2_000)..(receipt.recordedAt + DEVICE_PROGRESS_GRACE_MS)
        }.maxByOrNull { it.progress.lastWatched }?.progress
        val event = own ?: if (legacy == null) identity else identity.copy(progress = legacy.copy(deviceWatchEventId = identity.id),
            startedAt = minOf(legacy.lastWatched, row.lastWatched), lastAt = maxOf(legacy.lastWatched, row.lastWatched))
        val removed = event.copy(removed = true, nativeEvent = row.takeUnless { it.source == WatchProgress.SOURCE_LOCAL })
        put(profile, removed)
        removed
    }

    private fun through(event: DeviceWatchEvent, events: List<DeviceWatchEvent>): Long = minOf(event.lastAt + 2_000,
        events.filter { it.progress.contentId == event.progress.contentId && it.startedAt > event.startedAt }
            .minOfOrNull { it.startedAt - 1 } ?: Long.MAX_VALUE)

    fun decorate(rows: List<WatchProgress>, events: List<DeviceWatchEvent>): List<WatchProgress> = rows.filterNot { row -> events.any { it.removes(row, through(it, events)) } }.map { row ->
        val own = events.firstOrNull { !it.removed && it.id == row.deviceWatchEventId && it.matches(row) } ?:
            events.filter { !it.removed && it.matches(row) && row.lastWatched in (it.startedAt - 2_000)..through(it, events) }
                .maxByOrNull { it.startedAt }
        row.copy(deviceWatchEventId = own?.id)
    }

    /** Called only by the permitted refresh triggers, and after an explicit undo.
     * With no undo pending it does not read other providers at all. */
    suspend fun reconcile(profile: Int) {
        for (event in events(profile).filter { it.removed }) for (provider in providers.get().providers()) {
            if (profiles.activeProfileId.value != profile) return
            if (provider.providerId.name in event.handled || !provider.isAuthenticated.first()) continue
            try {
                val stored = event.baselines[provider.providerId.name]
                val reference = buildTrackingMediaReference(event.progress.contentType, event.progress.contentId,
                    event.progress.videoId, seasonNumber = event.progress.season, episodeNumber = event.progress.episode,
                    episodeTitle = event.progress.episodeTitle).let { it.copy(catalog = it.catalog!!.copy(tvdbContext = event.progress.tvdbContext)) }
                val targets = stored ?: (if (event.progress.tvdbContext == null) listOf(reference) else
                    requireNotNull(tvdb.get().resolveWatchEventTargets(profile, provider.providerId, reference))).map { TrackingWatchBaseline(it, false, null) }
                check(targets.isNotEmpty()) { "Watch-event targets unresolved" }
                if (stored == null) lock.withLock {
                    val latest = events(profile).firstOrNull { it.id == event.id } ?: return@withLock
                    put(profile, latest.copy(baselines = latest.baselines + (provider.providerId.name to targets)))
                }
                var done = true
                for (baseline in targets) done = provider.removeWatchEvent(baseline.target, TrackingWatchEvent(event.id,
                    event.nativeEvent ?: event.progress, event.startedAt - 2_000, through(event, events(profile)), baseline.known, baseline.watchedAt,
                    event.progress.isCompleted() && baseline.completionExpected &&
                        !(provider.providerId == TrackingProviderId.TRAKT && event.nativeEvent?.source == WatchProgress.SOURCE_TRAKT_PLAYBACK), profileId = profile)) && done
                if (done) lock.withLock {
                    val latest = events(profile).firstOrNull { it.id == event.id } ?: return@withLock
                    put(profile, latest.copy(handled = latest.handled + provider.providerId.name))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the event-specific undo durable for confirmation/retry. */ }
        }
    }
}
