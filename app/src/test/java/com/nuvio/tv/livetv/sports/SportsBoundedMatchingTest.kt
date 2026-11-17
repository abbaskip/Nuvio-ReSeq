package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class SportsBoundedMatchingTest {
    private val now=1_794_477_600_000L
    private fun channel(i:Int)=LiveChannel("ch$i","p","Fixture","NBA CLE vs ATL ${if(i%2==0) "HD" else "SD"} $i",
        null,null,null,"sport","Sports",i,"https://fixture.invalid/$i")
    private val event=SportsEvent("espn:basketball/nba:1","basketball/nba",now,true,GameState.LIVE,"Cleveland Cavaliers at Atlanta Hawks",null,null,
        Side(TeamInfo("basketball/nba","CLE","Cleveland Cavaliers","Cleveland Cavaliers","CLE",null,null,null,null),null,null,false,false),
        Side(TeamInfo("basketball/nba","ATL","Atlanta Hawks","Atlanta Hawks","ATL",null,null,null,null),null,null,false,true))
    @Test fun `bounded candidates retain the exact best ranked channels including late better entries`() {
        val channels=(1..1500).map(::channel)
        for(best in listOf(true,false)) {
            val input=SportsMatcher.Input(listOf(event),channels,mapOf("ch1500" to "4K"),0,SportsSettings(bestChannelFirst=best),now,includeGuide=false)
            val oracle=SportsMatcher(mockk(),limits=SportsMatchLimits.UNCAPPED).match(input).matches.getValue(event.id)
            val actual=SportsMatcher(mockk()).match(input).matches.getValue(event.id)
            val expected=oracle.take(SportsMatchLimits.DEFAULT.perFixture)
            // sortMatches deliberately leaves equal-ranked channels tied. Retention may select
            // either tied channel; scores, quality and ordering must still be the exact best N.
            if(best) {
                fun ranks(list:List<ChannelMatch>)=list.map { Triple(it.score,it.quality,it.channel.name.length) }
                assertEquals(ranks(expected),ranks(actual))
                assertEquals("ch1500",actual.first().channel.key)
            } else assertEquals(expected,actual)
            assertTrue(actual.size<=SportsMatchLimits.DEFAULT.perFixture)
        }
    }
    @Test fun `pathological repeated participants cannot retain a fixture times channel product`() {
        val channels=(1..10_000).map(::channel)
        val fixtures=(1..100).map { event.copy(id="fixture$it") }
        val result=SportsMatcher(mockk(),limits=SportsMatchLimits(totalMatches=512)).match(
            SportsMatcher.Input(fixtures,channels,emptyMap(),0,SportsSettings(),now,includeGuide=false)).matches
        assertTrue(result.values.all { it.size<=SportsMatchLimits.DEFAULT.perFixture })
        assertTrue(result.values.sumOf { it.size }<=512)
        assertTrue(result.isNotEmpty())
    }
    @Test fun `indexed current snapshot is reused until a programme boundary or guide revision`() {
        var revision="1"
        val p=EpgProgram(now-600_000,now+3_600_000,"NBA: Cleveland Cavaliers at Atlanta Hawks")
        val ch=channel(1)
        val db=mockk<EpgDatabase> {
            every { contentRevision() } answers { revision }
            every { nextSportsBoundary(any()) } returns p.stopMs
            every { visitSportsPrograms(any(),any(),any(),any(),any()) } answers {
                lastArg<(String,EpgProgram)->Unit>()(ch.key,p)
            }
        }
        val matcher=SportsMatcher(db,incremental=true)
        fun snapshot(at:Long)=matcher.tvNowSnapshot(SportsCatalog.all,emptyList(),mapOf(ch.key to ch),0,at,listOf(event))
        val first=snapshot(now)
        assertSame(first,snapshot(now+1))
        assertTrue(first.fixturePrograms.isNotEmpty())
        snapshot(p.stopMs)
        revision="2"; snapshot(p.stopMs+1)
        verify(exactly=3) { db.visitSportsPrograms(any(),any(),any(),any(),any()) }
    }
    @Test fun `indexed ranked set updates do not accumulate stale tree nodes`() {
        val retained=RankedCandidates<Int,Int>(8,compareBy { it })
        repeat(100_000) { retained.offer(it%8,-it) }
        assertEquals(8,retained.size)
        assertEquals((-99_999..-99_992).toSet(),retained.values.toSet())
    }

    @Test fun `guide retention uses the same quality ranking and keeps a late better feed`() {
        val channels=(1..1500).map(::channel).associateBy { it.key }
        val p=EpgProgram(now,now+3_600_000,"NBA Basketball: Cleveland Cavaliers at Atlanta Hawks")
        val db=mockk<EpgDatabase> {
            every { fingerprint() } returns "guide"
            every { contentRevision() } returns "1"
            every { visitSportsPrograms(any(),any(),any(),any(),any()) } answers {
                channels.keys.forEach { lastArg<(String,EpgProgram)->Unit>()(it,p) }
            }
        }
        val quality=mapOf("ch1500" to "4K")
        val discovered=SportsMatcher(db).guideEvents(now,now+3_600_000,SportsCatalog.all,emptyList(),channels,0,now,
            quality=quality)
        assertEquals(1,discovered.size)
        assertEquals(SportsMatchLimits.DEFAULT.perFixture,discovered.single().guideHits.size)
        val ranked=SportsMatcher(db).match(SportsMatcher.Input(discovered,channels.values.toList(),quality,0,
            SportsSettings(),now,includeGuide=false)).matches.getValue(discovered.single().id)
        assertEquals("ch1500",ranked.first().channel.key)
        assertTrue(ranked.any { it.quality=="HD" })
    }

    @Test fun `current and bulk result merges cannot grow past the per fixture bound`() {
        val p=EpgProgram(now,now+3_600_000,event.title)
        val early=(1..1024).map { ChannelMatch(channel(it),90,"Guide",p,"HD",false) }
        val later=(1025..2048).map { ChannelMatch(channel(it),100,"Guide",p,"4K",false) }
        val merged=SportsCurrentGuide.mergeMatches(mapOf(event.id to early),emptyMap(),mapOf(event.id to later),SportsSettings())
        assertEquals(1024,merged.getValue(event.id).size)
        assertTrue(merged.getValue(event.id).all { it.score==100 && it.quality=="4K" })
    }
    @Test fun `old cache restores the best bounded results instead of materializing all matches`() {
        val directory=kotlin.io.path.createTempDirectory().toFile()
        try {
            val file=java.io.File(directory,"matches.json")
            val channels=(1..3000).map(::channel).associateBy { it.key }
            val items=channels.values.map { ChannelMatch(it,if(it.number>2000) 100 else 90,"Guide",null,"HD",false) }
            SportsMatchCache.write(file,mapOf(event.id to items))
            val result=SportsMatchCache.read(file,channels,limits=SportsMatchLimits(perFixture=128,totalMatches=64))
            assertEquals(64,result.getValue(event.id).size)
            assertTrue(result.getValue(event.id).all { it.score==100 })
            assertEquals(items,SportsMatchCache.read(file,channels).getValue(event.id))
        } finally { directory.deleteRecursively() }
    }
}
