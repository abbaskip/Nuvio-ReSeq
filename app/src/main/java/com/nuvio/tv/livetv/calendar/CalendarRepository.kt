package com.nuvio.tv.livetv.calendar

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.cw.ContinueWatchingRepository
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CalendarRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: ProfileManager,
    private val library: Lazy<LibraryRepository>,
    private val progress: Lazy<WatchProgressRepository>,
    private val watching: Lazy<ContinueWatchingRepository>,
    private val settings: Lazy<TraktSettingsDataStore>,
    private val watched: Lazy<WatchedItemsPreferences>,
    private val sources: Lazy<CalendarSources>,
    private val local: Lazy<CalendarLocalListings>
) {
    private val cache by lazy { CalendarPageCache(File(context.cacheDir, "calendar")) }
    private val gate = Mutex()
    val profile = profiles.resolvedActiveProfileId

    fun restore(profile: Int, region: String, zone: ZoneId): CalendarPage =
        cache.read(profile, region, zone.id) ?: CalendarPage(region = region, zone = zone.id, generation = CalendarPageCache.generation(profile))

    private suspend fun checkProfile(profile: Int) {
        currentCoroutineContext().ensureActive()
        if (profiles.resolvedActiveProfileId.first() != profile) throw CancellationException("Calendar profile changed")
    }

    private suspend fun titles(profile: Int): List<CalendarTitle> {
        val found = LinkedHashMap<String, CalendarTitle>()
        fun put(id: String, title: String, type: String, at: Long, imdbId: String? = null, tmdbId: Int? = null) {
            val movie = type.equals("movie", true)
            if (!movie && !type.equals("series", true) && !type.equals("tv", true) && !type.equals("anime", true)) return
            if (id.isBlank() || id.length > 256 || title.isBlank()) return
            val imdb = (imdbId ?: id).takeIf(IMDB::matches)
            val tmdb = tmdbId ?: id.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')?.toIntOrNull()
            val next = CalendarTitle(id, title.trim().take(240), movie, imdb, tmdb, at)
            val prior = found[next.key]
            found[next.key] = if (prior == null) next else prior.copy(lastActivity = maxOf(at, prior.lastActivity),
                imdb = prior.imdb ?: imdb, tmdb = prior.tmdb ?: tmdb)
        }

        // The ordinary Library and Next Up subscriptions can launch full tracker synchronizations.
        val entries = library.get().cachedLibraryItems(profile) ?: throw IOException("Library snapshot pending")
        entries.forEach { put(it.id, it.name, it.type, it.listedAt, it.imdbId, it.tmdbId) }
        val source = settings.get().resolvedWatchProgressSource(profile).first()
        if (source == WatchProgressSource.NUVIO_SYNC) {
            watched.get().observeAllItems(profile).first().forEach {
                if (it.season != null && it.episode != null) put(it.contentId, it.title, it.contentType, it.watchedAt)
            }
        }
        val cw = watching.get()
        val settled = cw.projection.value?.takeIf { it.profileId == profile && it.source == source.name }
            ?: cw.observeProjection().first()
        if (settled.profileId != profile || settled.source != source.name) throw CancellationException("Calendar history changed")
        settled.members.forEach { (id, member) ->
            if (id !in settled.hiddenByProvider && !progress.get().isDroppedShow(id)) {
                member.inProgress?.takeIf { it.season != null }?.let { put(id, it.name, it.contentType, it.lastWatched) }
                member.progress?.takeIf { it.season != null }?.let { put(id, it.name, it.contentType, it.lastWatched) }
                member.nextUp?.let { put(id, it.name, it.contentType, it.lastWatched) }
            }
        }
        return found.values.sortedByDescending { it.lastActivity }
    }

    fun allowedNames(keys: Set<String>) = local.get().allowedNames(keys)

    /** Caller owns this work for exactly the visible, idle route lifetime. */
    suspend fun update(profile: Int, initial: CalendarPage, manual: Boolean,
        publish: suspend (CalendarPage) -> Unit): Boolean = gate.withLock {
        checkProfile(profile)
        val now = System.currentTimeMillis()
        val zone = ZoneId.of(initial.zone)
        val today = LocalDate.now(zone)
        val window = CalendarWindow.around(today)
        val sport = local.get().sports(window, now, zone)
        val sportsPage = initial.copy(items = CalendarPolicy.displayed(initial.items.filter { it.source != "sports" } + sport,
            window, today.toEpochDay()))
        if (sportsPage.items != initial.items) {
            checkProfile(profile)
            cache.write(profile, sportsPage)
            publish(sportsPage)
        }
        val titles = withTimeoutOrNull(12_000) { titles(profile) } ?: throw IOException("Library unavailable")
        val current = titles.mapTo(HashSet()) { it.key }
        val remainingChecks = initial.checked.filterKeys { it in current }
        val failedTitles = initial.failedTitles.filterTo(mutableSetOf()) { it in current }
        val updates = CalendarPolicy.work(titles, remainingChecks, now).sortedBy { it.key in failedTitles }
        val batch = if (manual && updates.isEmpty()) titles.take(CalendarPolicy.BATCH) else updates.take(CalendarPolicy.BATCH)
        var page = initial.copy(checked = remainingChecks, remaining = updates.size,
            guideTitles = initial.guideTitles.filter { it in current },
            items = initial.items.filter { it.source in current } + sport)
        page = local.get().guide(page, titles, now, zone)
        val checks = page.checked.toMutableMap()
        val items = page.items.toMutableList()
        var failed = false

        fun snapshot(finished: Boolean): CalendarPage =
            page.copy(checkedAt = if (finished) now else initial.checkedAt,
                retryAt = if (failedTitles.isEmpty()) 0 else now + CalendarPolicy.RETRY_MS,
                failedTitles = failedTitles.take(CalendarPolicy.MAX_CHECKS),
                checked = checks.entries.sortedByDescending { it.value }.take(CalendarPolicy.MAX_CHECKS).associate { it.toPair() },
                remaining = titles.count { !CalendarPolicy.fresh(checks[it.key] ?: 0L, now) },
                items = CalendarPolicy.displayed(items, window, today.toEpochDay()))
        var dirty = false
        suspend fun save(finished: Boolean) {
            checkProfile(profile)
            val result = snapshot(finished)
            try { cache.write(profile, result) } catch (_: IOException) { failed = true }
            dirty = false
            publish(result)
        }
        // Make local guide and Sports data durable before the first network request.
        save(false)
        try {
            for ((index, title) in batch.withIndex()) {
                checkProfile(profile)
                try {
                    val rows = sources.get().listings(profile, title, window, zone, page.region)
                    items.removeAll { it.source == title.key && it.kind != CalendarKind.GUIDE }
                    items.addAll(rows)
                    checks[title.key] = now
                    failedTitles.remove(title.key)
                    dirty = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: java.net.UnknownHostException) {
                    failedTitles.add(title.key)
                    failed = true
                    break
                } catch (_: IOException) {
                    failedTitles.add(title.key)
                    failed = true
                } catch (_: org.json.JSONException) {
                    failedTitles.add(title.key)
                    failed = true
                }
                if ((index + 1) % 3 == 0 && index != batch.lastIndex && dirty) save(false)
            }
            save(true)
        } finally {
            // Only the completed rows are checkpointed on Back or background. The HTTP call
            // and all remaining titles belong to the cancelled route, never to an app worker.
            if (dirty) withContext(NonCancellable) {
                try { cache.write(profile, snapshot(false)) } catch (_: IOException) { }
            }
        }
        failed
    }

    companion object {
        private val IMDB = Regex("tt[0-9]+")
        fun region(): String = Locale.getDefault().country.uppercase(Locale.ROOT).ifBlank { "US" }
    }
}
