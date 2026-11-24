package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.OffsetDateTime

class SportsFixtureDiscoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sport = Sport.SNOOKER
    private val time = OffsetDateTime.parse("2026-01-12T13:00:00Z")
    private val ms = time.toInstant().toEpochMilli()
    private val programme = EpgProgram(ms - 5 * 60_000, ms + 2 * 3_600_000, "Masters Snooker Day 2")
    private val claim = SportsFixtureDiscovery.Claim("https://www.wst.tv/events/masters-draw/", "Masters", time,
        "Europe/London", "Masters 2026 Draw", "Monday January 12th 1pm", "Play starts January 11th", "Masters in London")
    private val html = "<article><h1>Masters 2026 Draw</h1><p>Masters in London</p><p>Play starts January 11th.</p>" +
        "<p>Monday January 12<sup>th</sup></p><p>1pm – Player One vs Player Two</p><p>7pm – Evening match</p></article>"
    private fun verify(c: SportsFixtureDiscovery.Claim = claim, p: EpgProgram = programme, page: String = html) =
        SportsFixtureDiscovery.verify(p, sport, c, page)

    @Test fun unlabelledDayTwoAndActualSessionStart() {
        val result = verify()!!
        assertEquals(ms, result.startMs)
        assertEquals(claim.url, result.url)
        assertNull(verify(p = programme.copy(title = "Masters Snooker Day 3")))
        assertNull(verify(c = claim.copy(opening = "Play starts January 10th")))
        assertNull(verify(p = programme.copy(startMs = ms + 45 * 60_000)))
        assertNull(verify(p = programme.copy(description = "Evening session")))
        assertNull(verify(p = programme.copy(title = "German Masters Snooker Day 2")))
        assertNull(verify(p = programme.copy(title = "Masters Snooker Final")))
    }

    @Test fun previouslyUnknownTournamentCanQualifyInAnotherSport() {
        val c = claim.copy(url = "https://www.worldaquatics.com/competition/schedule", competition = "Harbour Cup",
            edition = "Harbour Cup 2026", zone = "UTC", slot = "January 12th 13:00 UTC", opening = "", zoneEvidence = "")
        val p = programme.copy(title = "Swimming – Harbour Cup")
        val source = "<h1>Harbour Cup 2026</h1><p>January 12th 13:00 UTC</p>"
        assertNotNull(SportsFixtureDiscovery.verify(p, Sport.SWIMMING, c, source))
        assertNull(SportsFixtureDiscovery.verify(p, Sport.SNOOKER, c, source))
        assertNull(SportsFixtureDiscovery.verify(p, Sport.SWIMMING, c, source + "<p>Meeting cancelled.</p>"))
    }

    @Test fun falsePositiveGuideEntriesNeverQualify() {
        for (p in listOf(programme.copy(title = "Masters Snooker 2024"), programme.copy(year = 2025),
            programme.copy(category = "Highlights"), programme.copy(description = "A repeat of last year's final."),
            programme.copy(title = "Masters Snooker studio"), programme.copy(title = "Snooker"),
            programme.copy(stopMs = programme.startMs))) assertFalse(p.toString(), SportsFixtureDiscovery.eligible(p))
    }

    @Test fun datesClocksAndZonesMustBelongToTheOfficialPage() {
        assertNull(verify(page = "<h1>Masters 2026 Draw</h1>Runs January 11th to 18th"))
        assertNull(verify(page = html.replace("2026", "2025")))
        assertNull(verify(page = html.replace("12<sup>", "14<sup>")))
        assertNull(verify(page = html.replace("1pm", "7pm")))
        assertNull(verify(c = claim.copy(zone = "Europe/Paris")))
        assertNull(verify(c = claim.copy(url = "https://wst.tv.example.org/draw")))
        assertNull(verify(c = claim.copy(url = "http://wst.tv/draw")))
        assertNull(verify(c = claim.copy(url = "https://www.bbc.co.uk/guide")))
        assertNull(verify(p = programme.copy(description = "Smith vs Jones in the first round.")))
        val range = "Monday January 12th 11am to 1pm"
        assertNull(verify(c = claim.copy(slot = range), page = html.replace("1pm", "11am to 1pm")))
        assertNull(verify(p = programme.copy(title = "Masters Snooker Final"),
            c = claim.copy(edition = "Masters 2026 Semifinal"), page = html.replace("Masters 2026 Draw", "Masters 2026 Semifinal")))
    }

    @Test fun sessionTimeUsesVenueZoneAtDaylightSavingBoundary() {
        val local = OffsetDateTime.parse("2026-07-12T13:00:00+01:00")
        val start = local.toInstant().toEpochMilli()
        val p = programme.copy(startMs = start, stopMs = start + 3_600_000)
        val c = claim.copy(start = local, slot = "July 12th 1pm", opening = "Play starts July 11th")
        assertNotNull(verify(c, p, html.replace("January", "July")))
        assertNull(verify(c.copy(start = local.withOffsetSameLocal(java.time.ZoneOffset.UTC)), p, html.replace("January", "July")))
    }

    @Test fun unrelatedCitationsAndUnfinishedResponsesAreRejected() {
        val data = buildJsonObject {
            put("decision","scheduled"); put("url",claim.url); put("competition","Masters")
            put("start",time.toString()); put("zone",claim.zone); put("edition",claim.edition); put("slot",claim.slot)
        }
        fun response(title: String, finish: String) = """{"candidates":[{"finishReason":"$finish","content":{"parts":[{"text":${JsonPrimitive(data.toString())}}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/id","title":"$title"}}]}}]}"""
        assertNotNull(SportsFixtureDiscovery.claim(response("wst.tv","STOP"), sport))
        assertNull(SportsFixtureDiscovery.claim(response("elsewhere.example","STOP"), sport))
        assertNull(SportsFixtureDiscovery.claim(response("wst.tv","MAX_TOKENS"), sport))
    }

    @Test fun confirmedFixturesRequireTheSameGuideContentAndGiveWayToProviders() {
        val guide = SportsEvent("guide:123", "guide/snooker", programme.startMs, true, GameState.LIVE, programme.title,
            null, null, null, null, source = EventSource.GUIDE, guideHits = listOf("ch1" to programme))
        val observations = mapOf(SportsGuideFixtures.Listing(guide.leagueId, programme) to SportsGuideFixtures.Observation(verify()!!, ms))
        fun fixtures(vararg input: SportsEvent, now: Long = ms) = SportsEventListings.allEvents(
            SportsGuideFixtures.add(input.toList(), observations, now), ms - 86_400_000, ms + 86_400_000, now)
        val fixture = fixtures(guide, guide.copy(id = "guide:other", guideHits = listOf("ch2" to programme))).single()
        assertEquals(setOf("ch1","ch2"), fixture.guideHits.map { it.first }.toSet())
        assertEquals(claim.url, fixture.scheduleUrl)
        assertEquals(GameState.PRE, fixture.state)
        assertEquals(ms, fixture.startMs)
        assertTrue(fixtures(guide.copy(guideHits = listOf("ch1" to programme.copy(description = "Replay")))).isEmpty())
        assertTrue(fixtures(guide, now = programme.stopMs).isEmpty())
        val provider = guide.copy(id = "provider", source = EventSource.FIXTURES, guideHits = emptyList())
        assertEquals(listOf(provider), fixtures(guide, provider))
    }

    @Test fun onlyAnUnmatchedVisibleIptvProgrammeCanTriggerSearch() {
        val airing = SportsEvent("guide:one", "guide/snooker", ms, true, GameState.PRE, programme.title,
            null, null, null, null, source = EventSource.GUIDE, guideHits = listOf("channel" to programme))
        fun next(airings: List<SportsEvent> = listOf(airing), fixtures: List<SportsEvent> = emptyList(),
                 visible: Set<String> = setOf("channel"), matched: Set<Pair<String,EpgProgram?>> = emptySet(),
                 checked: Set<SportsGuideFixtures.Listing> = emptySet()) =
            SportsGuideFixtures.next(airings.asSequence(), fixtures, visible, matched, checked, ms, null)
        assertEquals(airing to programme, next())
        assertNull(next(airings = emptyList()))
        assertNull(next(airings = listOf(airing.copy(guideHits = emptyList()))))
        assertNull(next(visible = emptySet()))
        assertNull(next(matched = setOf("channel" to programme)))
        assertNull(next(checked = setOf(SportsGuideFixtures.Listing(airing.leagueId, programme))))
        assertNull(next(fixtures = listOf(airing.copy(id = "calendar", source = EventSource.FIXTURES))))
    }

    @Test fun findingsArePrivateToTheProfileAndExpire() {
        var now = ms
        val dir = temporary.newFolder()
        val cache = GeminiGuideCache(dir) { now }
        cache.remember(1, "listing", verify())
        cache.remember(1, "uncertain", null)
        assertEquals(ms, cache.observation(1, "listing", sport)?.proof?.startMs)
        assertNotNull(cache.observation(1, "uncertain", sport))
        assertNull(cache.observation(2, "listing", sport))
        assertNull(cache.observation(1, "changed-programme", sport))
        now += SportsGuideFixtures.VALID_MS
        assertNull(GeminiGuideCache(dir) { now }.observation(1, "listing", sport))
    }

    @Test fun discoveryHasItsOwnPersistentLimitWithinTheSharedBudget() {
        var now = ms
        val dir = temporary.newFolder()
        repeat(3) {
            assertTrue(GeminiGuideCache(dir) { now }.reserve(discovery = true))
            now += 15_000
        }
        val cache = GeminiGuideCache(dir) { now }
        assertFalse(cache.reserve(discovery = true))
        assertTrue(cache.reserve())
        now += 3_600_000
        assertTrue(cache.reserve(discovery = true))
    }
}
