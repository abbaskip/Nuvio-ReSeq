package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.EpisodeCoordinates
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TvdbEpisodeResolverTest {
    private val store = mockk<TvdbOrderStore>()
    private val orders = mockk<TvdbOrderRepository>()
    private val api = mockk<TvdbClient>()
    private val tmdb = mockk<TmdbService>()
    private val original = tvdbFixtureMeta(tvdbFixtureSource())
    private val resolver = TvdbEpisodeResolver(store, orders, api, tmdb)

    @Test fun `a slow order response cannot restore a removed selection`() = runBlocking {
        var selected: TvdbOrderPreference? = TvdbOrderPreference(78874, "dvd")
        coEvery { store.selected(1, any()) } answers { selected }
        coEvery { store.sources(1, 78874) } returns emptyMap()
        coEvery { store.rememberSources(1, 78874, any()) } answers {
            thirdArg<Collection<TvdbSourceReference>>().associateBy { it.episodeId }
        }
        coEvery { orders.load(1, 78874, "dvd", any(), true) } answers { selected = null; tvdbFixtureOrder() }
        assertSame(original, resolver.apply(1, original))
    }

    @Test fun `API failure retains the selected preference and provider metadata`() = runBlocking {
        coEvery { store.selected(1, any()) } returns TvdbOrderPreference(78874, "dvd")
        coEvery { orders.load(1, 78874, "dvd", any(), true) } throws TvdbException(TvdbException.Reason.AUTHENTICATION)
        val result = resolver.apply(1, original)
        assertEquals("dvd", result.episodeOrder?.selectedType)
        assertNull(result.episodeOrder?.effectiveType)
        assertEquals(original.videos, result.videos)
        assertNotNull(result.episodeOrder?.message)
    }

    @Test fun `reentering Details reuses projection but changed provider metadata does not`() = runBlocking {
        coEvery { store.selected(1, any()) } returns TvdbOrderPreference(78874, "dvd")
        coEvery { store.sources(1, 78874) } returns emptyMap()
        coEvery { store.rememberSources(1, 78874, any()) } returns emptyMap()
        coEvery { orders.load(1, 78874, "dvd", any(), true) } returns tvdbFixtureOrder()
        val first = resolver.apply(1, original)
        assertSame(first, resolver.apply(1, original.copy()))
        assertSame(first, resolver.forOrder(1, original, TvdbOrderPreference(78874, "dvd")))
        val changed = resolver.apply(1, original.copy(name = "Updated provider title"))
        assertNotSame(first, changed)
        assertEquals("Updated provider title", changed.name)
        // Cache hits still check the order repository so its expiry/fallback policy applies.
        coVerify(exactly = 4) { orders.load(1, 78874, "dvd", any(), true) }
    }

    @Test fun `specials remain special in selected display and preserve source references`() {
        val source = tvdbFixtureSource().first().copy(id = "special", season = 0, episode = 1)
        val episode = tvdbFixtureOrder().officialEpisodes.first().copy(coordinates = EpisodeCoordinates(0, 1))
        val result = tvdbEpisodeOverlay(original.copy(videos = listOf(source)), 78874,
            TvdbEpisodeOrder("dvd", "dvd", listOf(episode), listOf(episode)))
        assertEquals(0, result.videos.single().episodeIdentity?.displayed?.season)
        assertEquals("tt0303461:0:1", result.videos.single().id)
        assertEquals("special", result.videos.single().episodeIdentity!!.providerVideoId)
        assertTrue(result.watchableEpisodes().isEmpty())
    }
}
