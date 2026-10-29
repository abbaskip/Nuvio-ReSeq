package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.di.ProviderMetadata
import com.nuvio.tv.core.di.RepositoryModule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonDto
import com.nuvio.tv.data.repository.*
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TvdbTraktMetadataBoundaryTest {
    private fun providerEpisodes(): List<Video> {
        val text = javaClass.getResourceAsStream("/tvdb/office-provider-episodes.json")!!.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonArray.map { value ->
            val row = value.jsonObject
            Video(row.getValue("id").jsonPrimitive.content, row.getValue("title").jsonPrimitive.content,
                row["released"]?.jsonPrimitive?.contentOrNull, null, season = row["season"]?.jsonPrimitive?.intOrNull,
                episode = row["episode"]?.jsonPrimitive?.intOrNull, overview = null,
                available = row["available"]?.jsonPrimitive?.booleanOrNull)
        }
    }

    @Test fun `Trakt mapping cache must never be populated with TVDB display metadata`() = runTest {
        val provider = tvdbFixtureMeta(providerEpisodes()).copy(id = "tt0386676")
        val (series, order) = recordedTvdbOrder("office", "alternate")
        val alternate = tvdbEpisodeOverlay(provider, series, order)
        val api = mockk<TraktApi>()
        val auth = mockk<TraktAuthService>()
        coEvery { auth.getCurrentAuthState() } returns TraktAuthState(accessToken = "fixture", refreshToken = "fixture")
        coEvery { auth.executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("fixture")
        }
        val seasons = provider.videos.filter { (it.season ?: 0) > 0 }.groupBy { it.season!! }.map { (season, videos) ->
            TraktSeasonDto(number = season, episodes = videos.map { TraktEpisodeDto(title = it.title, season = season, number = it.episode) })
        }
        coEvery { api.getShowSeasons(any(), "tt0386676", "episodes") } returns Response.success(seasons)
        // The pre-correction overlay omitted the combined entries from its resolved source table.
        var displayed = alternate.copy(videos = alternate.videos.filter { it.episodeIdentity?.canonicalTvdbEpisodeId == null })
        val decorated = mockk<MetaRepository> {
            every { getMetaFromAllAddons(any(), any(), any()) } answers { flowOf(NetworkResult.Success(displayed)) }
        }
        val contaminated = TraktEpisodeMappingService(api, auth, decorated)
        contaminated.prefetchAddonEpisodes(listOf(provider.id))
        displayed = provider // Returning to Provider Default does not flush the upstream mapper's cache.
        val wrong = contaminated.resolveAddonEpisodeMapping(provider.id, "series", 4, 14)
        assertNotNull("The fixture must reproduce cached remapping", wrong)
        assertNotEquals(4 to 14, wrong!!.season to wrong.episode)

        val raw = mockk<MetaRepositoryImpl> {
            every { getMetaFromAllAddons(any(), any(), any()) } returns flowOf(NetworkResult.Success(provider))
        }
        val isolated = TraktEpisodeMappingService(api, auth, raw)
        isolated.prefetchAddonEpisodes(listOf(provider.id))
        repeat(3) {
            displayed = if (it % 2 == 0) alternate else provider
            // Upstream returns null for matching structures: retain Trakt's original S4E14.
            assertNull(isolated.resolveAddonEpisodeMapping(provider.id, "series", 4, 14))
        }
        coVerify(exactly = 0) { api.addHistory(any(), any()) }
    }

    @Test fun `injection gives both native Trakt consumers original provider metadata`() {
        for (consumer in listOf(TraktEpisodeMappingService::class.java, TraktProgressService::class.java)) {
            val parameter = consumer.constructors.single().parameters.single { it.type == MetaRepository::class.java }
            assertNotNull("Native Trakt metadata must bypass the TVDB decorator", parameter.getAnnotation(ProviderMetadata::class.java))
        }
        val binding = RepositoryModule::class.java.declaredMethods.single { it.getAnnotation(ProviderMetadata::class.java) != null }
        assertEquals(MetaRepositoryImpl::class.java, binding.parameterTypes.single())
    }
}
