package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.*
import org.junit.Test

class SportsCurrentGuideTest {
    private val now = 1_794_312_000_000L
    private val hour = 3_600_000L
    private val channel = LiveChannel("505", "playlist", "Test", "AU: Fox Sports 505 HD",
        null, null, null, "sports", "Sports", 1, "https://example.invalid")
    private val programme = EpgProgram(now - 7 * hour, now + hour, "Test: Pakistan vs Sri Lanka 1st Test Day 2 live")
    private fun fixture(): SportsEvent = SportsFeeds.parseScoreboard(SportsCatalog.league("cricket/19430")!!,
        requireNotNull(javaClass.getResourceAsStream("/sports/pakistan-sri-lanka-test.json"))
            .bufferedReader().use { it.readText() }).single()
    private fun airing(p: EpgProgram = programme) = fixture().copy(id = "guide-current", source = EventSource.GUIDE,
        startMs = p.startMs, endMs = p.stopMs, title = p.title, subtitle = p.description,
        home = null, away = null, espnId = null, guideHits = listOf(channel.key to p))
    private fun match(p: EpgProgram? = programme, score: Int = 100) = ChannelMatch(channel, score, "Guide", p, "HD", false)

    @Test fun stumpsOverridesLiveTvMetadataWithoutRemovingTheAiring() {
        val stopped = fixture().copy(state = GameState.OFF, status = "Stumps")
        val shown = SportsCurrentGuide.withFixtures(listOf(airing()), listOf(stopped),
            mapOf(stopped.id to listOf(match())), now).single()
        assertEquals(GameState.OFF, shown.state)
        assertEquals("Stumps", shown.status)
        assertEquals(airing().id, shown.id)
        assertEquals(listOf(stopped), SportsCurrentGuide.fixtures(listOf(stopped)))
    }

    @Test fun scoreAndClockUpdatesDoNotScheduleAnotherDiscoveryButFixtureChangesDo() {
        val fixture = fixture()
        val before = SportsCurrentGuide.fixtureKeys(listOf(fixture, airing()))
        val scored = fixture.copy(status = "Day 2 Session 3", updatedAt = now,
            home = fixture.home!!.copy(score = "450/9d", periods = listOf("450/9d")))
        assertEquals(before, SportsCurrentGuide.fixtureKeys(listOf(scored, airing().copy(title = "Changed guide"))))
        assertNotEquals(before, SportsCurrentGuide.fixtureKeys(listOf(fixture.copy(state = GameState.POST))))
        assertNotEquals(before, SportsCurrentGuide.fixtureKeys(listOf(fixture.copy(startMs = fixture.startMs + hour))))
    }

    @Test fun provenCurrentAiringShowsItsLiveFixtureScoreWithoutChangingItsProgrammeIdentity() {
        val fixture = fixture()
        val airing = airing()
        val shown = SportsCurrentGuide.withFixtures(listOf(airing), listOf(fixture), mapOf(fixture.id to listOf(match())), now).single()
        assertEquals(airing.id, shown.id)
        assertEquals(airing.startMs, shown.startMs)
        assertEquals(airing.endMs, shown.endMs)
        assertEquals(airing.guideHits, shown.guideHits)
        assertEquals(EventSource.GUIDE, shown.source)
        assertEquals(fixture.home, shown.home)
        assertEquals(fixture.away, shown.away)
        assertEquals(fixture.espnId, shown.espnId)
        assertTrue(shown.hasScore)
        assertEquals("LIVE", shown.liveLabel)
    }

    @Test fun replaysAmbiguitySuggestionsAndBroadcasterOnlyPicksNeverAcquireALiveScore() {
        val fixture = fixture()
        val other = fixture.copy(id = "another-fixture")
        val airing = airing()
        fun shown(matches: Map<String, List<ChannelMatch>>, air: SportsEvent = airing, fixtures: List<SportsEvent> = listOf(fixture)) =
            SportsCurrentGuide.withFixtures(listOf(air), fixtures, matches, now).single()
        assertFalse(shown(mapOf(fixture.id to listOf(match(score = 65)))).hasScore)
        assertFalse(shown(mapOf(fixture.id to listOf(match(p = null, score = 200)))).hasScore)
        assertFalse(shown(mapOf(fixture.id to listOf(match(p = programme.copy(startMs = programme.startMs - 1))))).hasScore)
        assertFalse(shown(mapOf(fixture.id to listOf(match())), fixtures = listOf(fixture.copy(state = GameState.POST))).hasScore)
        val ambiguous = mapOf(fixture.id to listOf(match()), other.id to listOf(match()))
        assertFalse(shown(ambiguous, fixtures = listOf(fixture, other)).hasScore)
        assertTrue(SportsCurrentGuide.additionalAirings(emptyList(), listOf(fixture, other), ambiguous, now).isEmpty())
        val replay = programme.copy(title = "Replay: Test Pakistan vs Sri Lanka")
        assertFalse(shown(mapOf(fixture.id to listOf(match(replay))), airing(replay)).hasScore)
    }

    @Test fun refreshRemovesOnlyItsOwnAutomaticMatchesAndPreservesUserChoices() {
        val fixture = fixture()
        val automatic = match()
        val suggestion = match(p = null, score = 60).copy(channel = channel.copy(key = "other"))
        val own = mapOf(fixture.id to listOf(automatic))
        val previous = mapOf(fixture.id to listOf(automatic, suggestion), "unrelated" to listOf(suggestion))
        val removed = SportsCurrentGuide.mergeMatches(previous, own, emptyMap(), SportsSettings())
        assertEquals(listOf(suggestion), removed[fixture.id])
        assertEquals(previous["unrelated"], removed["unrelated"])

        val pick = match(p = null, score = 200)
        val fresh = automatic.copy(program = programme.copy(startMs = now, stopMs = now + hour))
        val updated = SportsCurrentGuide.mergeMatches(mapOf(fixture.id to listOf(pick)), own,
            mapOf(fixture.id to listOf(fresh)), SportsSettings())[fixture.id]!!.single()
        assertEquals(200, updated.score)
        assertEquals(channel.key, updated.channel.key)
        assertEquals(fresh.program, updated.program)
    }

    @Test fun newGuideDiscoveriesKeepEveryConfirmedChannelAndNeverDuplicateExistingAirings() {
        val fixture = fixture()
        val other = match().copy(channel = channel.copy(key = "quality-feed"))
        val matches = mapOf(fixture.id to listOf(match(), other))
        val extra = SportsCurrentGuide.additionalAirings(emptyList(), listOf(fixture), matches, now).single()
        assertEquals(setOf("505", "quality-feed"), extra.guideHits.map { it.first }.toSet())
        assertEquals(EventSource.GUIDE, extra.source)
        assertFalse(extra.hasScore) // Scores are projected from the freshest fixture at display time.
        assertTrue(SportsCurrentGuide.additionalAirings(listOf(extra), listOf(fixture), matches, now).isEmpty())
        assertTrue(SportsCurrentGuide.additionalAirings(emptyList(), listOf(fixture), matches, programme.stopMs).isEmpty())
    }
}
