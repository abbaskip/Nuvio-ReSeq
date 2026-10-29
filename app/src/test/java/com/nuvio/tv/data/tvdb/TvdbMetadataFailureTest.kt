package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.repository.MetaRepositoryImpl
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvdbMetadataFailureTest {
    @Test fun `one failed saved order cannot cancel metadata for other Continue Watching shows`() = runTest {
        val office = recordedProvider(recordedTvdbOrder("office", "alternate").second)
        val other = tvdbFixtureMeta(tvdbFixtureSource())
        val source = mockk<MetaRepositoryImpl> {
            every { getMetaFromAllAddons("series", office.id, null) } returns flowOf(NetworkResult.Success(office))
            every { getMetaFromAllAddons("series", other.id, null) } returns flowOf(NetworkResult.Success(other))
        }
        val store = mockk<TvdbOrderStore> {
            coEvery { selected(1, listOf(office.id)) } returns TvdbOrderPreference(73244, "alternate")
            coEvery { selected(1, listOf(other.id)) } returns null
        }
        val resolver = mockk<TvdbEpisodeResolver> { coEvery { apply(1, office) } throws IllegalStateException("fixture invalid order") }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val repository = TvdbMetaRepository(source, dagger.Lazy { resolver }, store, profiles)
        val fallback = (repository.getMetaFromAllAddons("series", office.id).first() as NetworkResult.Success).data
        assertEquals(office.videos, fallback.videos)
        assertEquals("alternate", fallback.episodeOrder?.selectedType)
        assertNull(fallback.episodeOrder?.effectiveType)
        assertNotNull(fallback.episodeOrder?.message)
        assertSame(other, (repository.getMetaFromAllAddons("series", other.id).first() as NetworkResult.Success).data)
        coEvery { resolver.apply(1, office) } throws CancellationException("fixture cancelled")
        try { repository.getMetaFromAllAddons("series", office.id).first(); fail("Cancellation was swallowed") }
        catch (_: CancellationException) { }
    }
    @Test fun `effective metadata capacity retains recently used titles instead of clearing every title`() = runTest {
        val raw = tvdbFixtureMeta(tvdbFixtureSource())
        val selection = TvdbOrderPreference(78874, "dvd")
        val source = mockk<MetaRepositoryImpl> {
            every { getMetaFromAllAddons("series", any(), null) } answers {
                flowOf(NetworkResult.Success(raw.copy(id = secondArg(), imdbId = null)))
            }
        }
        val store = mockk<TvdbOrderStore> {
            coEvery { selected(1, any()) } returns selection
            every { hasSnapshot(1) } returns true
            every { cachedSelection(1, any()) } returns selection
        }
        val resolver = mockk<TvdbEpisodeResolver> {
            coEvery { apply(1, any()) } answers {
                secondArg<com.nuvio.tv.domain.model.Meta>().copy(
                    episodeOrder = com.nuvio.tv.domain.model.EpisodeOrderStatus(78874, "dvd", "dvd"))
            }
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val repository = TvdbMetaRepository(source, dagger.Lazy { resolver }, store, profiles)
        repository.getMetaFromAllAddons("series", "hot").first()
        val hot = repository.getCachedMeta("series", "hot")
        assertNotNull(hot)
        repeat(66) { index ->
            repository.getMetaFromAllAddons("series", "cold$index").first()
            assertSame(hot, repository.getCachedMeta("series", "hot"))
        }
        assertNull(repository.getCachedMeta("series", "cold0"))
    }

}
