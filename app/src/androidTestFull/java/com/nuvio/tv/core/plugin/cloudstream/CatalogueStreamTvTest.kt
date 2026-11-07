package com.nuvio.tv.core.plugin.cloudstream

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.fleeksoft.ksoup.Ksoup
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.StreamContentContext
import com.nuvio.tv.livetv.storage.FixtureXtreamServer
import com.nuvio.tv.livetv.storage.SyntheticVideo
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test

private const val FILM = "Catalogue fixture film"

class CatalogueFixturePlugin : Plugin() {
    override fun load(context: Context) {
        val movie = requireNotNull(resources).assets.open("cs-marker").bufferedReader().use { it.readText() }
        registerMainAPI(object : MainAPI() {
            override var name = "Catalogue fixture"
            override var mainUrl = movie.substringBeforeLast('/')
            override suspend fun search(query: String): List<SearchResponse> =
                if (query == FILM) listOf(newMovieSearchResponse(FILM, movie, TvType.Movie)) else emptyList()
            override suspend fun load(url: String): LoadResponse =
                newMovieLoadResponse(FILM, url, TvType.Movie, movie)
            override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit): Boolean {
                val installed = PluginManager.getPluginsOnline().single { it.internalName == "fixture" }
                check(File(installed.filePath).isFile)
                val html = Ksoup.parse("""<i></i><a href="$data"></a>""")
                val link = requireNotNull(html.selectFirst("i")?.nextElementSibling()).attr("href")
                callback(newExtractorLink(name, FILM, link, ExtractorLinkType.VIDEO))
                return true
            }
        })
    }
}

class CatalogueStreamTvTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installedPackagePlaysFromStreamPickerWithOnlyImdbIdAndTitle() = runBlocking {
        FixtureXtreamServer().use { media ->
            media.mediaFile = SyntheticVideo.create(app.cacheDir)
            val url = "${media.base}/movie/catalogue.mp4"
            val binary = File.createTempFile("catalogue", ".cs3", app.cacheDir)
            try {
                writeResourceFixture(binary, CatalogueFixturePlugin::class.java, url)
                val selection = StreamContentContext("tt00000000000000000", FILM, 2001)
                val links = searchFilm(selection, "Catalogue fixture", binary = binary)
                assertEquals(listOf(url), links)
                SyntheticVideo.assertPlays(links.single())
            } finally {
                binary.delete()
            }
        }
    }

    @Test fun publicArchiveFilmPlaysFromStreamPicker() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudStreamLiveFilm") == "true")
        val selection = StreamContentContext("tt1254207", "Big Buck Bunny", 2008)
        val links = searchFilm(selection, "Internet Archive",
            download = "https://raw.githubusercontent.com/recloudstream/extensions/fcb6101ce646951f5055a6aff67ee2e9f3e1fd54/InternetArchiveProvider.cs3",
            hash = "sha256-54f3de560bebeff2e4a10d1163f6146313c59061d21c3a230125611f01b531eb")
        // Blender Foundation's CC BY film. Prefer MP4 when the archive offers several formats.
        val url = links.firstOrNull { it.substringBefore('?').endsWith(".mp4", ignoreCase = true) } ?: links.first()
        SyntheticVideo.assertPlays(url)
    }

    private suspend fun searchFilm(selection: StreamContentContext, name: String, binary: File? = null,
        download: String? = null, hash: String? = null): List<String> {
        val graph = EntryPointAccessors.fromApplication(app, CloudStreamTestEntryPoint::class.java)
        val plugins = graph.pluginManager()
        val previouslyEnabled = plugins.pluginsEnabled.first()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (binary != null && session.uri == "/fixture.cs3") return newFixedLengthResponse(Response.Status.OK,
                    "application/octet-stream", binary.inputStream(), binary.length())
                val manifest = JSONObject().put("name", name).put("internalName", "fixture")
                    .put("tvTypes", JSONArray().put("Movie")).put("fileHash", hash)
                    .put("url", download ?: "http://127.0.0.1:$listeningPort/fixture.cs3")
                return newFixedLengthResponse(Response.Status.OK, "application/json", JSONArray().put(manifest).toString())
            }
        }
        server.start()
        var repositoryId: String? = null
        try {
            plugins.setPluginsEnabled(true)
            val repo = plugins.addRepository("http://127.0.0.1:${server.listeningPort}/plugins.json").getOrThrow()
            repositoryId = repo.id
            val result = withTimeout(150_000) {
                graph.streamRepository().getStreamsFromAllAddons("movie", selection.contentId, forceRefresh = true,
                    contentContext = selection, priorityBingeGroup = "local-plugin-${repo.id}:fixture",
                    preferredPluginOnly = true).first { state -> state is NetworkResult.Success &&
                        state.data.any { group -> group.streams.any { it.pluginSourceName == name } } }
            } as NetworkResult.Success
            return result.data.flatMap { it.streams }.filter { it.pluginSourceName == name }.mapNotNull { it.url }
        } finally {
            repositoryId?.let { plugins.removeRepository(it) }
            plugins.setPluginsEnabled(previouslyEnabled)
            server.stop()
        }
    }
}
