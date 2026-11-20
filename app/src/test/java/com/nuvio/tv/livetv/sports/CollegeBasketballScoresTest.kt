package com.nuvio.tv.livetv.sports

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollegeBasketballScoresTest {
    @get:Rule val directory = TemporaryFolder()
    private val now = 1_795_132_800_000L
    private val tip = now - 7 * 3_600_000L
    private val league = "basketball/womens-college-basketball"
    private fun side(id: String, home: Boolean) = Side(TeamInfo(league,id,id,id,id,null,null,null,null),
        null,null,false,home)
    private val scheduled = SportsEvent("espn:$league:1001",league,tip,true,GameState.PRE,"Visitors at Home",
        null,null,side("visitors",false),side("home",true),broadcasts=listOf("Original channel"),updatedAt=tip-1)
    private val final = scheduled.copy(state=GameState.POST,status="Final/OT",updatedAt=now,
        home=scheduled.home!!.copy(score="72",winner=true,periods=listOf("37","35")),
        away=scheduled.away!!.copy(score="70",periods=listOf("34","36")))
    private fun cache() = CollegeBasketballScores(File(directory.root,"scores.json"))

    @Test fun `a cold hub keeps a confirmed early final even when it was omitted from the 512 game page`() = runBlocking {
        val upcoming = (1..530).map { scheduled.copy(id="upcoming-$it",startMs=now+it) }
        val page = File(directory.root,"page.json")
        SportsPageSnapshot.write(page,upcoming+final,emptyMap(),now,tip,now+86_400_000L) { false }
        assertFalse(SportsPageSnapshot.read(page,now)!!.events.any { it.id == final.id })
        cache().remember(listOf(final), now)
        val reopened = cache().apply { read(now+1) }
        val stale = scheduled.copy(updatedAt=now+1,broadcasts=listOf("Updated channel"),note="Schedule metadata")
        val restored = reopened.apply(stale,now+1)
        assertEquals(GameState.POST,restored.state)
        assertEquals("Final/OT",restored.status)
        assertEquals(final.home,restored.home)
        assertEquals(final.away,restored.away)
        assertEquals(stale.broadcasts,restored.broadcasts)
        assertEquals(stale.note,restored.note)
        assertEquals(final.id,restored.id)
        assertFalse(restored.awaitingCollegeStatus(now+1))
        val unseen = stale.copy(id = "unseen")
        assertSame(unseen, reopened.apply(unseen, now + 1))
        val live = scheduled.copy(id="live",state=GameState.LIVE,startMs=now-60_000)
        assertEquals(listOf(restored,live),SportsEventListings.allEvents(listOf(live,restored),tip-1,now+86_400_000,now))
    }

    @Test fun `score corrections survive another restart and a moved or cancelled game gets no cached score`() {
        val first = cache()
        first.remember(listOf(final),now)
        val corrected = final.copy(updatedAt=now+100,home=final.home!!.copy(score="73"))
        first.remember(listOf(corrected),now+100)
        first.remember(listOf(final),now+200)
        val reopened = cache().apply { read(now+300) }
        assertEquals("73",reopened.apply(scheduled,now+300).home?.score)
        val moved = scheduled.copy(startMs=now+3_600_000,updatedAt=now+300)
        val changedTeam = scheduled.copy(home=side("replacement",true))
        val cancelled = scheduled.copy(state=GameState.OFF,updatedAt=now+300)
        for (game in listOf(moved,changedTeam,cancelled)) assertSame(game,reopened.apply(game,now+300))
        reopened.remember(listOf(cancelled),now+300)
        assertSame(scheduled,cache().apply { read(now+301) }.apply(scheduled,now+301))
    }

    @Test fun `live scores expire at the existing freshness boundary and do not turn into a final`() {
        val live = final.copy(state=GameState.LIVE,status="2nd 04:19")
        cache().remember(listOf(live),now)
        val reopened = cache().apply { read(now+500) }
        assertEquals("2nd 04:19",reopened.apply(scheduled,now+500).status)
        assertSame(scheduled,reopened.apply(scheduled,now+SportsPageSnapshot.SCORE_AGE+1))
        assertSame(scheduled,cache().apply { read(now+SportsPageSnapshot.SCORE_AGE+1) }.apply(scheduled,now+SportsPageSnapshot.SCORE_AGE+1))
        val nba = scheduled.copy(leagueId="basketball/nba")
        val airing = scheduled.copy(source=EventSource.GUIDE)
        assertSame(nba,reopened.apply(nba,now+500))
        assertSame(airing,reopened.apply(airing,now+500))
    }
}
