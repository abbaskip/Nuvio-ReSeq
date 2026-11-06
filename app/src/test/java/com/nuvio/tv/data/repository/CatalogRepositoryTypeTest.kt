package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class CatalogRepositoryTypeTest {
    private val urls = mutableListOf<String>()
    private val api = mockk<AddonApi>()
    private val repository = CatalogRepositoryImpl(
        mockk<Context>(relaxed = true), api,
        mockk<LayoutPreferenceDataStore> {
            every { customPosterUrlPattern } returns flowOf("")
            every { customPosterEnabledScreens } returns flowOf(emptySet())
        },
        mockk(relaxed = true)
    )

    private suspend fun page(type: String, savedType: String? = null, skip: Int = 0) =
        repository.getCatalog(
            addonBaseUrl = "https://example.test/config?lang=en", addonId = "addon", addonName = "Addon",
            catalogId = "top", catalogName = "Top", type = type, skip = skip,
            extraArgs = mapOf("genre" to "Sci Fi"), supportsSkip = true, savedSourceType = savedType
        ).last()

    @Test
    fun `legacy spelling retries the same page and preserves manifest row identity`() = runTest {
        val title = MetaPreviewDto(id = "tt123", type = "series", name = "First")
        coEvery { api.getCatalog(capture(urls)) } returnsMany listOf(
            Response.error(404, "{}".toResponseBody()), Response.success(CatalogResponseDto(listOf(title))),
            Response.error(400, "{}".toResponseBody()), Response.success(CatalogResponseDto(listOf(title.copy(id = "tt456"))))
        )
        val row = (page("Series") as NetworkResult.Success).data
        assertEquals("Series", row.apiType)
        assertEquals("tt123", row.items.single().id)
        val laterPage = (page(row.apiType, skip = row.nextSkip) as NetworkResult.Success).data
        assertEquals("tt456", laterPage.items.single().id)
        assertEquals(listOf(
            "https://example.test/config/catalog/Series/top/genre=Sci%20Fi.json?lang=en",
            "https://example.test/config/catalog/series/top/genre=Sci%20Fi.json?lang=en",
            "https://example.test/config/catalog/Series/top/genre=Sci%20Fi&skip=1.json?lang=en",
            "https://example.test/config/catalog/series/top/genre=Sci%20Fi&skip=1.json?lang=en"
        ), urls)
    }

    @Test
    fun `folder can use its saved spelling when the manifest spelling fails`() = runTest {
        coEvery { api.getCatalog(capture(urls)) } returnsMany listOf(
            Response.error(422, "{}".toResponseBody()), Response.success(CatalogResponseDto())
        )
        assertEquals("series", (page("series", savedType = "Series") as NetworkResult.Success).data.apiType)
        assertEquals(listOf("series", "Series"), urls.map { it.substringAfter("/catalog/").substringBefore("/") })
    }

    @Test
    fun `empty successful pages and unrelated errors do not trigger another lookup`() = runTest {
        val codes = listOf(401, 403, 429, 500)
        coEvery { api.getCatalog(capture(urls)) } returnsMany
            (listOf(Response.success(CatalogResponseDto())) + codes.map {
                Response.error<CatalogResponseDto>(it, "{}".toResponseBody())
            })
        assertTrue((page("Series") as NetworkResult.Success).data.items.isEmpty())
        for (code in codes) assertEquals(code, (page("Series") as NetworkResult.Error).code)
        assertEquals(1 + codes.size, urls.size)
    }

    @Test
    fun `custom types remain distinct and a saved type cannot switch media categories`() = runTest {
        coEvery { api.getCatalog(capture(urls)) } returns Response.error(404, "{}".toResponseBody())
        assertEquals(404, (page("Anime", savedType = "anime") as NetworkResult.Error).code)
        assertEquals(404, (page("series", savedType = "movie") as NetworkResult.Error).code)
        assertEquals(listOf("Anime", "series"), urls.map { it.substringAfter("/catalog/").substringBefore("/") })
    }

    @Test
    fun `initial and paginated requests retain advertised Series type`() = runTest {
        val response = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(CatalogResponseDto::class.java)
            .fromJson("""{"metas":[{"id":"tt1","type":"series","name":"A show"}]}""")!!
        coEvery { api.getCatalog(capture(urls)) } returns Response.success(response)
        val descriptor = CatalogDescriptor(ContentType.SERIES, "Series", "mdblist.123", "My shows")
        val first = repository.getCatalog(
            addonBaseUrl = "https://example.com",
            addonId = "aio-metadata",
            addonName = "AIOMetadata",
            catalogId = descriptor.id,
            catalogName = descriptor.name,
            type = descriptor.apiType,
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last() as NetworkResult.Success
        val row = first.data
        repository.getCatalog(
            addonBaseUrl = row.addonBaseUrl,
            addonId = row.addonId,
            addonName = row.addonName,
            catalogId = row.catalogId,
            catalogName = row.catalogName,
            type = row.apiType,
            skip = row.nextSkip,
            skipStep = row.skipStep,
            extraArgs = row.extraArgs,
            supportsSkip = row.supportsSkip
        ).last()

        assertEquals(2, urls.size)
        assertTrue(urls.all { it.contains("/catalog/Series/mdblist.123") })
        assertEquals(ContentType.SERIES, row.type)
        assertEquals("series", row.items.single().apiType)
    }
}
