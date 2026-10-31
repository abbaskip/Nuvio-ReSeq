package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeminiGuideMatchingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_793_318_400_000L

    @Test fun `hourly request limit and spacing survive restarting the cache`() {
        val dir = temporary.newFolder()
        var clock = now
        val cache = GeminiGuideCache(dir) { clock }
        repeat(12) {
            assertTrue(cache.reserve())
            assertFalse(cache.reserve())
            clock += 15_000
        }
        assertFalse(GeminiGuideCache(dir) { clock }.reserve())
        clock = now + 61 * 60_000
        assertTrue(GeminiGuideCache(dir) { clock }.reserve())
    }
    @Test fun `rolling daily limit is shared across cache instances`() {
        val dir = temporary.newFolder()
        var clock = now
        repeat(40) { index ->
            clock = now + (index / 12) * 61 * 60_000L + (index % 12) * 15_000
            assertTrue(GeminiGuideCache(dir) { clock }.reserve())
        }
        clock += 15_000
        assertFalse(GeminiGuideCache(dir) { clock }.reserve())
    }
    @Test fun `failed request cooldown persists and a backward clock cannot reset usage`() {
        val dir = temporary.newFolder()
        var clock = now
        val cache = GeminiGuideCache(dir) { clock }
        assertTrue(cache.reserve())
        cache.pause()
        assertFalse(GeminiGuideCache(dir) { clock }.reserve())
        clock += 15 * 60_000
        assertTrue(GeminiGuideCache(dir) { clock }.reserve())
        clock = now - 60_000
        assertFalse(GeminiGuideCache(dir) { clock }.reserve())
    }
    @Test fun `decisions are profile scoped content keyed and expire`() {
        val dir = temporary.newFolder()
        var clock = now
        val cache = GeminiGuideCache(dir) { clock }
        cache.put(1, "fingerprint", GuideMatchDecision.MATCH)
        assertEquals(GuideMatchDecision.MATCH, GeminiGuideCache(dir) { clock }.get(1, "fingerprint"))
        assertNull(cache.get(2, "fingerprint"))
        assertNull(cache.get(1, "different-input"))
        clock += 3 * 24 * 60 * 60_000L
        assertNull(cache.get(1, "fingerprint"))
    }
    @Test fun `guide verification data excludes stream urls credentials and opaque identifiers`() {
        val event = SportsEvent("public-id", "basketball/nba", now, true, GameState.PRE, "Lakers at Clippers", null, null, null, null)
        val programme = EpgProgram(now, now + 3_600_000, "NBA Basketball",
            description = "LAL @ LAC https://username:password@private.example/live?token=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val data = GeminiGuideInput.data(event, programme)
        assertFalse(data.toString().contains("username"))
        assertFalse(data.toString().contains("password"))
        assertFalse(data.toString().contains("private.example"))
        assertEquals(GeminiGuideInput.key(data), GeminiGuideInput.key(data))
        assertNotEquals(GeminiGuideInput.key(data), GeminiGuideInput.key(GeminiGuideInput.data(event, programme.copy(title = "Different fixture"))))
    }
    @Test fun `only explicit structured decisions are accepted`() {
        fun response(value: String) = """{"candidates":[{"content":{"parts":[{"text":"{\"decision\":\"$value\"}"}]}}]}"""
        assertEquals(GuideMatchDecision.MATCH, GeminiGuideInput.decision(response("match")))
        assertEquals(GuideMatchDecision.DIFFERENT, GeminiGuideInput.decision(response("different")))
        assertEquals(GuideMatchDecision.UNCERTAIN, GeminiGuideInput.decision(response("uncertain")))
        assertTrue(runCatching { GeminiGuideInput.decision(response("yes")) }.isFailure)
    }
}
