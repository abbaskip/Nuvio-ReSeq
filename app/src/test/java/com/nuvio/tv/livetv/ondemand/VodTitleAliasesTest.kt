package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.*
import com.nuvio.tv.data.remote.api.*
import com.nuvio.tv.data.tvdb.TvdbClient
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class VodTitleAliasesTest {
    private val profile = MutableStateFlow(1)
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns profile }
    private val credentials = mockk<TmdbCredentialStore> {
        every { access(1) } returns TmdbCredentialAccess(1, 0, "synthetic-key")
        every { revision(1) } returns 0
    }
    private val api = mockk<TmdbApi>()
    private val tvdb = mockk<TvdbClient>()
    private val service = mockk<TmdbService>()
    private val resolver = VodTitleAliases(api, credentials, profiles, service, tvdb)

    @Test fun `identified series gets original translated and alternative names once per profile revision`() = runBlocking {
        coEvery { api.getTvDetails(123, "synthetic-key", any()) } returns Response.success(
            TmdbDetailsResponse(123, name = "As If", originalName = "Gibi"))
        coEvery { api.getTvAlternativeTitles(123, "synthetic-key") } returns Response.success(
            TmdbAlternativeTitlesResponse(tvTitles = listOf(TmdbAlternativeTitle(title = "As If"))))
        coEvery { tvdb.seriesTitles(1, 456) } returns setOf("Gibi", "Another verified alias")
        val result = resolver.resolve(VodKind.SERIES, "123", null, 456)
        assertEquals(setOf("Gibi", "As If", "Another verified alias"), result.titles)
        assertEquals(result, resolver.resolve(VodKind.SERIES, "123", null, 456))
        coVerify(exactly = 1) { api.getTvAlternativeTitles(123, any()) }
    }

    @Test fun `unavailable alias service retains the identified target without guessing`() = runBlocking {
        coEvery { api.getTvDetails(any(), any(), any()) } throws java.io.IOException()
        coEvery { api.getTvAlternativeTitles(any(), any()) } throws java.io.IOException()
        assertEquals(VodTitleIdentity("123", emptySet()), resolver.resolve(VodKind.SERIES, "123", null, null))
    }
}
