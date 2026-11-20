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
    private val gate = Mutex()
    val profile = profiles.resolvedActiveProfileId

    private suspend fun checkProfile(profile: Int) {
        currentCoroutineContext().ensureActive()
        if (profiles.resolvedActiveProfileId.first() != profile) throw CancellationException("Calendar profile changed")
    }

    private data class Inputs(val watchedIds: Map<String, String>, val movies: List<CalendarTitle>, val libraryReady: Boolean) {
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
        }

        val entries = library.get().cachedLibraryItems(profile)
        val movies = mutableListOf<CalendarTitle>()
        for (entry in entries.orEmpty()) {
            if (entry.id.isBlank() || entry.name.isBlank()) continue
            val imdb = entry.imdbId ?: entry.id.takeIf(IMDB::matches)
            val tmdb = entry.tmdbId ?: entry.id.removePrefix("tmdb:").takeIf { entry.id.startsWith("tmdb:") }?.toIntOrNull()
            if (entry.type.equals("movie", true)) {
                movies += CalendarTitle(entry.id, entry.name.take(240), true, imdb, tmdb, entry.listedAt,
                    entry.traktId ?: entry.id.takeIf { it.startsWith("trakt:") }?.substringAfter(':')?.toIntOrNull())
            } else {
                // Library metadata can connect an already watched title to another provider ID.
                // A library entry by itself is never episode watch history.
                val aliases = listOfNotNull(entry.id, imdb, tmdb?.let { "tmdb:" + it }, entry.traktId?.let { "trakt:" + it },
                    entry.simklId?.let { "simkl:" + it })
                if (aliases.any(watchedIds::containsKey) && aliases.none { it in history.hiddenByProvider || tracker.isDroppedShow(it) }) {
                    aliases.forEach { watchedIds[it] = entry.id }
                }
            }
        }
        checkProfile(profile)
        Inputs(watchedIds, movies.distinctBy { it.key }, entries != null)
    } ?: throw IOException("Calendar inputs unavailable")

    fun allowedNames(keys: Set<String>) = local.get().allowedNames(keys)
    fun sports(profile: Int, zone: ZoneId, week: Long) = local.get().sports(profile, zone, CalendarWindow(week, week + 6))

    fun pages(profile: Int, region: String, zone: ZoneId, week: Long, playing: Flow<Boolean>, requests: Flow<Boolean>) = flow {
        var changedInputs: Inputs? = null
        emitAll(calendarSession(playing, requests, System::currentTimeMillis,
            restore = { cache.read(profile, region, zone.id, week) ?: CalendarPage(region = region, zone = zone.id,
                weekStart = week, generation = CalendarPageCache.generation(profile)) },
            inputsChanged = { page ->
                val current = inputs(profile)
                (current.signature != page.inputSignature).also { if (it) changedInputs = current }
            },
            update = { page, manual, publish ->
                val current = changedInputs ?: inputs(profile)
                changedInputs = null
                update(profile, current, page, manual, publish)
            }))
    }

    private suspend fun update(profile: Int, input: Inputs, initial: CalendarPage, manual: Boolean,
        publish: suspend (CalendarPage) -> Unit): Boolean = gate.withLock {
        checkProfile(profile)
        val now = System.currentTimeMillis()
        val zone = ZoneId.of(initial.zone)
        val week = CalendarWindow(initial.weekStart, initial.weekStart + 6)
        val today = now.calendarDay(zone)
        val savedMovies = input.movies.associateBy { it.key }
        val watchedShows = input.watchedIds.values.toHashSet()
        val sameInputs = input.signature == initial.inputSignature
        val items = initial.items.filter { row -> when (row.kind) {
            CalendarKind.EPISODE -> row.contentId in watchedShows
            CalendarKind.GUIDE -> sameInputs
            else -> !input.libraryReady || row.source in savedMovies
        } }.toMutableList()
        val checks = initial.checked.filterKeys { it in savedMovies || (it == EPISODES && sameInputs) }.toMutableMap()
        val episodesFresh = CalendarPolicy.fresh(checks[EPISODES] ?: 0L, now)
        var page = initial.copy(inputSignature = input.signature)
        var failed = !input.libraryReady
        var dirty = true
        fun snapshot(finished: Boolean) = page.copy(
            checkedAt = if (finished) now else 0, checked = checks.toMap(),
            retryAt = if (failed) now + CalendarPolicy.RETRY_MS else 0,
            items = CalendarPolicy.displayed(items, week, today))
        fun store(value: CalendarPage) {
            try { cache.write(profile, value) } catch (_: IOException) { failed = true }
            dirty = false
        }
        val oldEpisodes = items.filter { it.kind == CalendarKind.EPISODE }
        val guidePage = page.copy(items = items.filter { it.kind == CalendarKind.GUIDE },
            guideTitles = if (sameInputs) page.guideTitles else emptyList())
        try {
            publish(snapshot(false))
            calendarLookups(
                episodes = {
                    if (!manual && episodesFresh) oldEpisodes
                    else sources.get().scheduledEpisodes(input.watchedIds, week, zone)
                },
                guide = { episodes ->
                    val airing = (episodes ?: oldEpisodes).distinctBy { it.contentId }.mapNotNull { row ->
                        row.contentId?.let { CalendarTitle(it, row.title, false, null, null, row.time ?: 0) }
                    }
                    local.get().guide(guidePage, airing + input.movies.sortedByDescending { it.lastActivity }, now, zone)
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
                    is CalendarLookup.Guide -> if (result.page == null) failed = true else {
                        val guide = result.page
                        items.removeAll { it.kind == CalendarKind.GUIDE }; items.addAll(guide.items)
                        page = page.copy(guideTitles = guide.guideTitles, guideDay = guide.guideDay, guideRevision = guide.guideRevision)
                    }
                    is CalendarLookup.Release -> if (result.rows == null) failed = true else {
                        val key = result.title.key
                        items.removeAll { it.source == key && it.kind != CalendarKind.GUIDE }; items.addAll(result.rows)
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
