package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class SportsLongLiveMatchingTest {
    private val now = 1_794_312_000_000L
    private val hour = 3_600_000L
    private val channel = LiveChannel("505", "playlist", "Test", "AU: Fox Sports 505 HD",
        null, null, null, "sports", "Sports", 1, "https://example.invalid")
    private fun cricket(): SportsEvent = SportsFeeds.parseScoreboard(SportsCatalog.league("cricket/19430")!!,
        requireNotNull(javaClass.getResourceAsStream("/sports/pakistan-sri-lanka-test.json"))
            .bufferedReader().use { it.readText() }).single().copy(startMs = now - 30 * hour, endMs = now + 70 * hour)
    private fun fixture(sport: Sport): SportsEvent {
        val league = SportsCatalog.leagues.first { it.sport == sport }
        fun side(name: String, home: Boolean) = Side(TeamInfo(league.id, name, name, name, name.take(3),
            null, null, null, null), "3", null, false, home)
        return SportsEvent("live-${sport.name}", league.id, now - 5 * hour, true, GameState.LIVE,
            "Ronnie O'Sullivan v Judd Trump", null, null, side("Judd Trump", false), side("Ronnie O'Sullivan", true),
            endMs = now + 3 * hour)
    }
    private fun input(event: SportsEvent, db: EpgDatabase, shift: Long = 0) =
        SportsMatcher(db).match(SportsMatcher.Input(listOf(event), listOf(channel), emptyMap(), shift,
            SportsSettings(region = "AU"), now)).matches[event.id].orEmpty()

    @Test fun fox505DayTwoListingMatchesAfterHoursOnAirUsingTheRealQueryBounds() {
        fun side(name: String, abbr: String, home: Boolean) = Side(
            TeamInfo("ca/shield", abbr, name, name, abbr, null, null, null, null), null, null, false, home)
        val event = cricket().copy(leagueId = "ca/shield", title = "Victoria v Western Australia",
            home = side("Victoria", "VIC", true), away = side("Western Australia", "WA", false))
        val programme = EpgProgram(now - 6 * hour, now + hour,
            "Sheffield Shield : VIC v WA Day 2 P2 ᴸᶦᵛᵉ", "Red ball cricket.")
        val db = mockk<EpgDatabase>()
        every { db.findByWords(any(), any(), any(), any(), any(), any()) } answers {
            // The earlier test returned a row regardless of SQL bounds, hiding this miss.
            val from = firstArg<Long>(); val to = secondArg<Long>(); val stop = thirdArg<Long>()
            if (programme.startMs in from until to && programme.stopMs > stop) listOf(channel.key to programme) else emptyList()
        }
        assertTrue(input(event, db).any { it.sure && it.program == programme })
        verify { db.findByWords(match { it >= now - 30 * hour }, any(), now, any(), any(), any()) }

        // A future fixture first in the catalogue must not hold the live result until the full pass ends.
        val future = event.copy(id = "tomorrow", state = GameState.PRE, startMs = now + 24 * hour)
        val request = SportsMatcher.Input(listOf(future, event), listOf(channel), emptyMap(), 0, SportsSettings(), now)
        val expected = SportsMatcher(db).match(request).matches
        var published = false
        every { db.findByWords(any(), any(), any(), any(), any(), any()) } answers {
            if (firstArg<Long>() > now) {
                assertTrue("Live channels must be published before querying tomorrow", published)
                emptyList()
            } else listOf(channel.key to programme)
        }
        for (focused in listOf(null, event.id)) {
            published = false
            val result = SportsMatcher(db).matchProgressively(request, focused, { partial ->
                assertEquals(programme, partial.matches[event.id]!!.single().program)
                published = true
            })
            assertTrue(published)
            assertEquals(expected, result.matches)
        }
    }

    @Test fun snookerAndTennisRetainLongLiveSessionsIncludingTheFinalMinutes() {
        for (sport in listOf(Sport.SNOOKER, Sport.TENNIS)) {
            val event = fixture(sport)
            val label = if (sport == Sport.SNOOKER) "Snooker" else sport.label
            val p = EpgProgram(now - 7 * hour, now + 60_000, "Live: $label: Ronnie O'Sullivan vs Judd Trump")
            val db = mockk<EpgDatabase>()
            every { db.findByWords(any(), any(), any(), any(), any(), any()) } returns listOf(channel.key to p)
            assertTrue(sport.name, input(event, db).any { it.sure && it.program == p })
        }
    }

    @Test fun preloadedProgrammesUseNoExtraGuideQueryAndDoNotApplyTheOffsetTwice() {
        val event = cricket()
        val p = EpgProgram(now - 8 * hour, now + hour, "Test: Pakistan vs Sri Lanka 1st Test Day 2 live")
        val db = mockk<EpgDatabase>()
        val result = SportsMatcher(db).match(SportsMatcher.Input(listOf(event), listOf(channel), emptyMap(), hour,
            SportsSettings(), now, includeGuide = false, currentGuidePrograms = listOf(channel.key to p)))
        assertEquals(p, result.matches[event.id]!!.single { it.sure }.program)
        verify { db wasNot Called }
    }

    @Test fun longSessionExceptionStillRejectsReplaysWrongOpponentsLoopsAndEndedProgrammes() {
        val event = cricket()
        val db = mockk<EpgDatabase>()
        val programmes = listOf(
            EpgProgram(now - 8 * hour, now + hour, "Replay: Test Pakistan vs Sri Lanka Day 2"),
            EpgProgram(now - 8 * hour, now + hour, "Recorded coverage: Test Pakistan vs Sri Lanka Day 2"),
            EpgProgram(now - 8 * hour, now + hour, "Test: Pakistan vs Sri Lanka live", "Highlights from yesterday"),
            EpgProgram(now - 8 * hour, now + hour, "Live: Test Australia vs England"),
            EpgProgram(now - 31 * hour, now + hour, "Live: Test Pakistan vs Sri Lanka"),
            EpgProgram(now - 8 * hour, now, "Live: Test Pakistan vs Sri Lanka"))
        for (p in programmes) {
            val result = SportsMatcher(db).match(SportsMatcher.Input(listOf(event), listOf(channel), emptyMap(), 0,
                SportsSettings(), now, includeGuide = false, currentGuidePrograms = listOf(channel.key to p)))
            assertTrue(p.title, result.matches[event.id].orEmpty().none { it.sure && it.program != null })
        }
    }

    @Test fun golfMotorsportAndCyclingTournamentSessionsAlsoMatchTheirOwnEvent() {
        for ((sport, name) in listOf(Sport.GOLF to "Hero World Challenge", Sport.RACING to "Singapore Grand Prix",
            Sport.CYCLING to "Tour de France")) {
            val league = SportsCatalog.leagues.first { it.sport == sport }
            val event = SportsEvent("long-${sport.name}", league.id, now - 48 * hour, false, GameState.LIVE,
                name, null, null, null, null, endMs = now + 24 * hour)
            val p = EpgProgram(now - 8 * hour, now + hour, "Live: ${sport.label}: ${league.guideWords.first()}: $name")
            val db = mockk<EpgDatabase>()
            val result = SportsMatcher(db).match(SportsMatcher.Input(listOf(event), listOf(channel), emptyMap(), 0,
                SportsSettings(), now, includeGuide = false, currentGuidePrograms = listOf(channel.key to p)))
            assertTrue("$sport: ${result.summary}", result.matches[event.id].orEmpty().any { it.sure && it.program == p })
            verify { db wasNot Called }
        }
    }

    @Test fun oneCurrentReadAlsoFindsParticipantOnlyListingsWithoutScanningTheFullGuide() {
        val event = cricket()
        val p = EpgProgram(now - 8 * hour, now + hour, "Pakistan vs Sri Lanka", "Live from Rawalpindi")
        val db = mockk<EpgDatabase> {
            every { fingerprint() } returns "guide"
            every { contentRevision() } returns "1"
            every { visitCurrentPrograms(now, any(), any()) } answers {
                thirdArg<(String, EpgProgram) -> Unit>().invoke(channel.key, p)
                thirdArg<(String, EpgProgram) -> Unit>().invoke("food", p.copy(title = "MasterChef", description = null))
            }
        }
        val channels = listOf(channel, channel.copy(key = "food")).associateBy { it.key }
        val matcher = SportsMatcher(db)
        val snapshot = matcher.tvNowSnapshot(SportsCatalog.all, emptyList(), channels, 0, now, listOf(event))
        assertEquals(listOf(channel.key to p), snapshot.fixturePrograms)
        val matched = matcher.match(SportsMatcher.Input(listOf(event), listOf(channel), emptyMap(), 0, SportsSettings(), now,
            includeGuide = false, currentGuidePrograms = snapshot.fixturePrograms)).matches
        val extra = SportsCurrentGuide.additionalAirings(emptyList(), listOf(event), matched, now)
        assertEquals(listOf(channel.key to p), extra.single().guideHits)
        assertTrue(SportsCurrentGuide.withFixtures(extra, listOf(event), matched, now).single().hasScore)
        verify(exactly = 1) { db.visitCurrentPrograms(now, any(), any()) }
        verify(exactly = 0) { db.visitPrograms(any(), any(), any()) }
        verify(exactly = 0) { db.findByWords(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun largeCurrentGuideRetainsOnlyFixtureCandidatesAndUsesTheSameSingleRead() {
        val event = cricket()
        val p = EpgProgram(now - 8 * hour, now + hour, "Pakistan vs Sri Lanka", "Live from Rawalpindi")
        val channels = (0 until 20_000).associate { i -> "noise-$i" to channel.copy(key = "noise-$i") } + (channel.key to channel)
        val db = mockk<EpgDatabase> {
            every { fingerprint() } returns "large-guide"
            every { contentRevision() } returns "1"
            every { visitCurrentPrograms(now, any(), any()) } answers {
                val visit = thirdArg<(String, EpgProgram) -> Unit>()
                repeat(20_000) { visit("noise-$it", p.copy(title = "Daily programme $it", description = null)) }
                visit(channel.key, p)
            }
        }
        val matcher = SportsMatcher(db)
        fun measure(extra: Boolean): Long {
            val start = System.nanoTime()
            if (extra) assertEquals(listOf(channel.key to p),
                matcher.tvNowSnapshot(SportsCatalog.all, emptyList(), channels, 0, now, listOf(event)).fixturePrograms)
            else matcher.tvNowEvents(SportsCatalog.all, emptyList(), channels, 0, now)
            return (System.nanoTime() - start) / 1_000_000
        }
        // Record comparable host timings without imposing a flaky wall-clock test threshold.
        measure(false); measure(true)
        val before = List(3) { measure(false) }.sorted()[1]
        val after = List(3) { measure(true) }.sorted()[1]
        println("Current-guide host benchmark: 20001 rows; previous median=${before}ms; fixture snapshot median=${after}ms")
        verify(exactly = 8) { db.visitCurrentPrograms(now, any(), any()) }
        verify(exactly = 0) { db.visitPrograms(any(), any(), any()) }
        verify(exactly = 0) { db.findByWords(any(), any(), any(), any(), any(), any()) }
    }
}
