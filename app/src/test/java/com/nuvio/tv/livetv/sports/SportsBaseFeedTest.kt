package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SportsBaseFeedTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_793_318_400_000L
    private fun body(at: Long = now, version: Int = 1, fetched: Long = at) =
        """{"schemaVersion":$version,"generatedAt":$at,"metadata":{"leagues":[]},"entries":[{"provider":"espn","leagueId":"football/nfl","fetchedAt":$fetched,"payload":{"events":[]}}]}"""

    @Test fun `valid empty source list is retained while incompatible stale and future feeds are rejected`() {
        assertEquals(1, SportsBaseFeed.parse(body(), now).entryCount)
        for (raw in listOf(body(version = 2), body(at = now + 3_600_000), body(at = now - SportsBaseFeed.MAX_AGE - 1), body(fetched = now + 3_600_000))) {
            assertTrue(runCatching { SportsBaseFeed.parse(raw, now) }.isFailure)
        }
    }

    @Test fun `persistent cache survives reopening and conditional requests do not redownload payload`() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            var clock = now
            server.enqueue(MockResponse().setBody(body()).addHeader("ETag", "fixture-v1"))
            val client = SportsBaseFeed(directory, server.url("/base.json").toString(), clock = { clock }, allowLocalhost = true)
            assertNotNull(client.refresh())
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertNotNull(client.refresh())
            assertEquals(1, server.requestCount)
            val reopened = SportsBaseFeed(directory, server.url("/base.json").toString(), clock = { clock }, allowLocalhost = true)
            assertNotNull(reopened.cached())
            assertNotNull(reopened.refresh())
            assertEquals(1, server.requestCount)
            clock += SportsBaseFeed.REFRESH + 1
            server.enqueue(MockResponse().setResponseCode(304))
            assertEquals(now, reopened.refresh()?.generatedAt)
            assertEquals("fixture-v1", server.takeRequest().getHeader("If-None-Match"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `invalid response keeps last good file and backs off without provider fallback`() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            var clock = now
            val client = SportsBaseFeed(directory, server.url("/base.json").toString(), clock = { clock }, allowLocalhost = true)
            server.enqueue(MockResponse().setBody(body()))
            client.refresh()
            clock += SportsBaseFeed.REFRESH + 1
            server.enqueue(MockResponse().setBody("{\"schemaVersion\":99}"))
            assertEquals(now, client.refresh()?.generatedAt)
            assertEquals(body(), java.io.File(directory, "base-feed.json").readText())
            assertNotNull(client.refresh())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `new valid snapshot replaces old cache but older snapshot cannot roll it back`() = runBlocking {
        MockWebServer().use { server ->
            var clock = now
            val client = SportsBaseFeed(temporary.newFolder(), server.url("/base.json").toString(), clock = { clock }, allowLocalhost = true)
            server.enqueue(MockResponse().setBody(body()))
            client.refresh()
            clock += SportsBaseFeed.REFRESH + 1
            val latest = clock
            server.enqueue(MockResponse().setBody(body(at = latest)))
            assertEquals(latest, client.refresh()?.generatedAt)
            clock += SportsBaseFeed.REFRESH + 1
            server.enqueue(MockResponse().setBody(body()))
            assertEquals(latest, client.refresh()?.generatedAt)
        }
    }

    @Test fun `failed first download backs off and expired cache is no longer displayed`() = runBlocking {
        MockWebServer().use { server ->
            var clock = now
            val client = SportsBaseFeed(temporary.newFolder(), server.url("/base.json").toString(), clock = { clock }, allowLocalhost = true)
            server.enqueue(MockResponse().setResponseCode(503))
            assertNull(client.refresh())
            assertNull(client.refresh())
            assertEquals(1, server.requestCount)
            clock += 15 * 60_000L
            server.enqueue(MockResponse().setBody(body()))
            assertNotNull(client.refresh())
            clock = now + SportsBaseFeed.MAX_AGE + 1
            assertNull(client.cached())
        }
    }

    @Test fun `disk catalogue visits payloads in order after reopening without retaining them in the header`() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val entries = (1..40).joinToString(",") { id ->
                """{"payload":{"events":[],"ignored":"${"x".repeat(10000)}"},"fetchedAt":$now,"leagueId":"league/$id","provider":"espn"}"""
            }
            // Header and payload fields need not precede their dependants.
            val raw = """{"entries":[$entries],"generatedAt":$now,"schemaVersion":1}"""
            server.enqueue(MockResponse().setBody(raw))
            val feed = SportsBaseFeed(directory, server.url("/base").toString(), clock = { now }, allowLocalhost = true)
            assertEquals(40, feed.refresh()?.entryCount)
            val reopened = SportsBaseFeed(directory, server.url("/base").toString(), clock = { now }, allowLocalhost = true)
            val seen = mutableListOf<String>()
            assertTrue(reopened.readEntries(reopened.cached()!!) { entry ->
                seen += entry.leagueId
                assertTrue(entry.payload.contains("events"))
            })
            assertEquals((1..40).map { "league/$it" }, seen)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `cleared cache is refetched immediately without a stale conditional validator`() = runBlocking {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val feed = SportsBaseFeed(directory, server.url("/base").toString(), clock = { now }, allowLocalhost = true)
            server.enqueue(MockResponse().setBody(body()).addHeader("ETag", "v1"))
            assertNotNull(feed.refresh())
            server.takeRequest()
            directory.listFiles()!!.forEach { assertTrue(it.delete()) }
            assertTrue(directory.delete())
            assertNull(feed.cached())
            server.enqueue(MockResponse().setBody(body()).addHeader("ETag", "v1"))
            assertNotNull(feed.refresh())
            assertNull(server.takeRequest().getHeader("If-None-Match"))
        }
    }

    @Test fun `stream validation rejects duplicate identities malformed events and trailing documents`() {
        val valid = body()
        val entry = """{"provider":"espn","leagueId":"football/nfl","fetchedAt":$now,"payload":{"events":[]}}"""
        for (raw in listOf(
            valid.replace("\"events\":[]", "\"events\":{}"),
            valid.replace("\"events\":[]", "\"other\":[]"),
            valid.replace("[$entry]", "[$entry,$entry]"),
            valid + "{}"
        )) assertTrue(runCatching { SportsBaseFeed.parse(raw, now) }.isFailure)
    }

    @Test fun `cancellation interrupts validation between events`() {
        val raw = body().replace("\"events\":[]", "\"events\":[" + (1..1000).joinToString(",") { "{\"id\":$it}" } + "]")
        var checks = 0
        try {
            okio.Buffer().writeUtf8(raw).use { source ->
                SportsBaseFeed.parse(source, now, checkCancelled = {
                    if (++checks == 20) throw kotlinx.coroutines.CancellationException("page closed")
                })
            }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(20, checks)
        }
    }

    @Test fun `shared feed requires https except explicitly permitted debug loopback`() {
        val dir = temporary.newFolder()
        assertTrue(runCatching { SportsBaseFeed(dir, "http://example.org/base.json") }.isFailure)
        assertTrue(runCatching { SportsBaseFeed(dir, "http://192.168.1.1/base.json", allowLocalhost = true) }.isFailure)
        assertTrue(runCatching { SportsBaseFeed(dir, "https://user:password@example.org/base.json") }.isFailure)
        assertFalse(SportsBaseFeed(dir, "").enabled)
    }

    @Test fun `catalogue live score is never presented as current and native event identity is retained`() {
        val league = League("tsdb/4416", Sport.RUGBY_LEAGUE, "NRL", "NRL")
        val raw = """{"events":[{"idEvent":"1","idLeague":"4416","dateEvent":"2026-10-30","strTime":"12:00:00","strStatus":"LIVE","strEvent":"A v B","idHomeTeam":"1","idAwayTeam":"2","strHomeTeam":"A","strAwayTeam":"B","intHomeScore":"12","intAwayScore":"0"}]}"""
        val live = FreeSportsDb.events(league, raw, now).single()
        assertTrue(live.hasScore)
        val snapshot = SportsBaseFeed.scheduled(live, now)
        assertEquals(live.id, snapshot.id)
        assertEquals(live.leagueId, snapshot.leagueId)
        assertEquals(GameState.PRE, snapshot.state)
        assertFalse(snapshot.hasScore)
        assertNull(snapshot.home?.score)
        assertEquals(GameState.POST, SportsBaseFeed.scheduled(live.copy(state = GameState.POST), now).state)
    }
}
