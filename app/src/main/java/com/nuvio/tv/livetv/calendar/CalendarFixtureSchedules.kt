package com.nuvio.tv.livetv.calendar

import android.content.Context
import android.util.AtomicFile
import com.nuvio.tv.livetv.sports.*
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
internal data class CalendarFixtureFile(val checkedAt: Long, val events: List<SportsSnapshotEvent>, val incomplete: Boolean = false)

internal data class CalendarFixtureResult(val key: String, val events: List<SportsEvent>, val failed: Boolean)

/** Calendar owns these public fixture lookups only while its destination is visible.
 * ESPN accepts individual months even for competitions that reject date ranges.
 */
@Singleton
internal class CalendarFixtureSchedules @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.cacheDir, "calendar-schedules")
    private val espn by lazy { SportsFeeds() }
    private val tsdb by lazy { FreeSportsDb() }
    private val cricket by lazy { CricketAustralia() }
    private val adapter by lazy { Moshi.Builder().build().adapter(CalendarFixtureFile::class.java) }

    private val slots = Semaphore(2)
    private val fallbackMonths = MutableStateFlow(emptySet<String>())
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private data class Query(val key: String, val monthly: Boolean, val load: suspend () -> List<SportsEvent>)

    fun dayLeagues(leagues: List<League>, day: Long, zone: ZoneId): Flow<List<League>> {
        val date = LocalDate.ofEpochDay(day)
        val months = dateStrings(date, zone, EASTERN, "yyyy-MM")
        return fallbackMonths.map { missing ->
            leagues.filter { league -> league.id.startsWith("tsdb/") || league.espnPath != null &&
                months.any { month -> "espn-" + league.id.replace('/', '_') + "-" + month in missing } }
        }.distinctUntilChanged()
    }

    private fun dateStrings(date: LocalDate, local: ZoneId, provider: ZoneId, pattern: String) =
        SportsDaySchedule.dates(date.atStartOfDay(local).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(local).toInstant().toEpochMilli(), provider, pattern).distinct()

    private fun fallback(query: Query, required: Boolean) {
        if (query.monthly) fallbackMonths.update { if (required) (it + query.key).toList().takeLast(64).toSet() else it - query.key }
    }

    fun fetch(leagues: List<League>, window: CalendarWindow, day: Long, zone: ZoneId, force: Boolean): Flow<CalendarFixtureResult> = channelFlow {
        val queries = queries(leagues, window, day, zone)
        val next = AtomicInteger()
        repeat(minOf(queries.size, 2)) {
            launch {
                while (true) {
                    val query = queries.getOrNull(next.getAndIncrement()) ?: break
                    val now = System.currentTimeMillis()
                    val file = AtomicFile(File(directory, query.key + ".json"))
                    val saved = read(file)
                    if (saved != null) send(CalendarFixtureResult(query.key, saved.events.map { it.event() }, false))
                    if (!force && saved != null && CalendarPolicy.fresh(saved.checkedAt, now) && query.key !in attempts.keys) {
                        fallback(query, saved.incomplete)
                        continue
                    }
                    if (!force && attempts[query.key]?.let { now - it in 0 until CalendarPolicy.RETRY_MS } == true) {
                        fallback(query, true)
                        send(CalendarFixtureResult(query.key, saved?.events?.map { it.event() }.orEmpty(), true))
                        continue
                    }
                    try {
                        val events = slots.withPermit { query.load() }.distinctBy { it.id }
                        val incomplete = query.monthly && events.size >= 1000
                        fallback(query, incomplete)
                        attempts.remove(query.key)
                        currentCoroutineContext().ensureActive()
                        try { persist(file, CalendarFixtureFile(now, events.map(SportsSnapshotEvent::from), incomplete)) } catch (_: java.io.IOException) { /* Keep the current screen usable when disk is full. */ }
                        send(CalendarFixtureResult(query.key, events, false))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        attempts.entries.removeAll { now - it.value >= CalendarPolicy.RETRY_MS }
                        attempts[query.key] = now
                        fallback(query, true)
                        send(CalendarFixtureResult(query.key, saved?.events?.map { it.event() }.orEmpty(), true))
                    }
                }
            }
        }
    }

    private fun queries(leagues: List<League>, window: CalendarWindow, day: Long, zone: ZoneId): List<Query> {
        val result = linkedMapOf<String, Query>()
        fun add(key: String, monthly: Boolean = false, load: suspend () -> List<SportsEvent>) { result.putIfAbsent(key, Query(key, monthly, load)) }
        val first = LocalDate.ofEpochDay(window.first).atStartOfDay(zone).toInstant()
        val end = LocalDate.ofEpochDay(window.last + 1).atStartOfDay(zone).toInstant().minusMillis(1)
        val chosen = LocalDate.ofEpochDay(day)
        val now = System.currentTimeMillis()
        for (league in leagues.sortedBy { it.id.startsWith("tsdb/") }) {
            val id = league.id.replace('/', '_')
            val providerZone = if (league.sport == Sport.CRICKET || league.id.startsWith("tsdb/")) UTC else EASTERN
            val months = generateSequence(YearMonth.from(first.atZone(providerZone))) { it.plusMonths(1) }
                .takeWhile { it <= YearMonth.from(end.atZone(providerZone)) }
            when {
                league.id.startsWith("ca/") -> {
                    val format = CricketAustralia.leagues.indexOfFirst { it.id == league.id } + 1
                    if (format > 0) for (month in months) add("cricket-${format}-$month") {
                        val anchor = month.atDay(6).atStartOfDay(UTC).toInstant().toEpochMilli()
                        CricketAustralia.parse(cricket.fixtures(format, anchor), now)
                    }
                }
                league.id == "cricket/19430" && leagues.any { it.id == "ca/test" } -> Unit
                league.espnPath != null && window.days == 1 -> for (date in dateStrings(chosen, zone, EASTERN, "yyyyMMdd")) {
                    add("espn-day-$id-$date") { SportsFeeds.parseScoreboard(league, espn.scoreboardDayRaw(league, date), strict = true) }
                }
                league.espnPath != null -> for (month in months) add("espn-${id}-$month", monthly = true) {
                    val raw = espn.scoreboardDayRaw(league, month.format(MONTH))
                    SportsFeeds.parseScoreboard(league, raw, strict = true)
                }
                league.fixtureSlug != null -> add("fixtures-$id") {
                    SportsFeeds.parseFixtures(league, espn.fixturesRaw(league.fixtureSlug))
                }
                league.id.startsWith("tsdb/") && window.days > 1 -> {
                    val today = now.calendarDay(zone)
                    val idNumber = league.id.removePrefix("tsdb/")
                    if (window.last >= today) add("upcoming-$id") {
                        FreeSportsDb.events(league, tsdb.get("eventsnextleague.php", "id" to idNumber), now)
                    }
                    if (window.last < today) add("past-$id") {
                        FreeSportsDb.events(league, tsdb.get("eventspastleague.php", "id" to idNumber), now)
                    }
                }
                league.id.startsWith("tsdb/") -> {
                    val dates = dateStrings(chosen, zone, UTC, "yyyy-MM-dd")
                    for (date in dates) add("tsdb-${id}-$date") {
                        FreeSportsDb.events(league, tsdb.get("eventsday.php", "d" to date, "l" to league.id.removePrefix("tsdb/")), now)
                    }
                }
            }
        }
        return result.values.toList()
    }

    @Synchronized private fun read(file: AtomicFile): CalendarFixtureFile? = runCatching {
        if (file.baseFile.length() in 1..MAX_BYTES) file.openRead().bufferedReader().use { adapter.fromJson(it.readText()) } else null
    }.getOrNull()

    @Synchronized private fun persist(file: AtomicFile, batch: CalendarFixtureFile) {
        val bytes = adapter.toJson(batch).toByteArray()
        if (bytes.size > MAX_BYTES) return
        directory.mkdirs()
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        var total = 0L
        directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }.forEachIndexed { index, f ->
            total += f.length()
            if (index >= 64 || total > 24L * 1024 * 1024) f.delete()
        }
    }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024
        private val UTC = ZoneId.of("UTC")
        private val EASTERN = ZoneId.of("America/New_York")
        private val MONTH = DateTimeFormatter.ofPattern("yyyyMM", java.util.Locale.ROOT)
    }
}
