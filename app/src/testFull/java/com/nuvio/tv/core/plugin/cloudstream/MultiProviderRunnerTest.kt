package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.nuvio.tv.domain.model.StreamContentContext
import com.nuvio.tv.core.plugin.TestDiagnostics
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiProviderRunnerTest {
    @Test fun failingFirstProviderDoesNotHideSecondProviderFromPlaybackOrDiagnostics() = runBlocking {
        val loader = mockk<ExternalExtensionLoader>()
        val broken = mockk<TmdbProvider> { every { name } returns "Broken" }
        coEvery { broken.load(any()) } throws NoClassDefFoundError("optional extension dependency")
        val working = mockk<TmdbProvider> { every { name } returns "Working" }
        val response = mockk<MovieLoadResponse> { every { dataUrl } returns "opaque-provider-data" }
        coEvery { working.load(any()) } returns response
        val mediaUrl = "https://example.org/public-film.mp4"
        val link = movieLink(mediaUrl)
        coEvery { working.loadLinks("opaque-provider-data", false, any(), any()) } answers {
            arg<(ExtractorLink) -> Unit>(3)(link)
            true
        }
        every { loader.loadExtension("fixture", null) } returns listOf(broken, working)
        every { loader.loadExtension("fixture", any<TestDiagnostics>()) } returns listOf(broken, working)
        val runner = ExternalExtensionRunner(loader, mockk())
        val normal = runner.execute("fixture", "12", "movie", null, null)
        val diagnostic = runner.executeWithDiagnostics("fixture", "12", "movie", null, null, TestDiagnostics())
        assertEquals(listOf(mediaUrl), normal.map { it.url })
        assertEquals(normal, diagnostic)
    }

    @Test fun tmdbProviderWithUnmappedImdbIdUsesItsOwnTitleSearch() = runBlocking {
        val loader = mockk<ExternalExtensionLoader>()
        val api = mockk<TmdbProvider> { every { name } returns "Fixture" }
        val title = "Public Film"
        val providerUrl = "https://example.org/title/42"
        val hit = mockk<MovieSearchResponse> {
            every { name } returns title
            every { type } returns TvType.Movie
            every { year } returns 2001
            every { url } returns providerUrl
        }
        every { loader.loadExtension("fixture", null) } returns listOf(api)
        coEvery { api.search(title, 1) } returns mockk { every { items } returns listOf(hit) }
        coEvery { api.load(providerUrl) } returns mockk<MovieLoadResponse> { every { dataUrl } returns "opaque" }
        val url = "https://example.org/public-film.mp4"
        coEvery { api.loadLinks("opaque", false, any(), any()) } answers {
            arg<(ExtractorLink) -> Unit>(3)(movieLink(url))
            true
        }
        val id = "tt1234567"
        val results = ExternalExtensionRunner(loader, mockk()).execute("fixture", id, "movie", null, null,
            StreamContentContext(id, title, 2001))
        assertEquals(listOf(url), results.map { it.url })
        coVerify(exactly = 1) { api.search(title, 1) }
        coVerify(exactly = 1) { api.load(providerUrl) }
    }

    private fun movieLink(mediaUrl: String): ExtractorLink = mockk {
        every { url } returns mediaUrl
        every { name } returns "Film"
        every { source } returns "Working"
        every { type } returns ExtractorLinkType.VIDEO
        every { quality } returns 720
        every { headers } returns emptyMap()
        every { referer } returns ""
    }

    @Test fun languageSelectionFiltersEachApiInsideOnePackage() = runBlocking {
        val loader = mockk<ExternalExtensionLoader>()
        val store = mockk<com.nuvio.tv.data.local.PluginDataStore>()
        val french = mockk<TmdbProvider> { every { name } returns "French"; every { lang } returns "fr-FR" }
        val english = mockk<TmdbProvider> { every { name } returns "English"; every { lang } returns "en" }
        val universal = mockk<TmdbProvider> { every { name } returns "Universal"; every { lang } returns "multi" }
        every { loader.loadExtension("fixture", null) } returns listOf(french, english, universal)
        every { store.sourceLanguages } returns kotlinx.coroutines.flow.flowOf(setOf("en"))
        for (api in listOf(english, universal)) {
            coEvery { api.load(any()) } returns mockk<MovieLoadResponse> { every { dataUrl } returns "film" }
            coEvery { api.loadLinks("film", false, any(), any()) } answers {
                arg<(ExtractorLink) -> Unit>(3)(movieLink("https://example.org/" + api.name + ".mp4"))
                true
            }
        }
        val runner = ExternalExtensionRunner(loader, mockk(), store)
        assertEquals(2, runner.execute("fixture", "12", "movie", null, null).size)
        coVerify(exactly = 0) { french.load(any()) }
        coVerify(exactly = 1) { english.load(any()) }
        coVerify(exactly = 1) { universal.load(any()) }
    }
}
