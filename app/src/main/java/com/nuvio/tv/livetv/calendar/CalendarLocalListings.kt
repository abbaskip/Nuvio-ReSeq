package com.nuvio.tv.livetv.calendar

import android.content.Context
import com.nuvio.tv.core.storage.DatabaseVersions
import com.nuvio.tv.livetv.data.*
import com.nuvio.tv.livetv.multiview.liveTvChannelVisibility
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.sports.*
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    private data class Airings(val names: Map<String, String> = emptyMap(), val events: List<SportsEvent> = emptyList(),
        val loading: Boolean = false, val failed: Boolean = false)
    private data class Schedule(val events: List<SportsEvent>, val loading: Boolean, val failed: Boolean = false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sports(profile: Int, zone: ZoneId, window: CalendarWindow, day: StateFlow<Long>, force: Boolean, overview: Boolean = false): Flow<CalendarDisplay> =
        combine(prefs.get().sportsMineJson(profile), prefs.get().sportsSettingsJson) { raw, options ->
            SportsFollows.fromJson(raw) to SportsSettings.fromJson(options)
        }.distinctUntilChanged().flatMapLatest { (follows, settings) ->
            flow {
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
                val visited = mutableMapOf<Long, List<SportsEvent>>()
                val refreshDay = day.value
                val daily = day.flatMapLatest { date ->
                    schedules.get().dayLeagues(providers, date, zone).flatMapLatest { dated ->
                        schedule(dated, CalendarWindow(date, date), date, zone, force && date == refreshDay)
                    }.map { games ->
                        val events = if (games.loading) (games.events + visited[date].orEmpty()).distinctBy { it.id } else games.events
                        visited[date] = events
                        games.copy(events = visited.values.flatten())
                    }
                }
                val guideDays = if (overview) flowOf(day.value) else day
                val tv = guideDays.flatMapLatest { date ->
                    airings(guideLeagues, settings, date, zone, now, if (overview) window else CalendarWindow(date, date))
                }
                emitAll(combine(provider, daily, tv) { games, dated, guide ->
                    val rows = sportRows(games.events + dated.events + guide.events + saved.events, byId, follows, settings, saved.channels, guide.names, window, zone)
                    val status = when {
                        games.loading || dated.loading || guide.loading -> CalendarStatus.UPDATING
                        games.failed || dated.failed || guide.failed -> CalendarStatus.RETRY
                        else -> CalendarStatus.READY
                    }
                    CalendarDisplay(rows, status)
                }.distinctUntilChanged())
            }
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun airings(leagues: List<League>, sports: SportsSettings, day: Long, zone: ZoneId, now: Long, window: CalendarWindow): Flow<Airings> {
        if (!context.getDatabasePath(DatabaseVersions.guide.fileName).isFile) return flowOf(Airings())
        val preferences = prefs.get()
        return combine(channels.get().displayChannels, preferences.userState, preferences.settings, ParentalControls.unlocked) {
            all, user, settings, _ -> liveTvChannelVisibility(all, user, settings).visible.associateBy { it.key } to settings.epgOffsetMinutes
        }.distinctUntilChanged().transformLatest { (visible, shift) ->
            val names = visible.mapValues { it.value.name }
            if (names.isEmpty() || leagues.isEmpty()) { emit(Airings(names)); return@transformLatest }
            emit(Airings(names, loading = true))
            delay(180)
            val offset = shift * 60_000L
            fun midnight(epochDay: Long) = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
            val covered = withContext(BackgroundWork.reads) { guide.get().sportsGuideRange(midnight(window.first) - offset, midnight(window.last + 1) - offset) }
            if (covered == null) { emit(Airings(names)); return@transformLatest }
            val days = ((covered.first + offset).calendarDay(zone)..(covered.last + offset).calendarDay(zone)).sortedBy { kotlin.math.abs(it - day) }
            val events = mutableListOf<SportsEvent>()
            for (date in days) {
                val batch = withContext(BackgroundWork.reads) {
                    val job = currentCoroutineContext()
                    guideReads.withLock {
                        matcher.guideEvents(midnight(date), midnight(date + 1), leagues, emptyList(), visible, offset, now, selection = sports,
                            liveCompetitionsOnly = true, checkCancelled = { job.ensureActive() })
                    }
                }
                events += SportsGuideEvents.merge(batch, emptySet())
                emit(Airings(names, events.toList()))
            }
        }.onStart { emit(Airings(loading = true)) }.catch { e ->
            if (e is CancellationException) throw e
            emit(Airings(failed = true))
        }
    }

    // A synced follow can refer to a TheSportsDB league added by the shared catalogue. Read only
    // its small metadata section. Calendar never needs to import the Hub's provider payloads.
    private fun localLeagueMetadata(follows: SportsFollows): Map<String, League> {
        val ids = follows.leagues + follows.pins + follows.teams.map { it.leagueId }
        if (SportsCatalog.extra.isNotEmpty() || ids.none { it.startsWith("taxonomy/") || it.startsWith("guide/") ||
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
        internal fun requestedLeagues(follows: SportsFollows, saved: Map<String, League>, settings: SportsSettings): List<League> {
            val ids = (follows.teams.flatMap { listOf(it.leagueId) + SportsCatalog.relatedLeagues(it.leagueId) } +
                follows.pins + follows.leagues).toSet()
            val catalog = (saved.values + SportsCatalog.all + CricketAustralia.leagues).associateBy { it.id }.toMutableMap()
            ids.filter { it.startsWith("tsdb/") && it !in catalog }.forEach { catalog[it] = League(it, Sport.OTHER, "Sport", "Sport") }
            return catalog.values.filter { league -> SportsTaxonomy.accepts(league, settings.excludedSportsNodes) &&
                (league.id in ids || ids.any { SportsTaxonomy.includesLeague(it, league.id, league) }) }
        }

        internal fun sportRows(events: List<SportsEvent>, leagues: Map<String, League>, follows: SportsFollows, settings: SportsSettings,
            matches: Map<String, List<String>>, names: Map<String, String>, window: CalendarWindow, zone: ZoneId): List<CalendarItem> {
            val byId = events.distinctBy { it.id }
            val leagueFollows = mutableMapOf<String, Boolean>()
            fun league(event: SportsEvent) = leagues[event.leagueId] ?: SportsCatalog.league(event.leagueId)
            fun followed(event: SportsEvent) = follows.teams.filter { it.playsIn(event, league(event)) }
            fun wanted(event: SportsEvent) = leagueFollows.getOrPut(event.leagueId) { follows.followsLeague(event.leagueId, league(event)) } ||
                followed(event).isNotEmpty()
            fun span(event: SportsEvent): LongRange {
                val first = event.startMs.calendarDay(zone)
                val multi = !event.hasTime || league(event)?.sport == Sport.CRICKET
                val last = if (multi) event.endMs?.takeIf { it > event.startMs }?.minus(1)?.calendarDay(zone) ?: first else first
                return first..last.coerceIn(first, first + 30)
            }
            val usable = byId.filter { e ->
                val days = span(e)
                days.first <= window.last && days.last >= window.first &&
                    SportsTaxonomy.acceptsEvent(league(e), e.title, e.subtitle, settings.excludedSportsNodes) &&
                    !(e.state == GameState.OFF && e.status.orEmpty().lowercase().let { "cancel" in it || "postpon" in it || "abandon" in it })
            }
            val official = usable.filter { it.source != EventSource.GUIDE }
            val reported = official.filter { wanted(it) || matches[it.id].orEmpty().any(names::containsKey) }
            val identities = SportsEventIdentity.Round()
            val espn = SportsFixtureIndex(reported.filter { it.source == EventSource.ESPN })
            val real = reported.filterNot { e -> e.source == EventSource.THESPORTSDB &&
                league(e)?.let { l -> espn.anyNear(e) { other -> league(other)?.let { identities.same(e, other, l, it) } == true } } == true }
            val primary = CricketAustralia.merge(real.filter { it.source != EventSource.CRICKET_AUSTRALIA },
                real.filter { it.source == EventSource.CRICKET_AUSTRALIA })
            val broadcasts = mutableMapOf<Pair<String, Long>, String>()
            val extra = mutableListOf<SportsEvent>()
            for (air in usable.filter { it.source == EventSource.GUIDE }) {
                val hit = air.guideHits.firstOrNull { (key, p) -> key in names && SportsEventListings.liveCompetition(p) } ?: continue
                val match = (primary.asSequence() + official.asSequence()).firstOrNull { e -> league(e)?.let { l -> l.sport == league(air)?.sport &&
                    SportsEventIdentity.guideCoversFixture(air, e, l) } == true }
                if (match != null) {
                    broadcasts.putIfAbsent(match.id to air.startMs.calendarDay(zone), names.getValue(hit.first))
                    if (match !in primary && extra.none { it.id == match.id }) extra += match
                }
                else { extra += air; broadcasts[air.id to air.startMs.calendarDay(zone)] = names.getValue(hit.first) }
            }
            val result = mutableListOf<CalendarItem>()
            for (event in primary + extra) {
                val competition = league(event)
                val days = span(event)
                val roster = followed(event)
                val teams = roster.map { it.name }.distinct()
                val followedSides = event.sides.filter { side -> roster.any { it.matches(side.team) } }.map { it.team.key }
                val regionalChannel = matches[event.id]?.firstNotNullOfOrNull(names::get) ?:
                    event.broadcastCoverage.firstOrNull { FreeSportsDb.region(it.country) == SportsCatalog.regionOf(settings) }?.name ?:
                    event.broadcasts.firstOrNull()?.takeIf { SportsCatalog.regionOf(settings) == "US" }
                for (d in maxOf(window.first, days.first)..minOf(window.last, days.last)) {
                    val channel = broadcasts[event.id to d] ?: regionalChannel
                    val note = if (days.first != days.last) "Day " + (d - days.first + 1) + " · " else ""
                    result += CalendarItem("sport/" + event.id + "/" + d, "sports", event.title.take(240), d, CalendarKind.SPORT,
                        detail = note + (channel ?: "Channel TBC"), time = event.startMs.takeIf { event.hasTime && d == days.first },
                        contentId = event.id, followedTeam = teams.takeIf { it.isNotEmpty() }?.joinToString(" & "),
                        fixture = event, leagueName = competition?.name, followedSides = followedSides)
                }
            }
            return result
        }
    }
}
