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
    private val time = OffsetDateTime.parse("2026-01-12T13:00Z")
    private val ms = time.toInstant().toEpochMilli()
    private val programme = EpgProgram(ms - 5 * 60_000, ms + 2 * 3_600_000, "Masters Snooker Day 2")
    private val source = "https://example.org/masters/session"
    private val fact = "Masters Snooker Day 2 is scheduled for 2026-01-12T13:00Z."
    private fun report(name: String = "Masters Snooker", finding: String = fact, sport: Sport = this.sport,
                       fields: Map<String,String> = emptyMap()) = buildJsonObject {
        val defaults = mapOf("eventName" to name, "evidence" to finding, "sport" to sport.name,
            "eventStart" to "2026-01-12T13:00Z", "fixtureMatch" to "exact", "airingType" to "live_at_fixture_time")
        (defaults + fields).forEach { (key,value) -> put(key, value) }
    }

    /** Simulate the generateContent wire format, including byte offsets into its visible response part. */
    private fun grounded(data: JsonObject = report(), excerpt: String? = data["evidence"]?.jsonPrimitive?.content,
                         url: String = source, finish: String = "STOP", chunk: Int = 0, part: Int = 1): String {
        val answer = data.toString()
        val supports = buildJsonArray {
            if (excerpt != null) {
                val at = answer.indexOf(excerpt)
                require(at >= 0)
                val from = answer.take(at).toByteArray().size
                add(buildJsonObject {
                    put("segment", buildJsonObject { put("partIndex", part); put("text", excerpt)
                        put("startIndex", from); put("endIndex", from + excerpt.toByteArray().size) })
                    put("groundingChunkIndices", buildJsonArray { add(chunk) })
                })
            }
        }
        return buildJsonObject { put("candidates", buildJsonArray { add(buildJsonObject {
            put("finishReason", finish)
            put("content", buildJsonObject { put("parts", buildJsonArray {
                add(buildJsonObject { put("thought", true); put("text", "Looking up the fixture") })
                add(buildJsonObject { put("text", answer) })
            }) })
            put("groundingMetadata", buildJsonObject {
                put("groundingChunks", buildJsonArray { add(buildJsonObject { put("web", buildJsonObject { put("uri", url) }) }) })
                put("groundingSupports", supports)
            })
        }) }) }.toString()
    }

    private fun verify(raw: String = grounded(), p: EpgProgram = programme, sport: Sport = this.sport) =
        SportsFixtureDiscovery.verify(p, sport, raw)

    @Test fun originalFixtureTimeAndSessionAreRequired() {
        assertEquals(ms, verify()!!.startMs)
        assertEquals(source, verify()!!.url)
        assertNull(verify(p = programme.copy(title = "Masters Snooker Day 3")))
        assertNull(verify(p = programme.copy(title = "German Masters Snooker Day 2")))
        assertNull(verify(p = programme.copy(title = "Masters Snooker Final")))
        assertNull(verify(p = programme.copy(startMs = ms - 20 * 60_000)))
        assertNull(verify(grounded(report(fields = mapOf("eventStart" to "2026-01-12T14:00+01:00")))))
        assertNull(verify(grounded(report(finding = "Masters Snooker Day 2 is on January 12."))))
        assertNull(verify(grounded(report(finding = fact.replace("13:00Z","13:00-05:00"),
            fields = mapOf("eventStart" to "2026-01-12T13:00-05:00")))))
    }

    @Test fun publisherIndependentHighSchoolFixtureHonoursCompetitionFilter() {
        val p = programme.copy(title = "High School Basketball: Lincoln vs Central")
        val name = "Lincoln vs Central"
        val evidence = "$name in high school basketball is scheduled for 2026-01-12T13:00Z."
        val data = report(name, evidence, Sport.BASKETBALL)
        val url = "https://athletics.lincoln.example/calendar"
        assertEquals(url, verify(grounded(data, url = url), p, Sport.BASKETBALL)?.url)
        val redirect = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/sample"
        assertEquals(redirect, verify(grounded(data, url = redirect), p, Sport.BASKETBALL)?.url)
        assertNull(verify(grounded(report(name, evidence.replace("high school","college"), Sport.BASKETBALL)), p, Sport.BASKETBALL))
        assertNull(verify(grounded(data), p.copy(title = "High School Basketball: Lincoln vs East"), Sport.BASKETBALL))
        assertNull(verify(grounded(report(name, evidence.replace("basketball","football"), Sport.BASKETBALL)), p, Sport.BASKETBALL))
        val node = requireNotNull(SportsTaxonomy.classifyProgramme(p.title, null))
        assertEquals("basketball.high-school-basketball", node.id)
        assertFalse(SportsTaxonomy.acceptsEvent(SportsTaxonomy.leagueById["taxonomy/" + node.id], p.title, null, setOf(node.id)))
        assertFalse(SportsTaxonomy.enabled(node.id, setOf("basketball")))
    }

    @Test fun onlyExplicitPositiveVerdictsQualify() {
        for (type in listOf("unknown","replay_or_delayed","non_event","yes",""))
            assertNull(verify(grounded(report(fields = mapOf("airingType" to type)))))
        for (match in listOf("different","unknown","yes"))
            assertNull(verify(grounded(report(fields = mapOf("fixtureMatch" to match)))))
        assertNull(verify(grounded(report(fields = mapOf("sport" to "unknown")))))
        assertNull(verify(grounded(JsonObject(report() - "fixtureMatch"))))
        assertNull(verify(grounded(report(finding = "$fact The match is postponed."))))
    }

    @Test fun citationsMustSupportTheFixtureAndTheTimeInTheCorrectPart() {
        assertNull(verify(grounded(excerpt = null)))
        assertNull(verify(grounded(excerpt = "Masters Snooker")))
        assertNull(verify(grounded(chunk = 4)))
        assertNull(verify(grounded(part = 0)))
        assertNull(verify(grounded(finish = "MAX_TOKENS")))
        assertNull(verify(grounded(url = "file:///schedule")))
        assertNull(verify(grounded(url = "http://publisher.example/game")))
        assertNull(verify(grounded(url = "https://localhost/game")))
        val name = "Étoile vs Central"
        val data = report(name,"$name in high school basketball starts at 2026-01-12T13:00Z.", Sport.BASKETBALL)
        assertNotNull(verify(grounded(data), programme.copy(title = "High School Basketball", description = name), Sport.BASKETBALL))
    }

    @Test fun wrongEditionsAndEditorialShowsCannotTriggerDiscovery() {
        for (p in listOf(programme.copy(title = "Masters Snooker 2024"), programme.copy(year = 2025),
            programme.copy(category = "Highlights"), programme.copy(description = "A repeat of last year's final."),
            programme.copy(title = "Masters Snooker studio"), programme.copy(title = "Snooker"),
            programme.copy(stopMs = programme.startMs))) assertFalse(p.toString(), SportsFixtureDiscovery.eligible(p))
    }
    @Test fun confirmedFixturesRequireTheSameGuideContentAndGiveWayToProviders() {
        val guide = SportsEvent("guide:123", "guide/snooker", programme.startMs, true, GameState.LIVE, programme.title,
            null, null, null, null, source = EventSource.GUIDE, guideHits = listOf("ch1" to programme))
        val observations = mapOf(SportsGuideFixtures.Listing(guide.leagueId, programme) to SportsGuideFixtures.Observation(verify()!!, ms))
        fun fixtures(vararg input: SportsEvent, now: Long = ms) = SportsEventListings.allEvents(
            SportsGuideFixtures.add(input.toList(), observations, now), ms - 86_400_000, ms + 86_400_000, now)
        val fixture = fixtures(guide, guide.copy(id = "guide:other", guideHits = listOf("ch2" to programme))).single()
        assertEquals(setOf("ch1","ch2"), fixture.guideHits.map { it.first }.toSet())
        assertEquals(source, fixture.scheduleUrl)
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
        assertEquals(ms, cache.observation(1, "listing")?.proof?.startMs)
        assertNotNull(cache.observation(1, "uncertain"))
        assertNull(cache.observation(2, "listing"))
        assertNull(cache.observation(1, "changed-programme"))
        now += SportsGuideFixtures.VALID_MS
        assertNull(GeminiGuideCache(dir) { now }.observation(1, "listing"))
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
