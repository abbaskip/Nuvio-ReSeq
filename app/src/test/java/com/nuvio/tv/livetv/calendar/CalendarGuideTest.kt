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
        assertTrue(live("Live: WWE SmackDown"))
        assertFalse(live("Live at the studio"))
        for (title in listOf("NBA Today", "Live: NBA Pregame", "Live: NBA Highlights", "NFL Live", "Live: World Cup Preview",
            "Replay: Celtics v Raptors", "NBA: Celtics @ Raptors", "Live: Sports News", "Oregon vs. Michigan State College Football In-Game Live Gameday", "Tennis Channel Live at the Finals", "The Crossing"))
            assertFalse(title, live(title))
    }

    @Test fun aVerifiedLiveAiringOnAVisibleChannelJoinsItsFixtureOnlyOnce() {
        val air = airing("Live: Boston Celtics at Toronto Raptors")
        val items = listOf(game, air, airing("Live: NBA highlights"), airing("NBA Today"), airing("Live: Wizards at Heat", "hidden"))
        fun rows(follows: SportsFollows, events: List<SportsEvent>) = CalendarLocalListings.sportRows(events, mapOf(nba.id to nba),
            follows, SportsSettings(region = "AU"), emptyMap(), mapOf("visible" to "Live Sports"), CalendarWindow(day, day), zone)
        val followed = SportsFollows(leagues = setOf(nba.id))
        assertEquals("Channel TBC", rows(followed, listOf(game)).single().detail)
        assertTrue(rows(SportsFollows(), listOf(game)).isEmpty())
        for (follows in listOf(followed, SportsFollows())) {
            val row = rows(follows, items).single()
            assertEquals(game.id, row.contentId)
            assertEquals("Live Sports", row.detail)
            assertEquals(game.id, row.fixture?.id)
            assertEquals(air.guideHits, row.fixture?.guideHits)
        }
        assertEquals(air.id, rows(SportsFollows(), listOf(air)).single().contentId)
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

    @Test fun cachedChannelAnnouncementsKeepTheirMarket() {
        val event = game.copy(broadcasts = listOf("US network"), broadcastCoverage = listOf(
            SportsBroadcast("US network", "United States"), SportsBroadcast("Australian network", "Australia")))
        val saved = SportsSnapshotEvent.from(event).event()
        fun label(fixture: SportsEvent) = CalendarLocalListings.sportRows(listOf(fixture), mapOf(nba.id to nba),
            SportsFollows(leagues = setOf(nba.id)), SportsSettings(region = "AU"), emptyMap(), emptyMap(), CalendarWindow(day, day), zone).single().detail
        assertEquals("Australian network", label(saved))
        assertEquals("Channel TBC", label(saved.copy(broadcastCoverage = listOf(SportsBroadcast("Unspecified market", null)))))
    }

    @Test fun aSecondDayOfTestCricketHasItsOwnDateWithoutRepeatingYesterdaysStartTime() {
        val cricket = CricketAustralia.leagues.first()
        val match = game.copy(leagueId = cricket.id, startMs = noon - 86_400_000, endMs = noon + 86_400_000,
            source = EventSource.CRICKET_AUSTRALIA)
        val row = CalendarLocalListings.sportRows(listOf(match), mapOf(cricket.id to cricket), SportsFollows(leagues = setOf(cricket.id)),
            SportsSettings(), emptyMap(), emptyMap(), CalendarWindow(day, day), zone).single()
        assertEquals("Day 2 · Channel TBC", row.detail)
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
