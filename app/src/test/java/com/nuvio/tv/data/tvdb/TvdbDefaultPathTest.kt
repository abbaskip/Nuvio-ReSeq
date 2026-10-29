package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.tmdb.TmdbService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class TvdbDefaultPathTest {
    @Test fun `ordinary series uses only a local preference read`() = runTest {
        val store = mockk<TvdbOrderStore>()
        coEvery { store.selected(1, any()) } returns null
        val api = mockk<TvdbClient>()
        val orders = mockk<TvdbOrderRepository>()
        val tmdb = mockk<TmdbService>()
        val resolver = TvdbEpisodeResolver(store, orders, api, tmdb)
        val original = tvdbFixtureMeta(tvdbFixtureSource())
        assertSame(original, resolver.apply(1, original))
        coVerify(exactly = 1) { store.selected(1, any()) }
        confirmVerified(api, orders, tmdb)
    }
}
