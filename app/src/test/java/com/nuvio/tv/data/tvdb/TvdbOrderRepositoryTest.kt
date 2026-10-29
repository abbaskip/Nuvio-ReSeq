package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.EpisodeCoordinates
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvdbOrderRepositoryTest {
    private val api = mockk<TvdbClient>()
    private val official = listOf(TvdbEpisode(297989, EpisodeCoordinates(1, 1), "The Train Job", null, null, null, null))
    private val dvd = listOf(official.single().copy(coordinates = EpisodeCoordinates(1, 2)))

    @Test fun `related empty orders never trigger season fallback or repeat empty reads but remain selectable`() = runTest {
        coEvery { api.orders(1, 78874) } returns listOf(TvdbOrder("official", "Aired"), TvdbOrder("dvd", "DVD"), TvdbOrder("alttwo", "Extended"))
        coEvery { api.episodes(1, 78874, "official", "eng") } returns official
        coEvery { api.episodes(1, 78874, "dvd", "eng") } returns dvd
        coEvery { api.episodes(1, 78874, "alttwo", "eng") } returns emptyList()
        coEvery { api.episodesFromSeasons(1, 78874, "alttwo") } returns dvd
        val repository = TvdbOrderRepository(api)
        repeat(2) { assertEquals(dvd, repository.load(1, 78874, "dvd", includeRelated = true).episodes) }
        coVerify(exactly = 1) { api.episodes(1, 78874, "alttwo", "eng") }
        coVerify(exactly = 0) { api.episodesFromSeasons(any(), any(), any()) }
        assertEquals(dvd, repository.load(1, 78874, "alttwo", includeRelated = true).episodes)
        coVerify(exactly = 1) { api.episodesFromSeasons(1, 78874, "alttwo") }
    }

    @Test fun `slow related metadata cannot discard a successfully loaded selected order`() = runTest {
        coEvery { api.orders(1, 78874) } returns listOf(TvdbOrder("dvd", "DVD"), TvdbOrder("slow", "Slow"))
        coEvery { api.episodes(1, 78874, "official", "eng") } returns official
        coEvery { api.episodes(1, 78874, "dvd", "eng") } returns dvd
        coEvery { api.episodes(1, 78874, "slow", "eng") } coAnswers { kotlinx.coroutines.delay(30_000); official }
        val result = TvdbOrderRepository(api).load(1, 78874, "dvd", includeRelated = true)
        assertEquals(dvd, result.episodes)
        assertEquals("dvd", result.effectiveType)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test fun `available alternatives can use season membership without losing official identity`() = runTest {
        coEvery { api.orders(1, 78874) } returns listOf(TvdbOrder("dvd", "DVD"))
        coEvery { api.episodes(1, 78874, "official", "eng") } returns official
        coEvery { api.episodes(1, 78874, "dvd", "eng") } returns emptyList()
        coEvery { api.episodesFromSeasons(1, 78874, "dvd") } returns dvd
        val result = TvdbOrderRepository(api).load(1, 78874, "dvd")
        assertEquals(dvd, result.episodes)
        assertEquals("dvd", result.effectiveType)
        assertEquals(official, result.officialEpisodes)
    }

    @Test fun `a failed season fetch cannot publish a partial alternative`() = runTest {
        coEvery { api.orders(1, 78874) } returns listOf(TvdbOrder("dvd", "DVD"))
        coEvery { api.episodes(1, 78874, "official", "eng") } returns official
        coEvery { api.episodes(1, 78874, "dvd", "eng") } returns emptyList()
        coEvery { api.episodesFromSeasons(1, 78874, "dvd") } throws TvdbException(TvdbException.Reason.NETWORK)
        try { TvdbOrderRepository(api).load(1, 78874, "dvd"); fail("Partial order was accepted") }
        catch (e: TvdbException) { assertEquals(TvdbException.Reason.NETWORK, e.reason) }
    }

    @Test fun `complete cache avoids rediscovery and episode requests`() = runTest {
        coEvery { api.orders(1, 78874) } returns listOf(TvdbOrder("dvd", "DVD"))
        coEvery { api.episodes(1, 78874, "official", "eng") } returns official
        coEvery { api.episodes(1, 78874, "dvd", "eng") } returns dvd
        val repository = TvdbOrderRepository(api)
        val first = repository.load(1, 78874, "dvd")
        assertEquals(first, repository.load(1, 78874, "dvd"))
        assertEquals("dvd", first.effectiveType)
        assertEquals(297989L, first.episodes.single().id)
        assertEquals(1, first.officialEpisodes.single().coordinates.episode)
        coVerify(exactly = 1) { api.orders(any(), any()) }
        coVerify(exactly = 1) { api.episodes(1, 78874, "dvd", "eng") }
    }

    @Test fun `switching selected orders reuses official metadata without replacing the selected order`() = runTest {
        coEvery { api.orders(1, 78874) } returns listOf(TvdbOrder("dvd", "DVD"), TvdbOrder("alternate", "Alternate"))
        coEvery { api.episodes(1, 78874, "official", "eng") } returns official
        coEvery { api.episodes(1, 78874, "dvd", "eng") } returns dvd
        val alternate = listOf(official.single().copy(coordinates = EpisodeCoordinates(2, 1)))
        coEvery { api.episodes(1, 78874, "alternate", "eng") } returns alternate
        val repository = TvdbOrderRepository(api)
        assertEquals(dvd, repository.load(1, 78874, "dvd").episodes)
        assertEquals(alternate, repository.load(1, 78874, "alternate").episodes)
        val aired = repository.load(1, 78874, "official")
        assertEquals("official", aired.selectedType)
        assertEquals(official, aired.episodes)
        coVerify(exactly = 1) { api.episodes(1, 78874, "official", "eng") }
    }

    @Test fun `unavailable order uses official without changing selected type`() = runTest {
        coEvery { api.orders(1, 78874) } returns listOf(TvdbOrder("official", "Aired"))
        coEvery { api.episodes(1, 78874, "official", "eng") } returns official
        val value = TvdbOrderRepository(api).load(1, 78874, "dvd")
        assertEquals("dvd", value.selectedType)
        assertEquals("official", value.effectiveType)
        assertEquals(official, value.episodes)
        coVerify(exactly = 0) { api.episodes(1, 78874, "dvd", "eng") }
    }

    @Test fun `authentication failure never becomes an unavailable order`() = runTest {
        coEvery { api.orders(1, 78874) } throws TvdbException(TvdbException.Reason.AUTHENTICATION)
        try { TvdbOrderRepository(api).load(1, 78874, "dvd"); fail("Authentication hidden") }
        catch (e: TvdbException) { assertEquals(TvdbException.Reason.AUTHENTICATION, e.reason) }
        coVerify(exactly = 0) { api.episodes(any(), any(), any(), any()) }
    }
}
