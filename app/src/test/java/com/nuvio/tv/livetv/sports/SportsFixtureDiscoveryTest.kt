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
        val defaults = mapOf("id" to "g1", "eventName" to name, "evidence" to finding, "sport" to sport.name,
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
        return response(answer, supports, listOf(url), finish)
    }

    private fun response(answer: String, supports: JsonArray, urls: List<String>, finish: String = "STOP") =
        buildJsonObject { put("candidates", buildJsonArray { add(buildJsonObject {
            put("finishReason", finish)
            put("content", buildJsonObject { put("parts", buildJsonArray {
                add(buildJsonObject { put("thought", true); put("text", "Looking up the fixture") })
                add(buildJsonObject { put("text", answer) })
            }) })
            put("groundingMetadata", buildJsonObject {
                put("groundingChunks", buildJsonArray { urls.forEach { url ->
                    add(buildJsonObject { put("web", buildJsonObject { put("uri", url) }) })
                } })
                put("groundingSupports", supports)
            })
        }) }) }.toString()

    private fun batchResponse(vararg rows: Pair<JsonObject, String?>): String {
        val answer = rows.joinToString("\r\n") { it.first.toString() }
        var offset = 0
        val supports = buildJsonArray {
            rows.forEachIndexed { index, (row, url) ->
                val line = row.toString()
                if (url != null) {
                    val fact = row.getValue("evidence").jsonPrimitive.content
                    val from = offset + line.substringBefore(fact).toByteArray().size
                    add(buildJsonObject {
                        put("segment", buildJsonObject { put("partIndex", 1); put("text", fact)
                            put("startIndex", from); put("endIndex", from + fact.toByteArray().size) })
                        put("groundingChunkIndices", buildJsonArray { add(index) })
                    })
                }
                offset += line.toByteArray().size + 2
            }
        }
        return response(answer, supports, rows.map { it.second.orEmpty() })
    }

    private fun listing(p: EpgProgram = programme, sport: Sport = this.sport) =
        SportsGuideFixtures.Listing(if (sport == Sport.BASKETBALL) "taxonomy/basketball.high-school-basketball" else "guide/snooker", p)

    private fun verify(raw: String = grounded(), p: EpgProgram = programme, sport: Sport = this.sport) =
        SportsFixtureDiscovery.verify(mapOf("g1" to listing(p, sport)), raw)["g1"]

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
            SportsGuideFixtures.candidates(airings.asSequence(), fixtures, visible, matched, checked, ms, null, SportsFollows()).toList()
        assertEquals(listOf(listing()), next())
        assertTrue(next(airings = emptyList()).isEmpty())
        assertTrue(next(airings = listOf(airing.copy(guideHits = emptyList()))).isEmpty())
        assertTrue(next(visible = emptySet()).isEmpty())
        assertTrue(next(matched = setOf("channel" to programme)).isEmpty())
        assertTrue(next(checked = setOf(listing())).isEmpty())
        assertTrue(next(fixtures = listOf(airing.copy(id = "calendar", source = EventSource.FIXTURES))).isEmpty())
    }

    @Test fun followsRankListingsBeforeFillingAvailableBatchSlots() {
        fun airing(title: String, league: String, minutes: Int): SportsEvent {
            val p = programme.copy(title = title, startMs = ms + minutes * 60_000)
            return SportsEvent(title, league, p.startMs, true, GameState.PRE, title, null, null, null, null,
                source = EventSource.GUIDE, guideHits = listOf("tv" to p))
        }
        val close = airing("Masters Snooker Day 2", "guide/snooker", 0)
        val basketball = "taxonomy/basketball.high-school-basketball"
        val otherSchool = airing("Northern vs Central", basketball, 15)
        val followedTeam = airing("Lincoln vs Central", basketball, 30)
        val followedName = airing("Ronnie Sullivan Invitational", "guide/snooker", 10)
        val later = airing("British Snooker Open", "guide/snooker", 20)
        val input = listOf(close, otherSchool, followedTeam, followedName, later, followedTeam)
        fun next(f: SportsFollows) = SportsGuideFixtures.candidates(input.asSequence(), emptyList(), setOf("tv"),
            emptySet(), emptySet(), ms, close.id, f).take(SportsGuideFixtures.BATCH_SIZE).map { it.programme.title }.toList()
        assertEquals(listOf(followedName.title, followedTeam.title, otherSchool.title, close.title),
            next(SportsFollows(words = listOf("Ronnie Sullivan"), teams = listOf(
                FollowedTeam(basketball, "lincoln", "Lincoln", "LIN", null, null)))))
        assertEquals(listOf(otherSchool.title, followedTeam.title, close.title, followedName.title),
            next(SportsFollows(pins = listOf("taxonomy/basketball"))))
        assertEquals(listOf(close.title, followedName.title, otherSchool.title, later.title), next(SportsFollows()))
        val other = airing("National Climbing Final", "taxonomy/climbing", 0)
        val skiing = airing("Kitzbuhel Alpine Skiing Final", "taxonomy/skiing", 15)
        val ordered = SportsGuideFixtures.candidates(sequenceOf(other, skiing), emptyList(), setOf("tv"),
            emptySet(), emptySet(), ms, other.id, SportsFollows(teams = listOf(
                FollowedTeam("taxonomy/skiing", "unrelated", "Unrelated Racer", "UR", null, null))))
        assertEquals(listOf(skiing.title, other.title), ordered.map { it.programme.title }.toList())
    }

    @Test fun verdictsAndCitationsBelongToOneRequestedListing() {
        val school = listing(programme.copy(title = "High School Basketball: Étoile vs Central"), Sport.BASKETBALL)
        val inputs = mapOf("g1" to listing(), "g2" to school, "g3" to listing(), "g4" to listing())
        val game = report("Étoile vs Central", "Étoile vs Central high school basketball starts at 2026-01-12T13:00Z.",
            Sport.BASKETBALL, mapOf("id" to "g2"))
        val url = "https://school.example/schedule"
        fun check(vararg rows: Pair<JsonObject, String?>) = SportsFixtureDiscovery.verify(inputs, batchResponse(*rows))
        // g3 repeats the identical claim without its own citation. g4 is missing.
        val uncited = report(fields = mapOf("id" to "g3"))
        val unknown = report(fields = mapOf("id" to "unrequested"))
        val result = check(game to url, uncited to null, unknown to source, report() to source)
        assertEquals(mapOf("g1" to source, "g2" to url), result.mapValues { it.value.url })
        val replay = report(fields = mapOf("airingType" to "replay_or_delayed"))
        assertEquals(setOf("g2"), check(report() to source, game to url, replay to null).keys)
        assertEquals(setOf("g2"), check(replay to source, game to url).keys)
        assertEquals(setOf("g1"), check(report(fields = mapOf("id" to "g2")) to source, report() to source).keys)
    }

    @Test fun oneCitationCoveringTwoAnswerRowsCannotVerifyEitherListing() {
        val first = report().toString()
        val second = report(fields = mapOf("id" to "g2")).toString()
        val answer = first + "\n" + second
        val spansBoth = buildJsonArray {
            add(buildJsonObject {
                put("segment", buildJsonObject { put("partIndex", 1); put("endIndex", answer.toByteArray().size) })
                put("groundingChunkIndices", buildJsonArray { add(0) })
            })
        }
        assertTrue(SportsFixtureDiscovery.verify(mapOf("g1" to listing(), "g2" to listing()),
            response(answer, spansBoth, listOf(source))).isEmpty())
    }

    @Test fun aProfileChangeOrFailedRequestCannotPublishOrCacheBatchVerdicts() = kotlinx.coroutines.runBlocking {
        var now = ms
        val cache = GeminiGuideCache(temporary.newFolder()) { now }
        var current = true
        val session = 1 to "fingerprint"
        val changed = GeminiFixtureBatch.discover(sequenceOf(listing()), cache, session, { current }) {
            current = false
            grounded()
        }
        assertTrue(changed.isEmpty())
        assertTrue(cache.observations(1).isEmpty())
        now += 15_000
        val failed = GeminiFixtureBatch.discover(sequenceOf(listing()), cache, session, { true }) {
            throw java.io.IOException("offline")
        }
        assertTrue(failed.isEmpty())
        assertTrue(cache.observations(1).isEmpty())
        now += 60_000
        assertFalse(cache.reserve(discovery = true))
    }

    @Test fun fourUncachedListingsUseOneRequestAndCachedDecisionsCostNothing() = kotlinx.coroutines.runBlocking {
        var now = ms
        val cache = GeminiGuideCache(temporary.newFolder()) { now }
        val listings = (0..7).map { listing(programme.copy(startMs = programme.startMs + it * 60_000)) }
        val session = 1 to "test-key-fingerprint"
        var calls = 0
        suspend fun discover(input: List<SportsGuideFixtures.Listing>, expectedRows: Int) =
            GeminiFixtureBatch.discover(input.asSequence(), cache, session, { true }) { payload ->
                calls++
                val text = payload.getValue("contents").jsonArray.single().jsonObject.getValue("parts")
                    .jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
                val rows = Json.parseToJsonElement(text.substringAfterLast("Listings: ")).jsonArray
                assertEquals(expectedRows, rows.size)
                assertEquals(expectedRows, rows.map { it.jsonObject.getValue("id") }.distinct().size)
                grounded() // A real verdict for g1; later rows omitted by the model remain unverified.
            }
        val first = discover(listings, 4)
        assertEquals(listings.take(4).toSet(), first.keys)
        assertEquals(setOf(listings.first()), first.filterValues { it.proof != null }.keys)
        assertEquals(1, calls)
        assertEquals(first.keys, discover(listings.take(4).reversed(), 0).keys)
        assertEquals(1, calls)
        // Spacing still applies. A reserved call cannot turn uncached items into negative results.
        assertEquals(setOf(listings[0]), discover(listOf(listings[0], listings[4]), 0).keys)
        assertEquals(1, calls)
        now += 15_000
        assertEquals(8, discover(listings, 4).size)
        assertEquals(2, calls)
    }

    @Test fun findingsArePrivateToTheProfileAndExpire() {
        var now = ms
        val dir = temporary.newFolder()
        val cache = GeminiGuideCache(dir) { now }
        cache.remember(1, mapOf("listing" to verify(), "uncertain" to null))
        assertEquals(ms, cache.observations(1)["listing"]?.proof?.startMs)
        assertNotNull(cache.observations(1)["uncertain"])
        assertNull(cache.observations(2)["listing"])
        assertNull(cache.observations(1)["changed-programme"])
        now += SportsGuideFixtures.VALID_MS
        assertNull(GeminiGuideCache(dir) { now }.observations(1)["listing"])
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
