package com.nuvio.tv.core.plugin.cloudstream

import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ExternalRepoCompatibilityTest {
    @Test fun redirectedListsResolvePackagesIndependentlyAndKeepNewestDuplicate() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.path) {
                    "/repo" -> MockResponse().setResponseCode(302).setHeader("Location", "/repo/repo.json")
                    "/repo/repo.json" -> MockResponse().setBody("""{"pluginLists":["lists/old","/new","/unavailable"]}""")
                    "/repo/lists/old" -> MockResponse().setBody("""[{"internalName":"Example","url":"old.cs3","version":1}]""")
                    "/new" -> MockResponse().setResponseCode(302).setHeader("Location", "/cdn/list.json")
                    "/cdn/list.json" -> MockResponse().setBody("""[null,{"internalName":"broken"},
                        {"internalName":"Example","version":2,"url":"../packages/example.cs3","iconUrl":"/icons/logo.png"},
                        {"internalName":"Separate","url":"other.cs3"}]""")
                    else -> MockResponse().setResponseCode(503)
                }
            }
            val parser = ExternalRepoParser(Moshi.Builder().build())
            val entries = requireNotNull(parser.tryParse(server.url("/repo").toString())).plugins
            assertEquals(listOf("Example", "Separate"), entries.map { it.name })
            assertEquals(2, entries.first().version)
            assertEquals(server.url("/packages/example.cs3").toString(), entries.first().url)
            assertEquals(server.url("/icons/logo.png").toString(), entries.first().iconUrl)
            assertEquals(server.url("/cdn/other.cs3").toString(), entries.last().url)
            val direct = parser.tryParse(server.url("/cdn/list.json").toString())
            assertEquals(entries, direct?.plugins)
        }
    }

    @Test fun directoryUrlFallsBackToRepoJsonWithoutLosingRelativePaths() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"pluginLists":["plugins.json"]}"""))
            server.enqueue(MockResponse().setBody("""[{"internalName":"A","url":"files/A.cs3"}]"""))
            val result = ExternalRepoParser(Moshi.Builder().build()).tryParse(server.url("/catalog").toString())
            assertEquals(server.url("/catalog/files/A.cs3").toString(), result?.plugins?.single()?.url)
            assertEquals("/catalog", server.takeRequest().path)
            assertEquals("/catalog/repo.json", server.takeRequest().path)
        }
    }

    @Test fun unavailableListsDoNotBecomeAnEmptySuccessfulRepository() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"name":"Archive","pluginLists":["missing"]}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(ExternalRepoParser(Moshi.Builder().build()).tryParse(server.url("/repo.json").toString()))
        }
    }

    @Test fun cloudStreamShareLinksAndGithubFileLinksReachTheRawManifest() {
        val file = "https://github.com/recloudstream/extensions/blob/master/repo.json"
        val raw = "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json"
        assertEquals(raw, cloudStreamManifestUrl("cloudstreamrepo://$file").toString())
        assertEquals(raw, cloudStreamManifestUrl("https://cs.repo/?$file").toString())
        assertEquals("https://raw.githubusercontent.com/recloudstream/extensions/HEAD/repo.json",
            cloudStreamManifestUrl("https://github.com/recloudstream/extensions").toString())
    }

    @Test fun otherCategoryIsEligibleForMovieAndSeriesSearch() {
        // Both InternetArchiveProvider and DailymotionProvider declare ["Others"].
        assertEquals(listOf("movie", "tv"), cloudStreamContentTypes(listOf("Others")))
        assertEquals(listOf("tv"), cloudStreamContentTypes(listOf("TvSeries")))
        assertEquals(listOf("movie"), cloudStreamContentTypes(listOf("Movie", "Documentary")))
        assertEquals(listOf("movie", "tv"), cloudStreamContentTypes(null))
    }
}
