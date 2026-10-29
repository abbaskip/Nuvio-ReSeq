package com.nuvio.tv.data.tvdb

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Opt-in local check; credentials and login responses never enter fixtures or reports. */
class TvdbLiveIntegrationTest {
    @Test fun `live Office alternate Firefly identities and complete pagination`() = runBlocking {
        val key = System.getenv("NUVIO_TVDB_LIVE_TEST_KEY")
        assumeTrue("Set a temporary local TVDB test credential to run live checks", !key.isNullOrBlank())
        val credentialRevision = AtomicLong()
        val credentials = mockk<TvdbCredentialStore> {
            every { read(1) } returns key
            every { revision(1) } answers { credentialRevision.get() }
        }
        val logins = AtomicInteger()
        val pages = AtomicInteger()
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                if (path.endsWith("/login")) logins.incrementAndGet()
                if (path.contains("/episodes/")) pages.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
        try {
            withTimeout(90_000L) {
                val api = TvdbClient(credentials, http)
                api.testConnection(1)
                val officeId = api.seriesByRemoteId(1, "tt0386676")
                assertEquals(73244L, officeId)
                val types = api.orders(1, 73244)
                assertTrue(types.any { it.type == "alternate" })
                val aired = api.episodes(1, 73244, "official")
                val alternate = api.episodes(1, 73244, "alternate")
                assertTrue(aired.size >= 200 && alternate.size >= 200)
                assertEquals(alternate.size, alternate.map { it.id }.distinct().size)
                assertEquals(alternate.size, alternate.map { it.coordinates }.distinct().size)
                val airedById = aired.associateBy { it.id }
                assertTrue(alternate.any { airedById[it.id]?.coordinates?.let { airedCoordinates -> airedCoordinates != it.coordinates } == true })
                assertEquals("Requests should reuse the login token", 1, logins.get())
                val repository = TvdbOrderRepository(api)
                val fallback = repository.load(1, 73244, "missing-order-fixture")
                assertEquals("missing-order-fixture", fallback.selectedType)
                assertEquals("official", fallback.effectiveType)
                val fireflyAired = api.episodes(1, 78874, "official")
                val fireflyDvd = api.episodes(1, 78874, "dvd")
                assertEquals(1, fireflyAired.single { it.id == 297989L }.coordinates.episode)
                assertEquals(2, fireflyDvd.single { it.id == 297989L }.coordinates.episode)
                assertEquals(1, fireflyDvd.single { it.id == 297999L }.coordinates.episode)
                val largeSeries = api.seriesByRemoteId(1, "tt0096697")!!
                val before = pages.get()
                val complete = api.episodes(1, largeSeries, "official")
                assertTrue("Live pagination did not exercise multiple pages", pages.get() - before > 1)
                assertEquals(complete.size, complete.map { it.id }.distinct().size)
                credentialRevision.incrementAndGet()
                api.orders(1, 78874)
                assertEquals("Credential revision must replace the memory token", 2, logins.get())
                println("Live TVDB: Office official=${aired.size}, alternate=${alternate.size}; Firefly official=${fireflyAired.size}, DVD=${fireflyDvd.size}; multi-page episodes=${complete.size}, pages=${pages.get() - before}; token reuse/replacement passed")
            }
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
}
