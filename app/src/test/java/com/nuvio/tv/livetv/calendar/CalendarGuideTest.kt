package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.sports.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneId

class CalendarGuideTest {
    @get:Rule val temp = TemporaryFolder()
    private val zone = ZoneId.of("Australia/Sydney")
    private val saturday = LocalDate.of(2026, 11, 21)
    private val day = saturday.toEpochDay()
    private val noon = saturday.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val nba = SportsCatalog.league("basketball/nba")!!
    private fun side(name: String) = Side(TeamInfo(nba.id, name, name, name, name, null, null, null, null), null, null, false, false)
    private val game = SportsEvent("nba-test", nba.id, noon, true, GameState.PRE, "Boston Celtics at Toronto Raptors", null, null,
        side("Boston Celtics"), side("Toronto Raptors"))
    private fun airing(title: String, channel: String = "visible") = game.copy(id = title, source = EventSource.GUIDE,
        title = title, home = null, away = null, guideHits = listOf(channel to EpgProgram(noon, noon + 7_200_000, title)))

    @Test fun liveCompetitionRequiresALiveMarkerAndAnEvent() {
        fun live(title: String, description: String? = null) = SportsEventListings.liveCompetition(EpgProgram(noon, noon + 1000, title, description))
        assertTrue(live("NBA: Celtics @ Raptors", "Live coverage from Boston"))
        assertTrue(live("LIVE: Formula 1 Grand Prix Qualifying"))
        assertTrue(live("Live Cricket: India v Australia"))
        assertFalse(live("As live: India v New Zealand"))
        assertFalse(live("Live: Women's Big Bash", "A tape-delayed broadcast of today's match"))
        assertTrue(live("Live: WWE SmackDown"))
        assertFalse(live("Live at the studio"))
        for (title in listOf("NBA Today", "Live: NBA Pregame", "Live: NBA Highlights", "NFL Live", "Live: World Cup Preview",
            "Replay: Celtics v Raptors", "NBA: Celtics @ Raptors", "Live: Sports News", "Oregon vs. Michigan State College Football In-Game Live Gameday", "Tennis Channel Live at the Finals", "The Crossing"))
            assertFalse(title, live(title))
    }

    @Test fun aVerifiedLiveAiringOnAVisibleChannelJoinsItsFixtureOnlyOnce() {
        val air = airing("Live: Boston Celtics at Toronto Raptors")
        val items = listOf(game, air, airing("Live: NBA highlights"), airing("NBA Today"), airing("Live: Wizards at Heat", "hidden"))
        fun rows(follows: SportsFollows, events: List<SportsEvent>, allSports: Boolean = false) = CalendarLocalListings.sportRows(events, mapOf(nba.id to nba),
            follows, SportsSettings(region = "AU"), emptyMap(), mapOf("visible" to "Live Sports"), CalendarWindow(day, day), zone, allSports)
        val followed = SportsFollows(leagues = setOf(nba.id))
        assertEquals("", rows(followed, listOf(game)).single().detail)
        assertTrue(rows(SportsFollows(), listOf(game)).isEmpty())
        for (follows in listOf(followed, SportsFollows())) {
            val row = rows(follows, items, allSports = true).single()
            assertEquals(game.id, row.contentId)
            assertEquals("Live Sports", row.detail)
            assertEquals(game.id, row.fixture?.id)
            assertEquals(air.guideHits, row.fixture?.guideHits)
        }
        assertTrue(rows(SportsFollows(), items).isEmpty())
        assertEquals(game.id, rows(followed, items).single().contentId)
        assertTrue(rows(SportsFollows(), listOf(air), allSports = true).isEmpty())
        val named = air.copy(word = "Celtics")
        assertEquals(game.id, rows(SportsFollows(words = listOf("Celtics")), listOf(named, game)).single().contentId)
        assertTrue(rows(SportsFollows(words = listOf("Rugby")), listOf(named, game)).isEmpty())
    }

    @Test fun simulcastsOfOneFormulaOneSessionKeepTheirChannelsAndOtherSessionsStaySeparate() {
        fun race(title: String, mins: Int, key: String) = airing("Live: " + title, key).copy(leagueId = "racing/f1",
            startMs = noon + mins * 60_000, endMs = noon + 90 * 60_000)
        val slots = listOf(race("FIA Formula One World Championship: Las Vegas P3", 0, "a"),
            race("Las Vegas F1 GP: Practice 3", 0, "b"), race("Formula 1 Practice 2026", 10, "c"),
            race("F1 Academy: Race 1", 0, "d"), race("F1 Academy: Race 2", 5, "e"),
            race("Formula 1 Qualifying", 0, "f"), race("Formula 1 Practice 1", 180, "g"))
        val merged = CalendarLiveBroadcasts.merge(slots)
        assertEquals(5, merged.size)
        val practice = merged.single { it.guideHits.size > 1 }
        assertEquals(setOf("a", "b", "c"), practice.guideHits.map { it.first }.toSet())
        assertEquals(noon, practice.startMs)
        assertTrue(practice.title.contains("Practice 3"))
    }

    @Test fun lasVegasCalendarUsesSessionTimesAndAttachesOnlyLiveCoverage() {
        val f1 = requireNotNull(SportsCatalog.league("racing/f1"))
        val raw = """{"events":[{"id":"600057449","name":"Heineken Las Vegas Grand Prix","date":"2026-11-20T00:30Z",
            "endDate":"2026-11-22T06:00Z","competitions":[
            {"id":"401839135","date":"2026-11-21T00:30Z","type":{"abbreviation":"FP3"}},
            {"id":"401839136","date":"2026-11-21T04:00Z","type":{"abbreviation":"Qual"}},
            {"id":"401839137","date":"2026-11-22T04:00Z","type":{"abbreviation":"Race"}}]}]}"""
        val sessions = SportsFeeds.parseScoreboard(f1, raw, racingSessions = true)
        val parent = SportsFeeds.parseScoreboard(f1, raw).single()
        fun slot(title: String, minutes: Int, key: String): SportsEvent {
            val start = noon + minutes * 60_000L
            val p = EpgProgram(start, start + 2 * 3_600_000L, "Live: " + title)
            return airing(title, key).copy(id = key, leagueId = f1.id, startMs = start, endMs = p.stopMs, guideHits = listOf(key to p))
        }
        val tv = listOf(slot("Las Vegas F1 GP: Practice 3", -35, "practice"),
            slot("Formula 1 Qualifying", 90, "early"), slot("Las Vegas F1 GP: Qualifying", 120, "sky"),
            slot("Formula 1 Qualifying 2026", 175, "tsn"), slot("Formula 1 Racing", 175, "generic"),
            slot("Formula 1 Qualifying", 89, "too-early"), slot("Formula 1 FP2", -30, "wrong-session"),
            slot("Formula 1 Qualifying Post Show", 185, "post"), slot("F1: Pressekonferenz: Qualifying", 190, "press"),
            slot("Formula 1: Las Vegas Grand Prix: Qualifying", 620, "repeat"),
            slot("F1 Academy: Race 1", 175, "academy"))
        val rows = CalendarLocalListings.sportRows(sessions + parent + tv + sessions[1].copy(id = "alternate", source = EventSource.THESPORTSDB),
            mapOf(f1.id to f1), SportsFollows(), SportsSettings(), emptyMap(), tv.associate { it.id to it.id }, CalendarWindow(day, day), zone, true)
        assertEquals(3, rows.size)
        val practice = rows.single { it.title.endsWith("Free Practice 3") }
        assertEquals(noon - 30 * 60_000L, practice.time)
        assertEquals(listOf("practice"), practice.fixture!!.guideHits.map { it.first })
        val qualifying = rows.single { it.title.endsWith(" · Qualifying") }
        assertEquals(noon + 180 * 60_000L, qualifying.time)
        assertEquals(setOf("early", "sky", "tsn", "generic"), qualifying.fixture!!.guideHits.map { it.first }.toSet())
        assertTrue(rows.any { it.contentId == "academy" })
    }

    @Test fun coveredLeagueDropsUnmatchedAiringsWithoutHidingAnotherCompetition() {
        val air = airing("Live: Boston Celtics at Toronto Raptors")
        val replay = air.copy(id = "rerun", startMs = noon + 6 * 3_600_000,
            guideHits = listOf("visible" to air.guideHits.single().second.copy(startMs = noon + 6 * 3_600_000, stopMs = noon + 8 * 3_600_000)))
        val youth = airing("Live: FIFA U17 World Cup Qualifiers").copy(leagueId = "guide/youth-football")
        val rows = CalendarLocalListings.sportRows(listOf(game, air, replay, youth), mapOf(nba.id to nba), SportsFollows(), SportsSettings(),
            emptyMap(), mapOf("visible" to "Sports"), CalendarWindow(day, day), zone, true)
        assertEquals(setOf(game.id, youth.id), rows.map { it.contentId }.toSet())
        assertEquals(air.guideHits, rows.single { it.contentId == game.id }.fixture!!.guideHits)
    }

    @Test fun coverageBeforeMidnightBelongsToTheOfficialFixturesDay() {
        val start = saturday.atTime(0, 20).atZone(zone).toInstant().toEpochMilli()
        val listing = EpgProgram(start - 60 * 60_000L, start + 120 * 60_000L, "Live: Boston Celtics at Toronto Raptors")
        val air = airing(listing.title).copy(startMs = listing.startMs, endMs = listing.stopMs, guideHits = listOf("visible" to listing))
        val row = CalendarLocalListings.sportRows(listOf(game.copy(startMs = start), air), mapOf(nba.id to nba),
            SportsFollows(leagues = setOf(nba.id)), SportsSettings(), emptyMap(), mapOf("visible" to "Sports"), CalendarWindow(day, day), zone).single()
        assertEquals(day, row.day)
        assertEquals(start, row.time)
        assertEquals(air.guideHits, row.fixture!!.guideHits)
    }

    @Test fun wholeSportFollowsIncludeGuideOnlyCompetitionsWithoutAnOfficialFixture() {
        fun unmatched(title: String): SportsEvent {
            val event = airing(title)
            assertTrue(title, SportsEventListings.liveCompetition(event.guideHits.single().second))
            val node = requireNotNull(SportsTaxonomy.classifyProgramme(title, null)) { title }
            return event.copy(leagueId = "taxonomy/" + node.id)
        }
        val cricket = unmatched("Live: Women's WBBL Cricket")
        val wbblGame = unmatched("Live: Women's Big Bash : Hobart Hurricanes v Sydney Thunder")
        val football = unmatched("Live: FIFA U17 World Cup Qualifiers")
        val youthGame = unmatched("Live: FIFA U-17 World Cup Qatar 2026™ - Serbia v Honduras")
        val racing = unmatched("Live: Formula 1 Grand Prix Qualifying")
        fun rows(vararg ids: String) = CalendarLocalListings.sportRows(listOf(cricket, wbblGame, football, youthGame, racing), emptyMap(),
            SportsFollows(pins = ids.toList()), SportsSettings(), emptyMap(), mapOf("visible" to "Sports TV"),
            CalendarWindow(day, day), zone).map { it.contentId }.toSet()
        assertEquals(setOf(cricket.id, wbblGame.id), rows("guide/cricket"))
        assertEquals(setOf(football.id, youthGame.id), rows("taxonomy/football-soccer"))
        assertTrue(rows("taxonomy/motorsport").isEmpty())
        val mine = SportsFollows(leagues = setOf("guide/cricket", InternationalCricket.LEAGUE_ID))
        val shortcuts = SportsRows.followedSports(mine, Sport.entries)
        assertEquals(listOf(Sport.CRICKET), shortcuts)
        val window = CalendarWindow(day, day)
        val all = CalendarLocalListings.sportRows(listOf(wbblGame, football), emptyMap(), mine, SportsSettings(),
            emptyMap(), mapOf("visible" to "Sports TV"), window, zone, allSports = true)
        assertEquals(2, all.size)
        assertEquals(wbblGame.id, CalendarPolicy.displayed(all, window, day, shortcuts.single()).single().contentId)
        assertTrue(rows("soccer/eng.1", "cricket/8048").isEmpty())
    }

    @Test fun aChannelMatchAloneDoesNotPutAnotherTeamsGameInMySports() {
        val other = game.copy(id = "other", away = side("Chicago Bulls"), home = side("Miami Heat"))
        val events = listOf(game, other)
        fun rows(follows: SportsFollows, all: Boolean = false) = CalendarLocalListings.sportRows(events, mapOf(nba.id to nba),
            follows, SportsSettings(), events.associate { it.id to listOf("visible") }, mapOf("visible" to "Sports TV"),
            CalendarWindow(day, day), zone, all).map { it.contentId }
        val team = FollowedTeam(nba.id, "Boston Celtics", "Boston Celtics", "BOS", null, null)
        assertEquals(listOf(game.id), rows(SportsFollows(teams = listOf(team))))
        assertEquals(listOf(other.id), rows(SportsFollows(reminders = listOf(SportsReminder(other.id, other.title, noon)))))
        assertEquals(setOf(game.id, other.id), rows(SportsFollows(), all = true).toSet())
    }

    @Test fun cachedChannelAnnouncementsKeepTheirMarket() {
        val event = game.copy(broadcasts = listOf("US network"), broadcastCoverage = listOf(
            SportsBroadcast("US network", "United States"), SportsBroadcast("Australian network", "Australia")))
        val saved = SportsSnapshotEvent.from(event).event()
        fun label(fixture: SportsEvent) = CalendarLocalListings.sportRows(listOf(fixture), mapOf(nba.id to nba),
            SportsFollows(leagues = setOf(nba.id)), SportsSettings(region = "AU"), emptyMap(), emptyMap(), CalendarWindow(day, day), zone).single().detail
        assertEquals("Australian network", label(saved))
        assertEquals("", label(saved.copy(broadcastCoverage = listOf(SportsBroadcast("Unspecified market", null)))))
    }

    @Test fun laterTestSessionsOpenOneOfficialFixtureWithAllGuideChannels() {
        val league = League("test/series", Sport.CRICKET, "Test Matches", "Tests")
        val fixture = game.copy(id = "official", leagueId = league.id, title = "New Zealand v India", startMs = noon - 3 * 3_600_000L - 2 * 86_400_000L,
            endMs = noon + 2 * 86_400_000L, away = side("India"), home = side("New Zealand"))
        fun listing(channel: String, title: String, hour: Int, description: String = "Live coverage") = airing(title, channel).let {
            val at = noon + (hour - 12) * 3_600_000L
            it.copy(id = channel + hour, leagueId = league.id, startMs = at, endMs = at + 2 * 3_600_000L,
                subtitle = description, guideHits = listOf(channel to EpgProgram(at, at + 2 * 3_600_000L, "Live: $title", description)))
        }
        val channels = listOf(listing("TNT", "Test Cricket: 1st Test, Day 2: New Zealand v India", 3),
            listing("Fox", "New Zealand v India 1st Test Day 3", 8), listing("Willow", "Test Cricket", 9, "1st Test, Day 3."),
            listing("Willow", "Test Cricket", 14, "1st Test, Day 3."), listing("BT", "Test Cricket: India vs. New Zealand", 9))
        val follows = SportsFollows(leagues = setOf(league.id))
        fun rows(events: List<SportsEvent>) = CalendarLocalListings.sportRows(events, mapOf(league.id to league), follows,
            SportsSettings(), emptyMap(), channels.flatMap { it.guideHits }.associate { it.first to it.first }, CalendarWindow(day, day), zone)
        val row = rows(listOf(fixture, fixture.copy(id = "tsdb-copy", source = EventSource.THESPORTSDB)) + channels).single()
        assertEquals(fixture.id, row.contentId)
        assertEquals(fixture.id, row.fixture?.id)
        assertEquals(channels.drop(1).flatMap { it.guideHits }.toSet(), row.fixture!!.guideHits.toSet())
        assertEquals(channels[1].startMs, row.time)
        val other = fixture.copy(id = "other", title = "England v Australia", home = side("England"), away = side("Australia"))
        val ambiguous = rows(listOf(fixture, other) + channels)
        assertTrue(ambiguous.single { it.contentId == fixture.id }.fixture!!.guideHits.none { it.first == "Willow" })
        assertFalse(ambiguous.any { it.title == "Test Cricket" })
        val namedOnly = channels.filterNot { it.title == "Test Cricket" }
        assertEquals(namedOnly.flatMap { it.guideHits }.toSet(), rows(namedOnly).single().fixture!!.guideHits.toSet())
    }

    @Test fun guideOnlyGamesMergeAcrossNetworksAndKeepTeamsCompetitionAndSeparateGames() {
        fun tv(title: String, minutes: Int, channel: String, league: League): SportsEvent {
            val start = noon + minutes * 60_000L
            val p = EpgProgram(start, start + 2 * 3_600_000L, "Live: $title")
            return airing(title, channel).copy(id = channel, leagueId = league.id, startMs = start, endMs = p.stopMs,
                guideHits = listOf(channel to p))
        }
        fun check(league: League, first: String, second: String, separate: String, names: List<String>, label: String) {
            val games = listOf(tv(first, 0, "A", league), tv(second, 25, "B", league), tv(separate, 15, "C", league),
                tv(first, 240, "D", league))
            val rows = CalendarLocalListings.sportRows(games, mapOf(league.id to league), SportsFollows(), SportsSettings(),
                emptyMap(), games.associate { it.id to it.id }, CalendarWindow(day, day), zone, true)
            assertEquals(first, 3, rows.size)
            val merged = rows.single { it.fixture!!.guideHits.size == 2 }
            assertEquals(setOf("A", "B"), merged.fixture!!.guideHits.map { it.first }.toSet())
            assertEquals(names.toSet(), merged.guideSides.map { it.team.name }.toSet())
            assertEquals(label, merged.leagueName)
            assertEquals(2, merged.channelCount)
        }
        check(League("guide/cricket", Sport.CRICKET, "Cricket", "Cricket"), "Women's Big Bash League: WBBL: Hurricanes v Thunder",
            "WBBL: Sydney Thunder vs. Hobart Hurricanes", "WBBL: Hobart Hurricanes v Sydney Sixers",
            listOf("Sydney Thunder", "Hobart Hurricanes"), "WBBL")
        check(League("guide/hockey", Sport.HOCKEY, "Field Hockey", "Hockey"), "NCAA Field Hockey: #3 Boston College at North Carolina",
            "NCAA Field Hockey: UNC vs. BC", "NCAA Field Hockey: North Carolina State v Boston College",
            listOf("North Carolina", "Boston College"), "NCAA Field Hockey")
        check(League("guide/football", Sport.AMERICAN_FOOTBALL, "High School Football", "HSFB"),
            "High School Football: St. Xavier HS @ Trinity", "High School Football: Trinity vs Saint Xavier High School",
            "High School Football: Trinity v Saint Xavier High School (Game 2)",
            listOf("Trinity", "Saint Xavier High School"), "High School Football")
    }

    @Test fun twoListingsForTheSameNamedChampionshipFinalCanMergeBeforeTeamsAreAnnounced() {
        val cricket = League("guide/cricket", Sport.CRICKET, "Cricket", "Cricket")
        fun slot(title: String, key: String, start: Long = noon) = airing(title, key).copy(id = key, leagueId = cricket.id,
            startMs = start, endMs = start + 7_200_000L,
            guideHits = listOf(key to EpgProgram(start, start + 7_200_000L, "Live: $title")))
        val listings = listOf(slot("Abu Dhabi T10 Cricket : Final: Teams TBA", "a"), slot("Abu Dhabi T10 League : Final", "b"),
            slot("Abu Dhabi T10 League : Semi Final", "c"), slot("Abu Dhabi T10 League : Final", "d", noon + 86_400_000L))
        val games = CalendarGuideMatchups.merge(listings) { cricket }
        assertEquals(3, games.size)
        val final = games.single { it.event.guideHits.size == 2 }
        assertEquals("Abu Dhabi T10 Final", final.event.title)
        assertEquals(setOf("a", "b"), final.event.guideHits.map { it.first }.toSet())
    }

    @Test fun testCoverageAllowsEarlyStartsAndOvernightPlayInTheViewersTimeZone() {
        val start = saturday.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val fixture = game.copy(startMs = start)
        val tests = CalendarTestMatches.Fixtures(emptyList(), zone) { null }
        fun covered(minutes: Int, dayLabel: Int): Boolean {
            val at = start + minutes * 60_000L
            return tests.liveSession(EpgProgram(at, at + 2 * 3_600_000L, "Live: Test Cricket, Day $dayLabel"), fixture)
        }
        assertTrue(covered(-90, 1))
        assertFalse(covered(-91, 1))
        assertTrue(covered(240, 1))
        assertFalse(covered(240, 2))
        assertTrue(covered(24 * 60 - 45, 2))
    }

    @Test fun aSecondDayOfTestCricketHasItsOwnDateWithoutRepeatingYesterdaysStartTime() {
        val cricket = CricketAustralia.leagues.first()
        val match = game.copy(leagueId = cricket.id, startMs = noon - 86_400_000, endMs = noon + 86_400_000,
            source = EventSource.CRICKET_AUSTRALIA)
        val row = CalendarLocalListings.sportRows(listOf(match), mapOf(cricket.id to cricket), SportsFollows(leagues = setOf(cricket.id)),
            SportsSettings(), emptyMap(), emptyMap(), CalendarWindow(day, day), zone).single()
        assertEquals(2L, row.matchDay)
        assertEquals("", row.detail)
        assertEquals(day, row.day)
        assertNull(row.time)
    }

    @Test fun olderCachedTVRowsDisappearBeforeTheFirstPaintAndMonthPagesStaySeparate() {
        val cache = CalendarPageCache(temp.root)
        val item = CalendarItem("episode", "series", "Show", day, CalendarKind.EPISODE)
        val old = CalendarPage(version = 2, region = "AU", zone = zone.id, weekStart = day, inputSignature = "old",
            items = listOf(item, item.copy(key = "rerun", kind = CalendarKind.GUIDE)))
        cache.write(53, old)
        val restored = cache.read(53, "AU", zone.id, day)!!
        assertEquals(listOf(item), restored.items)
        assertNull(restored.inputSignature)
        assertEquals(3, restored.version)
        cache.write(53, restored.copy(days = 30, items = emptyList()))
        assertEquals(listOf(item), cache.read(53, "AU", zone.id, day)!!.items)
        assertTrue(cache.read(53, "AU", zone.id, day, 30)!!.items.isEmpty())
        assertEquals(29, CalendarWindow.month(LocalDate.of(2028, 2, 15)).days)
    }
}
