package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the complete scorer against legacy SQL candidates and the new indexed stream. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class SportsIndexedMatchingParityTest {
    private val context=RuntimeEnvironment.getApplication()
    private val db=EpgDatabase(context)
    private val now=1_794_866_820_000L
    @After fun close() { db.close();context.deleteDatabase("livetv_guide.db") }
    private fun fixture(id:String,league:String,a:String,b:String)=SportsEvent(id,league,now,true,GameState.LIVE,
        "$a at $b",null,null,
        Side(TeamInfo(league,"a-$id",a,a,a.take(3),null,null,null,null),null,null,false,false),
        Side(TeamInfo(league,"b-$id",b,b,b.take(3),null,null,null,null),null,null,false,true))
    @Test fun `indexed candidate selection preserves complete legacy channel identity score and ranking`() {
        val events=listOf(fixture("nba","basketball/nba","Cleveland Cavaliers","Atlanta Hawks"),
            fixture("test","cricket/19430","South Africa","Bangladesh"),
            fixture("football","soccer/eng.1","Manchester United","Liverpool"))
        val titles=listOf("NBA Basketball: Cleveland Cavaliers at Atlanta Hawks","Live Test Cricket: South Africa v Bangladesh",
            "Live: Manchester United vs Liverpool","Sports News: Cleveland Cavaliers at Atlanta Hawks",
            "The World at One","News from South Africa","Cleveland home renovation","Liverpool traffic",
            "Replay: Manchester United vs Liverpool","Live: South Australia v Bangladesh")
        val channels=(0 until 60).map { i -> LiveChannel("ch$i","fixture","Fixture","Sports Network $i HD",
            null,null,null,"sports","Sports",i,"https://fixture.invalid/$i") }
        db.replaceAll("mixed",channels.associate { ch -> ch.key to listOf(EpgProgram(now-600_000,now+3_600_000,
            titles[ch.number%titles.size],"Scheduled programme")) },emptyMap())
        val legacy=mockk<EpgDatabase>(relaxed=true)
        every { legacy.visitMatchPrograms(any(),any(),any(),any(),any(),any()) } answers {
            val words=arg<Collection<String>>(3).map { it.trim() }.filter { it.length>=3 }.distinct()
            val conditions=words.flatMap { listOf("title LIKE ? ESCAPE '\\'","descr LIKE ? ESCAPE '\\'") }.joinToString(" OR ")
            fun like(w:String)="%"+w.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
            val args=listOf(firstArg<Long>().toString(),secondArg<Long>().toString(),thirdArg<Long>().toString())+
                words.flatMap { listOf(like(it),like(it)) }
            val listings="(SELECT ch,start,stop,title,descr,cat FROM programs WHERE shared=0 UNION ALL " +
                "SELECT a.ch,p.start,p.stop,p.title,p.descr,p.cat FROM schedule_channels a JOIN programs p ON p.ch=a.ref AND p.shared=1)"
            val rows=db.readableDatabase.rawQuery("SELECT ch,start,stop,title,descr,cat FROM $listings " +
                "WHERE start>=? AND start<? AND stop>? AND ($conditions) LIMIT 4000",args.toTypedArray()).use { cursor ->
                buildList { while(cursor.moveToNext()) add(cursor.getString(0) to EpgProgram(cursor.getLong(1),cursor.getLong(2),
                    cursor.getString(3),if(cursor.isNull(4)) null else cursor.getString(4),if(cursor.isNull(5)) null else cursor.getString(5))) }
            }
            (if(arg<Boolean>(4)) rows.sortedBy { it.second.startMs } else rows).forEach { (ch,p) ->
                lastArg<(String,EpgProgram)->Unit>()(ch,p)
            }
        }
        for(bestFirst in listOf(true,false)) {
            val input=SportsMatcher.Input(events,channels,emptyMap(),0,SportsSettings(bestChannelFirst=bestFirst),now)
            val expected=SportsMatcher(legacy,limits=SportsMatchLimits.UNCAPPED).match(input).matches
            assertTrue(expected.keys.toString(),expected.keys.containsAll(setOf("nba","test","football")))
            assertEquals(expected,SportsMatcher(db,limits=SportsMatchLimits.UNCAPPED).match(input).matches)
        }
    }
}
