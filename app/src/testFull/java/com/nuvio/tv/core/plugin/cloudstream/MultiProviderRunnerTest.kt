package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.nuvio.tv.core.plugin.TestDiagnostics
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
        val link = mockk<ExtractorLink> {
            every { url } returns mediaUrl
            every { name } returns "Film"
            every { source } returns "Working"
            every { type } returns ExtractorLinkType.VIDEO
            every { quality } returns 720
            every { headers } returns emptyMap()
            every { referer } returns ""
        }
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
}
