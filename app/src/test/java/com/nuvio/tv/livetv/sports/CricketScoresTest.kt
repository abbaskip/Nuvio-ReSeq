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
        assertEquals(CricketScoreText("", null), cricketScoreText(event.away!!.score))
    }

    @Test fun oldCachedOrdinalsFallBackToTheRealScoreAndSeparateBothTestInnings() {
        val side = parse(fixture()).home!!.copy(periods = listOf("1", "2"), score = "434/9d & 182/3 (51.2 ov)")
        assertEquals(listOf(CricketScoreText("434/9d", null), CricketScoreText("182/3", "51.2 ov")), cricketInningsScores(side))
        assertEquals(CricketScoreText("0/0", "0 ov"), cricketScoreText("0/0 (0 ov)"))
    }

    @Test fun matchInningsExcludeTheOtherTeamsZeroPlaceholders() {
        val raw = fixture()
        val competitors = raw.getJSONArray("events").getJSONObject(0).getJSONArray("competitions")
            .getJSONObject(0).getJSONArray("competitors")
        fun line(period: Int, runs: Int, wickets: Int, batting: Boolean, overs: Double, description: String = "") =
            JSONObject().put("period", period).put("displayValue", period).put("value", period)
                .put("runs", runs).put("wickets", wickets).put("isBatting", batting)
                .put("overs", overs).put("description", description)
        competitors.getJSONObject(0).put("score", "434/9d").put("linescores", org.json.JSONArray()
            .put(line(1, 434, 9, true, 106.3, "declared")).put(line(2, 0, 0, false, 12.0)))
        competitors.getJSONObject(1).put("score", "42/2 (12 ov)").put("linescores", org.json.JSONArray()
            .put(line(1, 0, 0, false, 106.3, "declared")).put(line(2, 42, 2, true, 12.0)))
        val event = parse(raw)
        assertEquals(listOf(CricketScoreText("434/9d", "106.3 ov")), cricketInningsScores(event.home!!))
        assertEquals(listOf(CricketScoreText("42/2", "12 ov")), cricketInningsScores(event.away!!))

        // A genuine new innings remains visible even before the first run or wicket.
        competitors.getJSONObject(1).put("score", "0/0 (0 ov)").getJSONArray("linescores")
            .getJSONObject(1).put("runs", 0).put("wickets", 0).put("overs", 0)
        assertEquals(listOf(CricketScoreText("0/0", "0 ov")), cricketInningsScores(parse(raw).away!!))
    }
}
