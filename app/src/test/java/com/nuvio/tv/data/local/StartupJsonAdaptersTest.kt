package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.data.remote.dto.mdblist.*
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

class StartupJsonAdaptersTest {
    private val moshi = Moshi.Builder().build() // No reflection fallback: requires generated adapters.

    @Test fun `legacy plugin records retain defaults and optional fields`() {
        val adapter = moshi.adapter(PluginRepository::class.java)
        val repo = adapter.fromJson("""{"id":"one","name":"One","url":"https://example.com"}""")!!
        assertTrue(repo.enabled)
        assertEquals(RepositoryType.NUVIO_JS, repo.type)
        assertEquals(repo, adapter.fromJson(adapter.toJson(repo)))
        val scraper = ScraperInfo("a", "A", "", "1", "a.js", listOf("series"), true, true,
            null, emptyList(), "one", null)
        val scrapers = moshi.adapter(ScraperInfo::class.java)
        assertEquals(scraper, scrapers.fromJson(scrapers.toJson(scraper)))
    }

    @Test fun `ratings adapters preserve mixed keywords ids nulls and default request`() {
        val adapter = moshi.adapter(MDBListMediaResponseDto::class.java)
        val media = adapter.fromJson("""{"ids":{"imdb":"tt123","tmdb":42},"ratings":[{"source":"tomatoes","value":91}],"keywords":["certified-fresh",{"name":"other"},null]}""")!!
        assertEquals("tt123", media.resolvedImdbId())
        assertTrue(media.toRatings().tomatoesCertified)
        assertEquals(media, adapter.fromJson(adapter.toJson(media)))
        assertEquals(MDBListMediaResponseDto(), adapter.fromJson("{}"))
        val request = moshi.adapter(MDBListMediaRequestDto::class.java)
        assertEquals(listOf("keyword"), request.fromJson("""{"ids":["tt123"]}""")!!.appendToResponse)
    }
}
