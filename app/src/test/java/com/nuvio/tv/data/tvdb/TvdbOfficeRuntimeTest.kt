package com.nuvio.tv.data.tvdb

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal fun recordedTvdbOrder(show: String, type: String): Pair<Long, TvdbEpisodeOrder> {
    val text = TvdbOfficeRuntimeTest::class.java.getResourceAsStream("/tvdb/$show-orders.json")!!.bufferedReader().use { it.readText() }
    val data = Json.parseToJsonElement(text).jsonObject
    fun episodes(key: String) = data.getValue(key).jsonArray.map { parseTvdbEpisode(it.jsonObject) }
    return data.getValue("seriesId").jsonPrimitive.long to TvdbEpisodeOrder(type, type, episodes(type), episodes("official"))
}

internal fun recordedProvider(order: TvdbEpisodeOrder, id: String = "tt0386676"): Meta =
    tvdbFixtureMeta(order.officialEpisodes.map { episode ->
        Video("$id:${episode.coordinates.season}:${episode.coordinates.episode}", episode.title, episode.aired, null,
            season = episode.coordinates.season, episode = episode.coordinates.episode, overview = null)
    }).copy(id = id)

class TvdbOfficeRuntimeTest {
    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }
    private val data = ConcurrentHashMap<Pair<Int, String>, Memory>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { data.getOrPut(firstArg<Int>() to secondArg<String>(), ::Memory) }
    }

    @Test fun `live Office Alternate matching visits provider list linearly instead of episode-pair regex compilation`() {
        val (id, order) = recordedTvdbOrder("office", "alternate")
        val provider = recordedProvider(order)
        val reads = AtomicInteger()
        val counted = object : AbstractList<Video>() {
            override val size get() = provider.videos.size
            override fun get(index: Int): Video = provider.videos[index].also { reads.incrementAndGet() }
        }
        val result = tvdbEpisodeOverlay(provider.copy(videos = counted), id, order)
        assertEquals(217, result.videos.size)
        assertTrue("Mapping rescans the provider list for every TVDB episode", reads.get() <= provider.videos.size * 4)
        val aired = provider.videos.associateBy { it.id }
        result.videos.filter { it.hasResolvedSource }.forEach { video ->
            assertEquals(aired[video.episodeIdentity!!.providerVideoId]?.let { EpisodeCoordinates(it.season!!, it.episode!!) }, video.episodeIdentity!!.providerCoordinates)
        }
    }

    @Test fun `saved Office Alternate survives restart and resolves both search and Continue Watching off the caller thread`() = runBlocking {
        val (id, order) = recordedTvdbOrder("office", "alternate")
        val original = recordedProvider(order)
        val store = TvdbOrderStore(factory)
        store.save(1, id, "alternate", listOf(original.id, "tmdb:2316"))
        val recreated = TvdbOrderStore(factory)
        val repository = mockk<TvdbOrderRepository> { coEvery { load(1, id, "alternate", any(), true) } returns order }
        val caller = Thread.currentThread()
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        val observed = object : AbstractList<Video>() {
            override val size get() = original.videos.size
            override fun get(index: Int) = original.videos[index].also { threads.add(Thread.currentThread()) }
        }
        val resolver = TvdbEpisodeResolver(recreated, repository, mockk<TvdbClient>(), mockk<TmdbService>())
        val search = resolver.apply(1, original.copy(videos = observed))
        val home = resolver.apply(1, original.copy(id = "tmdb:2316", videos = observed))
        assertTrue(threads.isNotEmpty())
        assertFalse("TVDB matching ran on its UI caller", caller in threads)
        assertEquals("alternate", search.episodeOrder?.effectiveType)
        assertEquals(search.videos.map { it.episodeIdentity }, home.videos.map { it.episodeIdentity })
        repeat(3) {
            recreated.save(1, id, null, listOf(original.id))
            assertSame(original, resolver.apply(1, original))
            recreated.save(1, id, "alternate", listOf(original.id))
            assertEquals(search.videos.map { it.id }, resolver.apply(1, original).videos.map { it.id })
        }
    }

    @Test fun `failed persisted Office order falls back without losing selection or touching another series`() = runBlocking {
        val (id, order) = recordedTvdbOrder("office", "alternate")
        val office = recordedProvider(order)
        val store = TvdbOrderStore(factory)
        store.save(1, id, "alternate", listOf(office.id))
        val orders = mockk<TvdbOrderRepository> {
            coEvery { load(1, id, "alternate", any(), true) } throws TvdbException(TvdbException.Reason.INVALID_RESPONSE)
        }
        val resolver = TvdbEpisodeResolver(TvdbOrderStore(factory), orders, mockk<TvdbClient>(), mockk<TmdbService>())
        val fallback = resolver.apply(1, office)
        assertEquals(office.videos, fallback.videos)
        assertEquals("alternate", fallback.episodeOrder?.selectedType)
        assertNull(fallback.episodeOrder?.effectiveType)
        assertNotNull(fallback.episodeOrder?.message)
        val unrelated = tvdbFixtureMeta(tvdbFixtureSource())
        assertSame(unrelated, resolver.apply(1, unrelated))
        store.save(1, id, null, listOf(office.id))
        assertSame(office, resolver.apply(1, office))
    }

    @Test fun `recorded Firefly DVD preserves source stream and watched identity`() {
        val (id, order) = recordedTvdbOrder("firefly", "dvd")
        val result = tvdbEpisodeOverlay(recordedProvider(order, "tt0303461"), id, order)
        val train = result.videos.single { it.episodeIdentity?.tvdbEpisodeId == 297989L }
        val serenity = result.videos.single { it.episodeIdentity?.tvdbEpisodeId == 297999L }
        assertEquals(2, train.displayEpisode)
        assertEquals(2, train.sourceEpisode)
        assertEquals("tt0303461:1:2", train.id)
        assertEquals(1, serenity.displayEpisode)
        val saved = WatchProgress("tt0303461", "series", "Firefly", null, null, null, train.id, 1, 1,
            train.title, 50, 100, 1, tvdbEpisodeId = 297989)
        val restored = reconcileTvdbProgress(result, mapOf((1 to 1) to saved)).getValue(1 to 2)
        assertEquals(saved.position, restored.position)
        assertEquals(saved.duration, restored.duration)
        assertEquals(saved.videoId, restored.videoId)
        assertEquals(setOf(297989L), restored.tvdbContentIds)
    }
}
