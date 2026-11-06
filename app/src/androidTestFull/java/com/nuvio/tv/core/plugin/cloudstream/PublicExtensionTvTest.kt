package com.nuvio.tv.core.plugin.cloudstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.nuvio.tv.core.plugin.TestDiagnostics
import com.nuvio.tv.livetv.storage.FixtureXtreamServer
import com.nuvio.tv.livetv.storage.SyntheticVideo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicExtensionTvTest {
    // Opt in with instrumentation argument cloudStreamPublic=true. No repository or plugin persists.
    @Test fun loadOfficialPackagesAndPlayArchiveProviderOutput() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudStreamPublic") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ExternalExtensionLoader(context, ExternalExtractorRegistry())
        val base = "https://raw.githubusercontent.com/recloudstream/extensions/fcb6101ce646951f5055a6aff67ee2e9f3e1fd54"
        val entries = requireNotNull(ExternalRepoParser(com.squareup.moshi.Moshi.Builder().build())
            .tryParse("$base/plugins.json")).plugins
        for (name in listOf("DailymotionProvider", "InternetArchiveProvider")) {
            val entry = entries.single { it.internalName == name }
            val id = "public-extension-test:$name"
            try {
                assertTrue("Movies should reach $name", "movie" in cloudStreamContentTypes(entry.tvTypes))
                // The pinned catalog may point to a mutable builds branch. Pin the binary as well.
                val file = requireNotNull(loader.downloadExtension(id, "$base/$name.cs3", requireNotNull(entry.fileHash)))
                val installedBytes = file.readBytes()
                val diagnostics = TestDiagnostics()
                val apis = loader.loadExtension(id, diagnostics)
                assertTrue(diagnostics.steps.joinToString(), apis.isNotEmpty())
                val api = apis.single()
                assertEquals(if (name == "DailymotionProvider") "Dailymotion" else "Internet Archive", api.name)
                assertSame(api, loader.loadExtension(id).single())
                assertTrue("Refresh must reject a checksum mismatch",
                    loader.downloadExtension(id, "$base/$name.cs3", "sha256-" + "0".repeat(64)) == null)
                assertArrayEquals(installedBytes, file.readBytes())
                assertSame(api, loader.loadExtension(id).single())
                if (name == "InternetArchiveProvider") {
                    if (InstrumentationRegistry.getArguments().getString("cloudStreamLiveFilm") == "true") {
                        // CC BY 3.0, Blender Foundation. Only the film's first frames are played.
                        val id = "BigBuckBunny_328"
                        val result = requireNotNull(api.search("identifier:$id", 1)?.items
                            ?.firstOrNull { it.url.endsWith("/$id") }) { "Archive search returned no matching film" }
                        val data = when (val details = api.load(result.url)) {
                            is MovieLoadResponse -> details.dataUrl
                            is TvSeriesLoadResponse -> details.episodes.first().data
                            else -> error("Archive returned no playable details")
                        }
                        val links = mutableListOf<ExtractorLink>()
                        api.loadLinks(data, false, {}, links::add)
                        val link = links.firstOrNull { it.url.substringBefore('?').endsWith(".mp4") } ?: links.first()
                        SyntheticVideo.assertPlays(link.url)
                    }
                    FixtureXtreamServer().use { media ->
                        media.mediaFile = SyntheticVideo.create(context.cacheDir)
                        val url = "${media.base}/movie/cloudstream.mp4"
                        val data = """{"type":"video-playlist","urlData":[{"url":"$url","format":"MPEG4","quality":720,"size":1.0}]}"""
                        val links = mutableListOf<ExtractorLink>()
                        api.loadLinks(data, false, {}, links::add)
                        assertEquals(url, links.single().url)
                        SyntheticVideo.assertPlays(links.single().url)
                    }
                }
            } finally {
                loader.deleteExtension(id)
            }
        }
    }
}
