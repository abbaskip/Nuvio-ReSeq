package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideWindowResidencyTest {
    @Test fun `fifty thousand channels never warm more than six hundred programme windows`() {
        val owner=GuideWindowResidency()
        owner.warm((0 until 50_001).map { "channel$it" })
        assertEquals(600,owner.keys().size)
        val data=(0 until 50_001).associate { "channel$it" to listOf(EpgProgram(1,3,"Long programme title".repeat(10))) }
        assertEquals(owner.keys().toSet(),owner.retain(data).keys)
        assertEquals(600,owner.retain(data).values.sumOf { it.size })
    }
    @Test fun `visible rows outside the first group load and evict old windows without changing their schedules`() {
        val owner=GuideWindowResidency(3)
        owner.warm(listOf("one","two","three"))
        val saved=listOf(EpgProgram(1,3,"Programme"),EpgProgram(3,6,"Next"))
        val first=owner.retain(mapOf("one" to saved,"two" to saved,"three" to saved))
        owner.request(listOf("far-row"))
        val current=owner.retain(first+mapOf("far-row" to saved))
        assertFalse("one" in current)
        assertSame(saved,current["far-row"])
        // A late warm-up cannot evict the row now on screen or resurrect its old read.
        owner.warm(listOf("one"))
        assertFalse("one" in owner.retain(current+mapOf("one" to saved)))
        owner.request(listOf("one"))
        assertSame(saved,owner.retain(current+mapOf("one" to saved))["one"])
    }
    @Test fun `removed playlists release their warm demand`() {
        val owner=GuideWindowResidency(2)
        owner.warm(listOf("old|one","old|two"))
        owner.restrict(setOf("new|one"));owner.warm(listOf("new|one"))
        assertEquals(listOf("new|one"),owner.keys())
    }
}
