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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
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
    private data class ReleaseSnapshot(val profile: Int, val page: CalendarPage)
    @Volatile private var lastReleases: ReleaseSnapshot? = null

    fun cachedReleases(profile: Int, region: String, zone: ZoneId, window: CalendarWindow): CalendarPage? =
        lastReleases?.takeIf { it.profile == profile }?.page?.takeIf {
            it.region == region && it.zone == zone.id && it.weekStart == window.first && it.days == window.days &&
                it.generation == CalendarPageCache.generation(profile)
        }

    private val gate = Mutex()
    val profile = profiles.resolvedActiveProfileId

    private suspend fun checkProfile(profile: Int) {
        currentCoroutineContext().ensureActive()
        if (profiles.resolvedActiveProfileId.first() != profile) throw CancellationException("Calendar profile changed")
    }

    private data class Inputs(val watchedIds: Map<String, String>, val movies: List<CalendarTitle>, val libraryReady: Boolean,
        val artwork: Map<String, String>) {
        val signature: String by lazy {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) { digest.update(value.toByteArray()); digest.update(0.toByte()) }
            watchedIds.toSortedMap().forEach { (alias, id) -> add(alias); add(id) }
            add("movies")
            movies.sortedBy { it.key }.forEach { add(it.key); add(it.imdb.orEmpty()); add(it.tmdb?.toString().orEmpty()) }
            add(libraryReady.toString())
            android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)
        }
    }

    private suspend fun inputs(profile: Int): Inputs = withTimeoutOrNull(12_000) {
        val source = settings.get().resolvedWatchProgressSource(profile).first()
        val cw = watching.get()
        val history = cw.projection.value?.takeIf { it.profileId == profile && it.source == source.name }
            ?: cw.observeProjection().first { it.profileId == profile && it.source == source.name }
        val tracker = progress.get()
        val watchedIds = LinkedHashMap<String, String>()
        val artwork = mutableMapOf<String, String>()
        fun add(id: String) {
            if (id.isNotBlank() && id !in history.hiddenByProvider && !tracker.isDroppedShow(id)) watchedIds[id] = id
        }
        if (source == WatchProgressSource.NUVIO_SYNC) {
            watched.get().observeAllItems(profile).first().forEach { item ->
                if (item.season != null && item.episode != null) add(item.contentId)
            }
        } else tracker.cachedWatchedShowIds(profile).forEach(::add)
        history.completed.forEach { (id, episodes) ->
            if (episodes.values.any { it.season != null && it.episode != null }) add(id)
        }
        history.members.forEach { (id, member) ->
            if (member.progress?.season != null || member.inProgress?.season != null || (member.nextUp?.lastWatched ?: 0) > 0) add(id)
            (member.nextUp?.backdrop ?: member.inProgress?.backdrop ?: member.progress?.backdrop ?:
                member.nextUp?.poster ?: member.inProgress?.poster ?: member.progress?.poster)?.let { artwork[id] = it }
        }

        val entries = library.get().cachedLibraryItems(profile)
        val movies = mutableListOf<CalendarTitle>()
        for (entry in entries.orEmpty()) {
            if (entry.id.isBlank() || entry.name.isBlank()) continue
            val imdb = entry.imdbId ?: entry.id.takeIf(IMDB::matches)
            val tmdb = entry.tmdbId ?: entry.id.removePrefix("tmdb:").takeIf { entry.id.startsWith("tmdb:") }?.toIntOrNull()
            if (entry.type.equals("movie", true)) {
                (entry.background ?: entry.landscapePoster ?: entry.poster)?.let { artwork[entry.id] = it }
                movies += CalendarTitle(entry.id, entry.name.take(240), true, imdb, tmdb, entry.listedAt,
                    entry.traktId ?: entry.id.takeIf { it.startsWith("trakt:") }?.substringAfter(':')?.toIntOrNull())
            } else {
                // Library metadata can connect an already watched title to another provider ID.
                // A library entry by itself is never episode watch history.
                val aliases = listOfNotNull(entry.id, imdb, tmdb?.let { "tmdb:" + it }, entry.traktId?.let { "trakt:" + it },
                    entry.simklId?.let { "simkl:" + it })
                if (aliases.any(watchedIds::containsKey) && aliases.none { it in history.hiddenByProvider || tracker.isDroppedShow(it) }) {
                    (entry.background ?: entry.landscapePoster ?: entry.poster ?: aliases.firstNotNullOfOrNull(artwork::get))
                        ?.let { artwork[entry.id] = it }
                    aliases.forEach { watchedIds[it] = entry.id }
                }
            }
        }
        checkProfile(profile)
        Inputs(watchedIds, movies.distinctBy { it.key }, entries != null, artwork)
    } ?: throw IOException("Calendar inputs unavailable")

    fun sports(profile: Int, zone: ZoneId, window: CalendarWindow, day: StateFlow<Long>, force: Boolean, overview: Boolean, allSports: Boolean) =
        local.get().sports(profile, zone, window, day, force, overview, allSports)

    fun followedSports(profile: Int) = local.get().followedSports(profile)

    fun selectFixture(item: CalendarItem) { item.fixture?.let { local.get().select(it) } }

    fun pages(profile: Int, region: String, zone: ZoneId, week: Long, playing: Flow<Boolean>, requests: Flow<Boolean>, days: Int = 7) = flow {
        var changedInputs: Inputs? = null
        emitAll(calendarSession(playing, requests, System::currentTimeMillis,
            restore = {
                cachedReleases(profile, region, zone, CalendarWindow(week, week + days - 1))
                    ?: cache.read(profile, region, zone.id, week, days)?.also { lastReleases = ReleaseSnapshot(profile, it) }
                    ?: CalendarPage(region = region, zone = zone.id, weekStart = week, days = days, generation = CalendarPageCache.generation(profile))
            },
            inputsChanged = { page ->
                val current = inputs(profile)
                (current.signature != page.inputSignature).also { if (it) changedInputs = current }
            },
            update = { page, manual, publish ->
                val current = changedInputs ?: inputs(profile)
                changedInputs = null
                update(profile, current, page, manual) { updated ->
                    lastReleases = ReleaseSnapshot(profile, updated)
                    publish(updated)
                }
            }))
    }

    private suspend fun update(profile: Int, input: Inputs, initial: CalendarPage, manual: Boolean,
        publish: suspend (CalendarPage) -> Unit): Boolean = gate.withLock {
        checkProfile(profile)
        val now = System.currentTimeMillis()
        val zone = ZoneId.of(initial.zone)
        val week = CalendarWindow(initial.weekStart, initial.weekStart + initial.days - 1)
        val today = now.calendarDay(zone)
        val savedMovies = input.movies.associateBy { it.key }
        val watchedShows = input.watchedIds.values.toHashSet()
        val sameInputs = input.signature == initial.inputSignature
        val items = initial.items.filter { row -> when (row.kind) {
            CalendarKind.EPISODE -> row.contentId in watchedShows
            CalendarKind.GUIDE, CalendarKind.SPORT -> false
            else -> !input.libraryReady || row.source in savedMovies
        } }.toMutableList()
        val checks = initial.checked.filterKeys { it in savedMovies || (it == EPISODES && sameInputs) }.toMutableMap()
        val episodesFresh = CalendarPolicy.fresh(checks[EPISODES] ?: 0L, now)
        val page = initial.copy(inputSignature = input.signature)
        var failed = !input.libraryReady
        var dirty = true
        fun snapshot(finished: Boolean) = page.copy(
            checkedAt = if (finished) now else 0, checked = checks.toMap(),
            retryAt = if (failed) now + CalendarPolicy.RETRY_MS else 0,
            items = CalendarPolicy.displayed(items.map { row ->
                input.artwork[row.contentId]?.let { art -> if (art == row.artwork) row else row.copy(artwork = art) } ?: row
            }, week, today))
        fun store(value: CalendarPage) {
            try { cache.write(profile, value) } catch (_: IOException) { failed = true }
            dirty = false
        }
        val oldEpisodes = items.filter { it.kind == CalendarKind.EPISODE }
        try {
            publish(snapshot(false))
            calendarLookups(
                episodes = {
                    if (!manual && episodesFresh) oldEpisodes
                    else sources.get().scheduledEpisodes(input.watchedIds, week, zone)
                },
                movies = { sources.get().movieCandidates(profile, input.movies, week, initial.region)
                    .filter { manual || !CalendarPolicy.fresh(initial.checked[it.key] ?: 0L, now) } },
                listings = { title -> sources.get().movie(profile, title, week, initial.region) }
            ).collect { result ->
                checkProfile(profile)
                when (result) {
                    is CalendarLookup.Episodes -> if (result.rows == null) failed = true else {
                        items.removeAll { it.kind == CalendarKind.EPISODE }; items.addAll(result.rows)
                        checks[EPISODES] = now
                    }
                    is CalendarLookup.Release -> if (result.rows == null) failed = true else {
                        val key = result.title.key
                        items.removeAll { it.source == key }
                        items.addAll(result.rows)
                        checks[key] = now
                    }
                    CalendarLookup.MoviesUnavailable -> failed = true
                }
                dirty = true
                val updated = snapshot(false)
                publish(updated)
                store(updated)
            }
            val finished = snapshot(true)
            store(finished)
            publish(finished)
        } finally {
            if (dirty) withContext(NonCancellable) {
                try { cache.write(profile, snapshot(false)) } catch (_: IOException) { }
            }
        }
        failed
    }

    companion object {
        private val IMDB = Regex("tt[0-9]+")
        private const val EPISODES = "episode-schedule"
        fun region(): String = Locale.getDefault().country.uppercase(Locale.ROOT).ifBlank { "US" }
    }
}
