package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.cw.ContinueWatchingRepository
import com.nuvio.tv.data.cw.CwProjection
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalendarHistoryTest {
    @Test fun onlyWatchedSeriesEnterTheWeeklyQueryAndAnUnchangedReopenUsesTheCache() = runBlocking {
        val id = 73
        val zone = ZoneId.of("Australia/Sydney")
        val source = WatchProgressSource.NUVIO_SYNC
        val history = MutableStateFlow(listOf(WatchedItem("tmdb:123", "series", "Watched", 1, 2, 100L)))
        val profiles = mockk<ProfileManager> { every { resolvedActiveProfileId } returns MutableStateFlow(id) }
        fun entry(n: Int) = LibraryEntry("tmdb:" + n, "series", "Library " + n, null, background = null, logo = null,
            description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null, imdbId = "tt" + n)
        val entries = (1..4000).map(::entry)
        val library = mockk<LibraryRepository> { coEvery { cachedLibraryItems(id) } returns entries }
        val tracker = mockk<WatchProgressRepository> { every { isDroppedShow(any()) } returns false }
        val cw = mockk<ContinueWatchingRepository> {
            every { projection } returns MutableStateFlow(CwProjection(profileId = id, source = source.name))
        }
        val settings = mockk<TraktSettingsDataStore> { every { resolvedWatchProgressSource(id) } returns flowOf(source) }
        val watched = mockk<WatchedItemsPreferences> { every { observeAllItems(id) } returns history }
        val dates = mockk<CalendarSources>()
        val lookups = mutableListOf<Map<String, String>>()
        coEvery { dates.scheduledEpisodes(capture(lookups), any(), any()) } returns emptyList()
        coEvery { dates.movieCandidates(id, emptyList(), any(), any()) } returns emptyList()
        val listings = mockk<CalendarLocalListings>()
        coEvery { listings.guide(any(), any(), any(), any()) } answers { firstArg() }
        val context = RuntimeEnvironment.getApplication()
        CalendarPageCache.clear(context.cacheDir, id)
        val repo = CalendarRepository(context, profiles, Lazy { library }, Lazy { tracker }, Lazy { cw }, Lazy { settings },
            Lazy { watched }, Lazy { dates }, Lazy { listings })
        fun open() = repo.pages(id, "AU", zone, 20_000L, flowOf(false), emptyFlow())
        open().first { it.status == CalendarStatus.READY }
        assertEquals(setOf("tmdb:123", "tt123"), lookups.single().keys)
        assertEquals("tmdb:123", lookups.single()["tt123"])
        open().toList()
        assertEquals(1, lookups.size)
        history.value += WatchedItem("tmdb:321", "series", "Newly watched", 1, 1, 200L)
        open().dropWhile { it.status != CalendarStatus.UPDATING }.first { it.status == CalendarStatus.READY }
        assertEquals(setOf("tmdb:123", "tt123", "tmdb:321", "tt321"), lookups.last().keys)
    }
}
