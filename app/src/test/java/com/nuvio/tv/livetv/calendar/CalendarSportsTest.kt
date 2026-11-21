package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.core.storage.DatabaseVersions
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.sports.*
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalendarSportsTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.now(zone)
    private val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val country = FollowedTeam(InternationalCricket.LEAGUE_ID, "australia", "Australia", "AUS", null, null)
    private val raw = MutableStateFlow<String?>(null)
    private val context = RuntimeEnvironment.getApplication()
    private val cache = SportsFixtureCache(context)
    private val prefs = mockk<LiveTvPreferences> {
        every { sportsMineJson(7) } returns raw
        every { sportsSettingsJson } returns flowOf(null)
    }
    private val schedules = mockk<CalendarFixtureSchedules> {
        every { fetch(any(), any(), any(), any(), any()) } returns emptyFlow()
        every { dayLeagues(any(), any(), any()) } returns flowOf(emptyList())
    }
    private suspend fun rows(day: LocalDate = today, refresh: Boolean = false) = local.sports(7, zone,
        CalendarWindow.week(day, java.util.Locale.UK), MutableStateFlow(day.toEpochDay()), refresh)
        .first { it.status == CalendarStatus.READY }.items
    private val local = CalendarLocalListings(context, Lazy { prefs }, Lazy { error("No channel load") },
        Lazy { error("No TV-guide load") }, Lazy { cache }, Lazy { schedules })

    private fun fixture(id: String, league: String, offset: Long, name: String? = null, title: String = id): SportsEvent {
        val away = name?.let { Side(TeamInfo(league, "provider-$id", it, it, "", null, null, null, null), null, null, false, false) }
        return SportsEvent(id, league, noon + offset, true, GameState.PRE, title, null, null, away, null)
    }

    @Test fun countryAcrossFormatsAndWholeLeagueMergeOnceChronologicallyWithTeamsFirstOnlyAtEqualTimes() = runBlocking {
        val test = "cricket/19430"
        val odi = "taxonomy/cricket.odi-one-day.odi-internationals"
        val t20 = "taxonomy/cricket.t20-internationals.t20-internationals"
        val league = fixture("same-time-league", test, 2, "England")
        val australia = fixture("odi", odi, 2, "Australia ODI")
        cache.known = MutableStateFlow(listOf(league, fixture("test", test, 3, "Australia Test"), australia,
            fixture("t20", t20, 4, title = "India vs. Australia (T20I)"), fixture("earlier", test, 1, "India"),
            fixture("domestic", "cricket/8048", 0, "Australia A"), fixture("women", t20, 1, "Australia Women"),
            fixture("football", "soccer/fifa.world", 0, "Australia"), australia))
        raw.value = SportsFollows(teams = listOf(country), pins = listOf(test)).toJson()
        val matched = rows()
        val displayed = CalendarPolicy.displayed(matched, CalendarWindow.around(today), today.toEpochDay())
        assertEquals(listOf("earlier", "odi", "same-time-league", "test", "t20"), displayed.map { it.contentId })
        assertEquals(listOf(null, "Australia", null, "Australia", "Australia"), displayed.map { it.followedTeam })

        // The next visit reuses the fixture data with today's follows, even while release dates are fresh.
        raw.value = """{"leagues":["cricket/19430"]}"""
        assertEquals(setOf("earlier", "test", "same-time-league"), rows().map { it.contentId }.toSet())
        assertTrue(rows().all { it.followedTeam == null })
        raw.value = SportsFollows().toJson()
        assertTrue(rows().isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun overviewReusesCompletedDaysAndOnlyFinishesChecksForDaysActuallyVisited() = runTest {
        val nba = SportsCatalog.league("basketball/nba")!!
        val first = today.toEpochDay()
        val selected = MutableStateFlow(first)
        val dated = MutableStateFlow(listOf(nba))
        val calls = mutableListOf<Pair<Long, Boolean>>()
        every { schedules.dayLeagues(any(), any(), zone) } returns dated
        every { schedules.fetch(any(), match { it.days == 1 }, any(), zone, any()) } answers {
            val date = thirdArg<Long>()
            val force = arg<Boolean>(4)
            flow {
                calls += date to force
                kotlinx.coroutines.delay(300)
                val events = if (date == first) listOf(fixture("home", nba.id, 0)) else emptyList()
                emit(CalendarFixtureResult("day", events, false))
            }
        }
        raw.value = SportsFollows(leagues = setOf(nba.id)).toJson()
        val displays = mutableListOf<CalendarDisplay>()
        backgroundScope.launch {
            local.sports(7, zone, CalendarWindow(first, first + 6), selected, true, overview = true).toList(displays)
        }
        suspend fun stay(date: Long, ms: Long = 600) {
            selected.value = date
            advanceTimeBy(ms)
            runCurrent()
        }
        stay(first)
        assertEquals(listOf("home"), displays.last().items.map { it.contentId })
        displays.clear()
        stay(first + 1)
        stay(first)
        stay(first + 1)
        // Moving past a column quickly doesn't start its fallback. An interrupted check can resume on a revisit.
        stay(first + 2, 50)
        stay(first + 3, 220)
        stay(first)
        stay(first + 3)
        assertEquals(listOf(first to true, (first + 1) to false, (first + 3) to false, (first + 3) to false), calls)
        assertTrue(displays.all { it.status == CalendarStatus.READY && it.items.single().contentId == "home" })
        dated.value = listOf(nba, SportsCatalog.league("soccer/eng.1")!!)
        stay(first + 3)
        assertEquals(5, calls.size)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun reopeningSportsReusesCardsAndManualRefreshKeepsThemUntilReplacement() = runTest {
        val league = "basketball/nba"
        val first = fixture("previous", league, 0)
        val replacement = fixture("updated", league, 0)
        val day = today.toEpochDay()
        val window = CalendarWindow(day, day + 6)
        val selected = MutableStateFlow(day)
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        raw.value = SportsFollows(leagues = setOf(league)).toJson()
        every { schedules.fetch(any(), any(), any(), any(), any()) } answers {
            flow {
                calls++
                val next = if (calls == 1) first else { finish.await(); replacement }
                emit(CalendarFixtureResult("nba", listOf(next), false))
            }
        }
        fun visit(force: Boolean = false) = local.sports(7, zone, window, selected, force)
        assertEquals("previous", visit().first { it.status == CalendarStatus.READY }.items.single().contentId)
        val reopened = mutableListOf<CalendarDisplay>()
        val warm = backgroundScope.launch { visit().toList(reopened) }
        runCurrent()
        assertEquals(CalendarStatus.READY, reopened.single().status)
        assertEquals("previous", reopened.single().items.single().contentId)
        assertEquals(1, calls)
        warm.cancel()
        val frames = mutableListOf<CalendarDisplay>()
        val refresh = backgroundScope.launch { visit(true).toList(frames) }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(frames.isNotEmpty() && frames.all { it.items.single().contentId == "previous" })
        finish.complete(Unit)
        runCurrent()
        assertEquals("updated", frames.last().items.single().contentId)
        refresh.cancel()
        raw.value = SportsFollows().toJson()
        assertTrue(visit().first().items.isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun reopeningAnUnfinishedMonthShowsPartialCardsUntilAnEmptyScheduleIsConfirmed() = runTest {
        val league = "basketball/nba"
        val day = MutableStateFlow(today.toEpochDay())
        val month = CalendarWindow.month(today)
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        raw.value = SportsFollows(leagues = setOf(league)).toJson()
        every { schedules.fetch(any(), any(), any(), any(), any()) } answers {
            flow {
                if (++calls == 1) emit(CalendarFixtureResult("nba", listOf(fixture("partial", league, 0)), false))
                finish.await()
                emit(CalendarFixtureResult("nba", emptyList(), false))
            }
        }
        fun visit() = local.sports(7, zone, month, day, false, overview = true)
        visit().first { it.status == CalendarStatus.UPDATING && it.items.isNotEmpty() }
        val frames = mutableListOf<CalendarDisplay>()
        backgroundScope.launch { visit().toList(frames) }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(frames.isNotEmpty() && frames.all { it.items.single().contentId == "partial" })
        finish.complete(Unit)
        runCurrent()
        assertEquals(CalendarDisplay(emptyList(), CalendarStatus.READY), frames.last())
    }

    @Test fun saturdayFixturesLoadWithoutAnyHubSnapshotAndRefreshFetchesIndependently() = runBlocking {
        val saturday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY))
        val id = "basketball/nba"
        val event = fixture("saturday", id, 0, "Boston").copy(startMs = saturday.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
        val calls = mutableListOf<Boolean>()
        every { schedules.fetch(match { batch -> batch.any { it.id == id && it.espnPath != null } },
            any(), saturday.toEpochDay(), zone, capture(calls)) } returns flowOf(CalendarFixtureResult("nba-month", listOf(event), false))
        raw.value = SportsFollows(leagues = setOf(id)).toJson()
        assertEquals("", rows(saturday).single().detail)
        assertEquals("saturday", rows(saturday, refresh = true).single().contentId)
        assertEquals(listOf(false, true), calls)
        val shell = League(id, Sport.BASKETBALL, "NBA", "NBA")
        val restored = CalendarLocalListings.requestedLeagues(SportsFollows(leagues = setOf(id)), mapOf(id to shell), SportsSettings())
        assertNotNull(restored.single { it.id == id }.espnPath)
    }

    @Test fun aSuccessfulEmptyMonthIsCachedAndAFailedMonthFallsBackToBothProviderDates() = runBlocking {
        val saturday = LocalDate.of(2031, 11, 22)
        val month = CalendarWindow.month(saturday)
        val day = saturday.toEpochDay()
        val nba = SportsCatalog.league("basketball/nba")!!
        val sources = listOf(nba)
        mockkConstructor(SportsFeeds::class)
        try {
            coEvery { anyConstructed<SportsFeeds>().scoreboardDayRaw(nba, any()) } returns """{"events":[]}"""
            val first = CalendarFixtureSchedules(context)
            first.fetch(sources, month, day, zone, true).toList()
            val reopened = CalendarFixtureSchedules(context)
            reopened.fetch(sources, month, day, zone, false).toList()
            coVerify(exactly = 1) { anyConstructed<SportsFeeds>().scoreboardDayRaw(nba, "203111") }
            coEvery { anyConstructed<SportsFeeds>().scoreboardDayRaw(nba, "203111") } throws java.io.IOException("Month not supported")
            assertTrue(reopened.fetch(sources, month, day, zone, true).toList().any { it.failed })
            assertEquals(sources, reopened.dayLeagues(sources, day, zone).first())
            reopened.fetch(sources, CalendarWindow(day, day), day, zone, true).toList()
            coVerify(exactly = 1) { anyConstructed<SportsFeeds>().scoreboardDayRaw(nba, "20311121") }
            coVerify(exactly = 1) { anyConstructed<SportsFeeds>().scoreboardDayRaw(nba, "20311122") }
        } finally { unmockkConstructor(SportsFeeds::class) }
    }

    @Test fun weekWithNoFollowsReadsOnlyPublishedLiveCompetitionsAcrossBothLocalDates() = runBlocking {
        val db = EpgDatabase(context)
        val directory = context.getDatabasePath(DatabaseVersions.guide.fileName)
        val friday = LocalDate.of(2026, 11, 20)
        val day = friday.toEpochDay()
        fun at(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        fun programme(d: LocalDate, title: String) = EpgProgram(at(d), at(d) + 3_600_000, title)
        val channel = LiveChannel("sports", "p", "P", "Sports", null, null, null, "sports", "Sports", 1, "https://example.invalid")
        val store = mockk<LiveTvChannelStore> { every { displayChannels } returns MutableStateFlow(listOf(channel)) }
        every { prefs.userState } returns flowOf(LiveUserState())
        every { prefs.settings } returns flowOf(LiveTvSettings())
        raw.value = SportsFollows().toJson()
        try {
            db.replaceAll("calendar-week", mapOf(channel.key to listOf(
                programme(friday, "Live: Formula 1: Las Vegas Grand Prix Practice"),
                programme(friday.plusDays(1), "Live: Formula 1: Las Vegas Grand Prix Qualifying"),
                programme(friday.plusDays(2), "Formula 1: Las Vegas Grand Prix Highlights")
            )), emptyMap())
            assertTrue(directory.isFile)
            assertNull(db.sportsGuideRange(at(friday.plusDays(3)), at(friday.plusDays(4))))
            val from = friday.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = friday.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()
            val inserted = db.readableDatabase.rawQuery("SELECT start, stop FROM programs ORDER BY start", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
            }
            assertEquals("Fixture timestamps in the database ($from, $until): $inserted", at(friday)..at(friday.plusDays(2)), db.sportsGuideRange(from, until))
            val live = mutableListOf<String>()
            db.visitSportsPrograms(from, until, Long.MIN_VALUE, listOf("live")) { _, p -> live += p.title }
            assertEquals("Indexed live listings", 2, live.size)
            val games = SportsMatcher(db).guideEvents(from, until, SportsCatalog.all, emptyList(), mapOf(channel.key to channel),
                0, from, liveCompetitionsOnly = true)
            assertEquals("Classified competitions: " + games.map { it.title }, 2, games.size)
            val f1 = requireNotNull(SportsCatalog.league("racing/f1"))
            val official = games.mapIndexed { i, air -> air.copy(id = "official-" + i, leagueId = f1.id,
                source = EventSource.ESPN, startMs = air.startMs + 5 * 60_000L, guideHits = emptyList()) }
            every { schedules.fetch(match { selected -> selected.any { it.id == f1.id } }, any(), any(), any(), any()) } returns
                flowOf(CalendarFixtureResult("f1", official, false))
            val source = CalendarLocalListings(context, Lazy { prefs }, Lazy { store }, Lazy { db }, Lazy { cache }, Lazy { schedules })
            var last = CalendarDisplay()
            val result = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                source.sports(7, zone, CalendarWindow(day, day + 6), MutableStateFlow(day), false, overview = true, allSports = true)
                    .first { rows -> last = rows; rows.items.map { it.day }.toSet().size == 2 }
            } ?: throw AssertionError("Last Calendar state " + last.status + ", items " + last.items.map { it.title })
            assertEquals(setOf(day, day + 1), result.items.map { it.day }.toSet())
            assertEquals(official.map { it.id }.toSet(), result.items.map { it.contentId }.toSet())
            assertTrue(result.items.all { it.kind == CalendarKind.SPORT && it.detail == channel.name })
        } finally { db.close(); context.deleteDatabase(DatabaseVersions.guide.fileName) }
    }

    @Test fun aNewProcessUsesTheSavedHubScheduleWithCurrentCountryFollow() = runBlocking {
        val file = File(context.cacheDir, "sports/page-snapshot.json")
        val match = fixture("disk-odi", "taxonomy/cricket.odi-one-day.odi-internationals", 2, title = "England v Australia")
        val now = System.currentTimeMillis()
        try {
            SportsPageSnapshot.write(file, listOf(match), emptyMap(), now, noon - 86_400_000, noon + 86_400_000) { true }
            raw.value = SportsFollows(teams = listOf(country)).toJson()
            val row = rows().single()
            assertEquals("disk-odi", row.contentId)
            assertEquals("Australia", row.followedTeam)
        } finally { file.delete() }
    }
}
