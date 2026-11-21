package com.nuvio.tv.livetv.calendar

import android.content.Context
import com.nuvio.tv.core.storage.DatabaseVersions
import com.nuvio.tv.livetv.data.*
import com.nuvio.tv.livetv.multiview.liveTvChannelVisibility
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.sports.*
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.buffer
import okio.source
import com.squareup.moshi.JsonReader
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal class CalendarLocalListings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: Lazy<LiveTvPreferences>,
    private val channels: Lazy<LiveTvChannelStore>,
    private val guide: Lazy<EpgDatabase>,
    private val fixtures: Lazy<SportsFixtureCache>,
    private val schedules: Lazy<CalendarFixtureSchedules>
) {
    private val matcher by lazy { SportsMatcher(guide.get(), File(context.cacheDir, "calendar-live-guide")) }
    private val guideReads = Mutex()
    fun select(event: SportsEvent) = fixtures.get().select(event)

    fun followedSports(profile: Int): Flow<List<Sport>> =
        combine(prefs.get().sportsMineJson(profile), prefs.get().sportsSettingsJson, SportsCatalog.extraFlow) { raw, options, _ ->
            val follows = SportsFollows.fromJson(raw)
            val ids = follows.leagues + follows.pins + follows.teams.map { it.leagueId }
            val saved = if (ids.any { it.startsWith("tsdb/") && SportsCatalog.league(it) == null }) localLeagueMetadata(follows) else emptyMap()
            SportsRows.followedSports(follows, SportsCatalog.sportsOrder(SportsSettings.fromJson(options)), saved)
        }.distinctUntilChanged()

    private data class Airings(val names: Map<String, String> = emptyMap(), val events: List<SportsEvent> = emptyList(),
        val loading: Boolean = false, val failed: Boolean = false, val day: Long? = null)
    private data class Schedule(val events: List<SportsEvent>, val loading: Boolean, val failed: Boolean = false,
        val day: Long? = null, val complete: Boolean = true)
    private data class DayCheck(val leagues: List<League>, val until: Long, val failed: Boolean)

    private class ChannelVisibility(val channels: List<LiveChannel>, val user: LiveUserState,
        val settings: LiveTvSettings, val unlocked: Set<String>) {
        val visible = liveTvChannelVisibility(channels, user, settings).visible.associateBy { it.key }
    }
    @Volatile private var lastVisibility: ChannelVisibility? = null
    private data class GuideInputs(val visible: Map<String, LiveChannel>, val shift: Int, val revision: String?)
    private data class SportsPageKey(val profile: Int, val generation: Long, val zone: ZoneId, val window: CalendarWindow,
        val overview: Boolean, val allSports: Boolean, val follows: SportsFollows, val settings: SportsSettings, val guide: GuideInputs)
    private data class SportsPage(val key: SportsPageKey, val day: Long, val rows: List<CalendarItem>, val checkedAt: Long?)
    private val recentPages = ArrayDeque<SportsPage>()

    @Synchronized private fun cachedPage(key: SportsPageKey, day: Long): SportsPage? =
        recentPages.lastOrNull { it.day == day && it.key == key }
            ?: if (key.overview) recentPages.lastOrNull { it.key == key } else null

    @Synchronized private fun rememberPage(page: SportsPage) {
        recentPages.removeAll { it.key == page.key && it.day == page.day || it.key.profile != page.key.profile }
        recentPages.addLast(page)
        // A busy month can fill the whole budget. Keep that page usable with every quick filter.
        while (recentPages.size > 1 && (recentPages.size > 3 || recentPages.sumOf { it.rows.size } > 3 * CalendarPolicy.MAX_ITEMS)) {
            recentPages.removeFirst()
        }
    }

    private fun guideInputs(): Flow<GuideInputs> {
        if (!context.getDatabasePath(DatabaseVersions.guide.fileName).isFile) return flowOf(GuideInputs(emptyMap(), 0, null))
        val preferences = prefs.get()
        return combine(channels.get().displayChannels, preferences.userState, preferences.settings, ParentalControls.unlocked) {
            all, user, settings, unlocked ->
            val visibility = lastVisibility?.takeIf {
                it.channels === all && it.user == user && it.settings == settings && it.unlocked == unlocked
            } ?: ChannelVisibility(all, user, settings, unlocked).also { lastVisibility = it }
            GuideInputs(visibility.visible,
                settings.epgOffsetMinutes, guide.get().contentRevision())
        }.distinctUntilChanged()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sports(profile: Int, zone: ZoneId, window: CalendarWindow, day: StateFlow<Long>, force: Boolean, overview: Boolean = false,
        allSports: Boolean = false): Flow<CalendarDisplay> =
        combine(prefs.get().sportsMineJson(profile), prefs.get().sportsSettingsJson) { raw, options ->
            SportsFollows.fromJson(raw) to SportsSettings.fromJson(options)
        }.distinctUntilChanged().flatMapLatest { (follows, settings) ->
            if (!allSports && follows.isEmpty && follows.reminders.isEmpty())
                return@flatMapLatest flowOf(CalendarDisplay(status = CalendarStatus.READY))
            guideInputs().flatMapLatest { guide ->
                val key = SportsPageKey(profile, CalendarPageCache.generation(profile), zone, window, overview, allSports, follows, settings, guide)
                sportsPage(key, day, force)
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun sportsPage(key: SportsPageKey, day: StateFlow<Long>, force: Boolean): Flow<CalendarDisplay> = flow {
        val zone = key.zone
        val window = key.window
        val overview = key.overview
        val allSports = key.allSports
        val follows = key.follows
        val settings = key.settings
        val initialDay = day.value
        var previous = cachedPage(key, initialDay)
        if (previous != null) {
            val checked = previous.checkedAt
            val fresh = previous.day == initialDay && !force && checked != null && android.os.SystemClock.elapsedRealtime() - checked in 0 until REOPEN_FRESH_MS
            emit(CalendarDisplay(previous.rows, if (fresh) CalendarStatus.READY else CalendarStatus.UPDATING))
            if (fresh) day.first { it != initialDay }
        } else emit(CalendarDisplay())
        val now = System.currentTimeMillis()
        val saved = fixtures.get().read(now)
        val leagues = requestedLeagues(follows, saved.savedLeagues + localLeagueMetadata(follows), settings)
        val guideLeagues = if (context.getDatabasePath(DatabaseVersions.guide.fileName).isFile)
            (SportsCatalog.all + leagues).distinctBy { it.id }.filter { SportsTaxonomy.accepts(it, settings.excludedSportsNodes) }
            else emptyList()
        val periodIds = leagues.filter { it.espnPath != null || it.fixtureSlug != null || it.id.startsWith("ca/") }
            .mapTo(HashSet()) { SportsCompetitions.canonicalId(it.id) }
        val explicitIds = follows.leagues + follows.pins + follows.teams.map { it.leagueId }
        val providers = leagues.filter { !it.id.startsWith("tsdb/") || it.id in explicitIds || SportsCompetitions.canonicalId(it.id) !in periodIds }
        val provider = schedule(providers, window, day.value, zone, force)
        val byId = (saved.savedLeagues.values + guideLeagues + leagues).associateBy { it.id }
        val daily = dailySchedule(providers, day, zone, force, overview)
        val guideDays = if (overview) flowOf(day.value) else day
        val tv = guideDays.flatMapLatest { date ->
            airings(key.guide, guideLeagues, settings, follows.words, date, zone, now, if (overview) window else CalendarWindow(date, date))
                .map { it.copy(day = date) }
        }
        // In All events a visible guide competition need not be one of the user's follows.
        // Fetch only competitions the guide found, sharing the existing scan and disk cache.
        val listings = if (allSports || follows.words.isNotEmpty()) guideSchedules(tv, providers, window, day, zone, force, overview) {
            allSports || isMine(it, follows, byId[it.leagueId])
        } else tv
        emitAll(combine(provider, daily, listings) { games, dated, guide ->
            val rows = sportRows(games.events + dated.events + guide.events + saved.events, byId, follows, settings, saved.channels, guide.names, window, zone, allSports)
            val status = when {
                games.loading || dated.loading || guide.loading -> CalendarStatus.UPDATING
                games.failed || dated.failed || guide.failed -> CalendarStatus.RETRY
                else -> CalendarStatus.READY
            }
            val date = dated.day
            val sameDay = date != null && date == day.value && (overview || date == guide.day)
            val old = previous?.takeIf { overview || it.day == day.value }
            val retained = old?.takeIf { status != CalendarStatus.READY || !dated.complete || !sameDay }
            val shown = when {
                retained == null -> rows
                retained.checkedAt != null || rows.isEmpty() -> retained.rows
                else -> (rows + retained.rows).distinctBy { it.key }
            }
            if (date != null && sameDay && retained?.checkedAt == null) {
                val checked = if (status == CalendarStatus.READY && dated.complete) android.os.SystemClock.elapsedRealtime() else null
                if (shown.isNotEmpty() || checked != null) {
                    val snapshot = SportsPage(key, date, shown, checked)
                    rememberPage(snapshot)
                    if (checked != null) previous = snapshot
                }
            }
            CalendarDisplay(shown, status)
        }.distinctUntilChanged())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun dailySchedule(providers: List<League>, selected: StateFlow<Long>, zone: ZoneId, force: Boolean, overview: Boolean) = flow {
        // Scoped to this window, profile, follow set and refresh. Keep empty results as well as games.
        val visited = mutableMapOf<Long, List<SportsEvent>>()
        val completed = mutableMapOf<Long, DayCheck>()
        val initialDay = selected.value
        val daily = selected.flatMapLatest { date ->
            schedules.get().dayLeagues(providers, date, zone).transformLatest { leagues ->
                val checked = completed[date]
                if (checked != null && checked.leagues == leagues && android.os.SystemClock.elapsedRealtime() < checked.until) {
                    emit(Schedule(visited.values.flatten(), false, checked.failed, date))
                    return@transformLatest
                }
                if (overview && date != initialDay && leagues.isNotEmpty()) delay(180)
                val refresh = force && date == initialDay && checked?.leagues != leagues
                schedule(leagues, CalendarWindow(date, date), date, zone, refresh).collect { games ->
                    visited[date] = if (games.loading) (games.events + visited[date].orEmpty()).distinctBy { it.id } else games.events
                    // Day-specific fallback lookups can fill in an open overview without shifting its grid.
                    val showLoading = games.loading && (!overview || completed.isEmpty())
                    if (!games.loading) {
                        val ttl = if (games.failed) CalendarPolicy.RETRY_MS else CalendarPolicy.FRESH_MS
                        completed[date] = DayCheck(leagues, android.os.SystemClock.elapsedRealtime() + ttl, games.failed)
                    }
                    emit(Schedule(visited.values.flatten(), showLoading, games.failed, date, !games.loading))
                }
            }
        }
        emitAll(daily.distinctUntilChanged())
    }

    private fun guideSchedules(tv: Flow<Airings>, providers: List<League>, window: CalendarWindow, day: StateFlow<Long>,
        zone: ZoneId, force: Boolean, overview: Boolean, wanted: (SportsEvent) -> Boolean): Flow<Airings> = channelFlow {
        val seen = providers.mapTo(HashSet()) { SportsCompetitions.canonicalId(it.id) }
        val extra = MutableStateFlow(emptyMap<String, Schedule>())
        val listings = tv.onEach { guide ->
            for (air in guide.events) {
                val id = SportsCompetitions.canonicalId(air.leagueId)
                if (id in seen || !wanted(air)) continue
                seen.add(id)
                val sources = SportsCompetitions.sources(id)
                val source = sources.firstOrNull { it.espnPath != null }
                    ?: sources.firstOrNull { it.fixtureSlug != null || it.id.startsWith("tsdb/") } ?: continue
                extra.update { it + (id to Schedule(emptyList(), true)) }
                launch {
                    val league = listOf(source)
                    combine(schedule(league, window, day.value, zone, force), dailySchedule(league, day, zone, force, overview)) { a, b ->
                        Schedule(a.events + b.events, a.loading || b.loading, a.failed || b.failed)
                    }.collect { chunk -> extra.update { it + (id to chunk) } }
                }
            }
        }
        combine(listings, extra) { guide, chunks ->
            guide.copy(events = guide.events + chunks.values.flatMap { it.events },
                loading = guide.loading || chunks.values.any { it.loading }, failed = guide.failed || chunks.values.any { it.failed })
        }.collect { send(it) }
    }

    private fun schedule(leagues: List<League>, window: CalendarWindow, day: Long, zone: ZoneId, force: Boolean): Flow<Schedule> = flow {
        if (leagues.isEmpty()) { emit(Schedule(emptyList(), false)); return@flow }
        val chunks = mutableMapOf<String, List<SportsEvent>>()
        val failed = mutableSetOf<String>()
        emit(Schedule(emptyList(), true))
        schedules.get().fetch(leagues, window, day, zone, force).collect { result ->
            chunks[result.key] = result.events
            if (result.failed) failed.add(result.key) else failed.remove(result.key)
            emit(Schedule(chunks.values.flatten(), true))
        }
        emit(Schedule(chunks.values.flatten(), false, failed.isNotEmpty()))
    }.conflate()

    private fun airings(inputs: GuideInputs, leagues: List<League>, sports: SportsSettings, words: List<String>, day: Long,
        zone: ZoneId, now: Long, window: CalendarWindow): Flow<Airings> = flow {
        val (visible, shift) = inputs
        val names = visible.mapValues { it.value.name }
        if (names.isEmpty() || leagues.isEmpty()) { emit(Airings(names)); return@flow }
        emit(Airings(names, loading = true))
        delay(180)
        val offset = shift * 60_000L
        fun midnight(epochDay: Long) = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val from = midnight(window.first) - CalendarFixtureBroadcasts.EARLY_COVERAGE_MS
        val covered = withContext(BackgroundWork.reads) { guide.get().sportsGuideRange(from - offset, midnight(window.last + 1) - offset) }
        if (covered == null) { emit(Airings(names)); return@flow }
        val first = maxOf(window.first, (covered.first + offset).calendarDay(zone))
        val days = (first..maxOf(first, (covered.last + offset).calendarDay(zone))).sortedBy { kotlin.math.abs(it - day) }
        val events = mutableListOf<SportsEvent>()
        for (date in days) {
            val batch = withContext(BackgroundWork.reads) {
                val job = currentCoroutineContext()
                guideReads.withLock {
                    matcher.guideEvents(if (date == window.first) from else midnight(date), midnight(date + 1), leagues, words, visible, offset, now, selection = sports,
                        liveCompetitionsOnly = true, checkCancelled = { job.ensureActive() })
                }
            }
            events += batch
            emit(Airings(names, events.toList(), loading = date != days.last()))
        }
    }.onStart { emit(Airings(loading = true)) }.catch { e ->
        if (e is CancellationException) throw e
        emit(Airings(failed = true))
    }

    // A synced follow can refer to a TheSportsDB league added by the shared catalogue. Read only
    // its small metadata section. Calendar never needs to import the Hub's provider payloads.
    private fun localLeagueMetadata(follows: SportsFollows): Map<String, League> {
        val ids = follows.leagues + follows.pins + follows.teams.map { it.leagueId }
        if ((SportsCatalog.extra.isNotEmpty() && ids.none { it.startsWith("tsdb/") && SportsCatalog.league(it) == null }) || ids.none { it.startsWith("taxonomy/") || it.startsWith("guide/") ||
                it == InternationalCricket.LEAGUE_ID || it.startsWith("tsdb/") && SportsCatalog.league(it) == null }) return emptyMap()
        val directory = File(context.cacheDir, "sports")
        val found = mutableMapOf<String, League>()
        val base = File(directory, "base-feed.json")
        if (base.length() in 1..SportsBaseFeed.MAX_BYTES) runCatching {
            base.source().buffer().use { source ->
                val reader = JsonReader.of(source)
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "metadata" -> {
                            reader.nextSource().use { section -> FreeSportsDb.leagues(section.readUtf8()).associateByTo(found) { it.id } }
                            break
                        }
                        "entries" -> break
                        else -> reader.skipValue()
                    }
                }
            }
        }
        for (id in ids.filter { it.matches(Regex("tsdb/[0-9]+")) && it !in found }) {
            val file = File(directory, "free_league_" + id.substringAfter('/') + ".json")
            if (file.length() in 1..256_000) runCatching { FreeSportsDb.leagues(file.readText().substringAfter('\n')).associateByTo(found) { it.id } }
        }
        return found
    }

    companion object {
        private const val REOPEN_FRESH_MS = 2 * 60_000L

        internal fun requestedLeagues(follows: SportsFollows, saved: Map<String, League>, settings: SportsSettings): List<League> {
            val ids = (follows.teams.flatMap { listOf(it.leagueId) + SportsCatalog.relatedLeagues(it.leagueId) } +
                follows.pins + follows.leagues).toSet()
            val catalog = (saved.values + SportsCatalog.all + CricketAustralia.leagues).associateBy { it.id }.toMutableMap()
            ids.filter { it.startsWith("tsdb/") && it !in catalog }.forEach { catalog[it] = League(it, Sport.OTHER, "Sport", "Sport") }
            return catalog.values.filter { league -> SportsTaxonomy.accepts(league, settings.excludedSportsNodes) &&
                (league.id in ids || ids.any { SportsTaxonomy.includesLeague(it, league.id, league) }) }
        }

        internal fun sportRows(events: List<SportsEvent>, leagues: Map<String, League>, follows: SportsFollows, settings: SportsSettings,
            matches: Map<String, List<String>>, names: Map<String, String>, window: CalendarWindow, zone: ZoneId, allSports: Boolean = false): List<CalendarItem> {
            val membership = mutableMapOf<String, Boolean>()
            fun league(event: SportsEvent) = leagues[event.leagueId] ?: SportsCatalog.league(event.leagueId)
            val byId = CalendarFixtureBroadcasts.withoutRaceDuplicates(events.distinctBy { it.id }, ::league)
            fun followed(event: SportsEvent) = follows.teams.filter { it.playsIn(event, league(event)) }
            fun wanted(event: SportsEvent) = membership.getOrPut(event.id) { isMine(event, follows, league(event)) }
            fun span(event: SportsEvent): LongRange {
                val first = event.startMs.calendarDay(zone)
                val multi = !event.hasTime || league(event)?.sport == Sport.CRICKET
                val last = if (multi) event.endMs?.takeIf { it > event.startMs }?.minus(1)?.calendarDay(zone) ?: first else first
                return first..last.coerceIn(first, first + 30)
            }
            val firstStart = LocalDate.ofEpochDay(window.first).atStartOfDay(zone).toInstant().toEpochMilli()
            val usable = byId.filter { e ->
                val days = span(e)
                val testInProgress = days.last < window.first && window.first - days.first <= 4 &&
                    e.source != EventSource.GUIDE && CalendarTestMatches.fixture(e, league(e)) != null
                val earlyGuide = e.source == EventSource.GUIDE && e.startMs in firstStart - CalendarFixtureBroadcasts.EARLY_COVERAGE_MS until firstStart
                days.first <= window.last && (days.last >= window.first || testInProgress || earlyGuide) &&
                    SportsTaxonomy.acceptsEvent(league(e), e.title, e.subtitle, settings.excludedSportsNodes) &&
                    !(e.state == GameState.OFF && e.status.orEmpty().lowercase().let { "cancel" in it || "postpon" in it || "abandon" in it })
            }
            val official = usable.filter { it.source != EventSource.GUIDE }
            val identities = SportsEventIdentity.Round()
            val espn = SportsFixtureIndex(official.filter { it.source == EventSource.ESPN })
            val real = official.filterNot { e -> e.source == EventSource.THESPORTSDB &&
                league(e)?.let { l -> espn.anyNear(e) { other -> league(other)?.let { identities.same(e, other, l, it) } == true } } == true }
            val preferred = CricketAustralia.merge(real.filter { it.source != EventSource.CRICKET_AUSTRALIA },
                real.filter { it.source == EventSource.CRICKET_AUSTRALIA })
            val tests = CalendarTestMatches.Fixtures(preferred, zone, ::league)
            val canonical = preferred.map(tests::canonical).distinctBy { it.id }
            val primary = canonical.filter { wanted(it) || (allSports && matches[it.id].orEmpty().any(names::containsKey)) }
            val primaryIds = primary.mapTo(HashSet()) { it.id }
            val fixtureGuide = CalendarFixtureBroadcasts(canonical, ::league, byId)
            val broadcasts = mutableMapOf<String, MutableMap<Long, MutableList<Pair<String, EpgProgram>>>>()
            val extra = mutableListOf<SportsEvent>()
            val liveAirings = usable.filter { it.source == EventSource.GUIDE }.mapNotNull { air ->
                val hits = air.guideHits.filter { (key, programme) -> key in names && SportsEventListings.liveCompetition(programme) }
                when { hits.isEmpty() -> null; hits.size == air.guideHits.size -> air; else -> air.copy(guideHits = hits) }
            }
            // A simulcast can carry a followed name or reminder on just one channel.
            val followedHits = if (allSports) emptySet() else liveAirings.filter(::wanted).flatMap { it.guideHits }.toHashSet()
            val guideGames = CalendarGuideMatchups.merge(CalendarTestMatches.merge(liveAirings, zone, ::league), ::league)
            val cards = guideGames.mapNotNull { game -> game.card?.let { game.event.id to it } }.toMap()
            val airings = CalendarLiveBroadcasts.merge(guideGames.map { it.event })
            val namedTests = airings.mapNotNull { air -> tests.named(air, league(air))?.let { air.id to it } }.toMap()
            val corroborated = airings.groupBy { it.startMs.calendarDay(zone) }.mapValues { (_, rows) ->
                rows.mapNotNullTo(mutableSetOf()) { namedTests[it.id]?.id }
            }
            for (air in airings) {
                val keepAiring = allSports || air.guideHits.any { it in followedHits }
                val date = air.startMs.calendarDay(zone)
                val test = namedTests[air.id] ?: tests.unnamed(air, league(air), corroborated[date].orEmpty())
                val fixtures = fixtureGuide.fixturesFor(air)
                if (test == null && fixtures.isEmpty()) {
                    if (keepAiring && !fixtureGuide.covers(air) && !CalendarTestMatches.anonymousTest(air, league(air))) extra += air
                    continue
                }
                for ((programme, hits) in air.guideHits.groupBy { it.second }) {
                    val match = if (test != null) test.takeIf { tests.liveSession(programme, it) }
                        else fixtureGuide.match(air, programme, fixtures)
                    if (match == null || !keepAiring && match.id !in primaryIds) continue
                    // Early coverage may start the previous evening. Keep it on the fixture's date.
                    val playDay = if (test != null) date else match.startMs.calendarDay(zone)
                    broadcasts.getOrPut(match.id) { mutableMapOf() }.getOrPut(playDay) { mutableListOf() }.addAll(hits)
                    if (match.id !in primaryIds && extra.none { it.id == match.id }) extra += match
                }
            }
            val result = mutableListOf<CalendarItem>()
            for (event in primary + extra) {
                val competition = league(event)
                val card = cards[event.id]
                val days = span(event)
                val roster = followed(event)
                val teams = roster.map { it.name }.distinct()
                val followedSides = event.sides.filter { side -> roster.any { it.matches(side.team) } }.map { it.team.key }
                val regionalChannel = matches[event.id]?.firstNotNullOfOrNull(names::get) ?:
                    event.broadcastCoverage.firstOrNull { FreeSportsDb.region(it.country) == SportsCatalog.regionOf(settings) }?.name ?:
                    event.broadcasts.firstOrNull()?.takeIf { SportsCatalog.regionOf(settings) == "US" }
                val eventDays = (maxOf(window.first, days.first)..minOf(window.last, days.last)).toMutableSet()
                val coverage = broadcasts[event.id].orEmpty()
                eventDays += coverage.keys.filter { it in window }
                for (d in eventDays) {
                    val hits = coverage[d].orEmpty().ifEmpty { event.guideHits }
                    val channel = hits.firstNotNullOfOrNull { names[it.first] } ?: regionalChannel
                    val title = card?.teams?.takeIf { it.isNotEmpty() }?.joinToString(" v ") ?: event.title
                    val fixture = if (title == event.title && (hits === event.guideHits || hits.isEmpty())) event
                        else event.copy(title = title, guideHits = hits.distinctBy { it.first to it.second.startMs })
                    result += CalendarItem("sport/" + event.id + "/" + d, "sports", title.take(240), d, CalendarKind.SPORT,
                        detail = channel.orEmpty(), channelCount = hits.distinctBy { it.first }.size,
                        matchDay = (d - days.first + 1).takeIf { days.first != days.last || d > days.first },
                        time = event.startMs.takeIf { event.hasTime && d == days.first }
                            ?: coverage[d]?.minOfOrNull { it.second.startMs },
                        contentId = event.id, followedTeam = teams.takeIf { it.isNotEmpty() }?.joinToString(" & "),
                        fixture = fixture, leagueName = card?.competition ?: competition?.name, followedSides = followedSides,
                        sport = competition?.sport, guideSides = card?.sides(event.leagueId).orEmpty())
                }
            }
            return result
        }
    }
}
