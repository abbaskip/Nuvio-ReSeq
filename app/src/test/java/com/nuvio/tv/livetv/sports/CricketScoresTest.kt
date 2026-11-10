package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CricketScoresTest {
    private fun fixture() = JSONObject(requireNotNull(javaClass.getResourceAsStream(
        "/sports/pakistan-sri-lanka-test.json")).bufferedReader().use { it.readText() })
    private fun parse(raw: JSONObject) = SportsFeeds.parseScoreboard(SportsCatalog.league("cricket/19430")!!, raw.toString()).single()

    @Test fun inningsUseRunsWicketsAndOversRatherThanEspnsNumericOrdinal() {
        val event = parse(fixture())
        assertEquals(listOf("342/5 (85 ov)"), event.home!!.periods)
        assertTrue("an unbatted team's placeholder is not a 0-run innings", event.away!!.periods.isEmpty())
        assertEquals(CricketScoreText("342/5", "85 ov"), cricketInningsScores(event.home!!).single())
        assertEquals("BY INNINGS", periodHeading(Sport.CRICKET))
    }

    @Test fun declaredInningsAndAnUnbattedOpponentRemainReadable() {
        val raw = fixture()
        val home = raw.getJSONArray("events").getJSONObject(0).getJSONArray("competitions")
            .getJSONObject(0).getJSONArray("competitors").getJSONObject(0)
        home.put("score", "434/9d (106.3 ov)")
        home.getJSONArray("linescores").getJSONObject(0).put("runs", 434).put("wickets", 9)
            .put("overs", 106.3).put("description", "declared")
        val event = parse(raw)
        assertEquals(listOf("434/9d (106.3 ov)"), event.home!!.periods)
        assertEquals(CricketScoreText("434/9d", "106.3 ov"), cricketScoreText(event.home!!.score))
        assertEquals(CricketScoreText("Yet to bat", null), cricketScoreText(event.away!!.score))
    }

    @Test fun oldCachedOrdinalsFallBackToTheRealScoreAndSeparateBothTestInnings() {
        val side = parse(fixture()).home!!.copy(periods = listOf("1", "2"), score = "434/9d & 182/3 (51.2 ov)")
        assertEquals(listOf(CricketScoreText("434/9d", null), CricketScoreText("182/3", "51.2 ov")), cricketInningsScores(side))
        assertEquals(CricketScoreText("0/0", "0 ov"), cricketScoreText("0/0 (0 ov)"))
    }
}
