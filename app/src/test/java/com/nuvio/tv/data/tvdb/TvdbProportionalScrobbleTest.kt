package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.mdblist.*
import com.nuvio.tv.data.remote.api.*
import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.data.repository.*
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TvdbProportionalScrobbleTest {
    private val orders = officeOrderFixtures()
    private val original = recordedProvider(orders.getValue("official"))
    private val effective = tvdbEpisodeOverlay(original, 73244, orders.getValue("alttwo"))
    private val store = mockk<TvdbOrderStore> {
        coEvery { selected(1, any()) } returns TvdbOrderPreference(73244, "alttwo")
        coEvery { knownSeries(1, any()) } returns 73244
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val overlay = mockk<TvdbEpisodeResolver> {
        coEvery { apply(1, original) } returns effective
        coEvery { forOrder(1, original, any()) } returns effective
    }
    private val source = mockk<MetaRepositoryImpl> { every { getCachedMeta("series", original.id) } returns original }
    private val trakt = mockk<TraktApi>()
    private val auth = mockk<TraktAuthService>()
    private val local = mockk<WatchProgressPreferences> { coEvery { getAllRawEntries(1) } returns emptyMap() }
    private val rows = Json.parseToJsonElement(javaClass.getResourceAsStream("/tvdb/office-trakt-episodes.json")!!
        .bufferedReader().use { it.readText() }).jsonArray.flatMap { it.jsonObject.getValue("episodes").jsonArray }
        .map { value -> value.jsonObject.let { row ->
            val ids = row.getValue("ids").jsonObject
            TraktEpisodeDto(row["title"]?.jsonPrimitive?.contentOrNull,
                row.getValue("season").jsonPrimitive.int, row.getValue("number").jsonPrimitive.int,
                TraktIdsDto(tvdb = ids["tvdb"]?.jsonPrimitive?.intOrNull, trakt = ids["trakt"]?.jsonPrimitive?.intOrNull),
                row["first_aired"]?.jsonPrimitive?.contentOrNull)
        } }
    private val nativeMedia = MdbListMedia(MdbListIds(imdb = original.id, tvdb = 73244, tmdb = 2316))
    private val mdbSnapshot = com.nuvio.tv.data.mdblist.MdbListSyncSnapshot(1,
        watched = rows.map { row -> MdbListWatchedRecord(MdbListItemType.EPISODE, nativeMedia, "2026-01-01",
            row.season, row.number, row.title, episodeTmdbId = row.ids!!.trakt!!.toLong(), episodeTvdbId = row.ids.tvdb?.toLong()) })
    private val mdb = mockk<MdbListSyncRepository> {
        coEvery { ensureLoaded() } returns mdbSnapshot
        every { state } returns MutableStateFlow(MdbListSyncState(snapshot = mdbSnapshot))
    }
    private val resolver = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, trakt, auth,
        mockk<TmdbApi>(), mockk<TmdbService>(), local,
        simkl = dagger.Lazy { fixtureSimkl(73244, *rows.mapNotNull { row -> row.ids?.tvdb?.toLong()?.let {
            it to EpisodeCoordinates(row.season!!, row.number!!)
        } }.toTypedArray()) }, mdbList = dagger.Lazy { mdb })

    init {
        coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
        }
        coEvery { trakt.searchById("fixture", "imdb", original.id, "show") } returns Response.success(listOf(
            TraktSearchResultDto(show = TraktShowDto(ids = TraktIdsDto(tvdb = 73244, trakt = 100)))))
        coEvery { trakt.getShowSeasons("fixture", "100", "episodes,full") } returns Response.success(
            rows.groupBy { it.season }.map { (season, episodes) -> TraktSeasonDto(season, episodes = episodes) })
    }

    private val lecture get() = effective.videos.single { it.episodeIdentity!!.tvdbEpisodeId == 10718533L }
    private fun event(percent: Double, video: Video = lecture) = TrackingScrobbleEvent(
        buildTrackingMediaReference("series", original.id, video.id, "The Office", seasonNumber = video.season,
            episodeNumber = video.episode, episodeTitle = video.title).let {
            it.copy(catalog = it.catalog!!.copy(tvdbContext = video.tvdbPlaybackContext(), tvdbCoverageVerified = true))
        }, percent)

    @Test fun `combined partial events use the active verified part for every tracker and cannot complete early`() = runTest {
        for (provider in TrackingProviderId.entries) {
            for ((percent, episode, expected) in listOf(Triple(0.0, 14, 0.0), Triple(25.0, 14, 50.0),
                Triple(49.0, 14, 79.0), Triple(50.0, 15, 0.0), Triple(75.0, 15, 50.0), Triple(89.0, 15, 78.0))) {
                for (action in TrackingScrobbleAction.entries) {
                    val (mappedAction, mapped) = resolver.scrobbleEvents(provider, action, event(percent))!!.single()
                    assertEquals(if (action == TrackingScrobbleAction.START) action else TrackingScrobbleAction.PAUSE, mappedAction)
                    assertEquals(episode, mapped.media.episode!!.number)
                    assertEquals(5, mapped.media.episode!!.season)
                    assertEquals(expected, mapped.progressPercent, 0.01)
                    assertFalse(mapped.allowCompletion)
                    assertEquals("tt0386676:5:16", event(percent).media.catalog!!.videoId)
                }
            }
        }
    }

    @Test fun `only verified whole completion stops both native parts and avoids automatic history duplication`() = runTest {
        for (provider in TrackingProviderId.entries) {
            val completed = resolver.scrobbleEvents(provider, TrackingScrobbleAction.STOP, event(99.0))!!
            assertEquals(setOf(14, 15), completed.map { it.second.media.episode!!.number }.toSet())
            assertTrue(completed.all { it.first == TrackingScrobbleAction.STOP && it.second.progressPercent == 100.0 })
            assertTrue(resolver.resolveAll(1, provider, event(99.0).media, true, coverageChangesOnly = true)!!.isEmpty())
            assertEquals(2, resolver.resolveAll(1, provider, event(99.0).media, true)!!.size)
        }
    }

    @Test fun `boundary handoff pauses the old part then starts the new without periodic API writes`() = runTest {
        val adapters = TrackingProviderId.entries.map { id -> mockk<TrackingScrobbler>(relaxed = true) { every { providerId } returns id } }
        val coordinator = TrackingScrobbleCoordinator(mockk { every { connectedScrobblers() } returns adapters }, resolver)
        assertTrue(coordinator.scrobbleBoundary(event(49.99), event(50.01)).isEmpty())
        adapters.forEach { adapter ->
            coVerifyOrder {
                adapter.scrobble(TrackingScrobbleAction.PAUSE, match { it.media.episode!!.number == 14 && it.progressPercent < 80 })
                adapter.scrobble(TrackingScrobbleAction.START, match { it.media.episode!!.number == 15 && it.progressPercent < 1 })
            }
        }
        adapters.forEach { clearMocks(it, answers = false) }
        assertTrue(coordinator.scrobbleBoundary(event(60.0), event(61.0)).isEmpty())
        adapters.forEach { coVerify(exactly = 0) { it.scrobble(any(), any()) } }
        assertTrue(coordinator.scrobbleBoundary(event(49.0), event(51.0).let {
            it.copy(media = it.media.copy(catalog = it.media.catalog!!.copy(contentId = "other")))
        }).isEmpty())
        adapters.forEach { coVerify(exactly = 0) { it.scrobble(any(), any()) } }
    }

    @Test fun `split source cannot complete the combined tracker until both verified parts finish`() = runTest {
        val split = tvdbEpisodeOverlay(original, 73244, orders.getValue("alternate"))
        coEvery { overlay.forOrder(1, original, any()) } returns split
        val parts = split.videos.filter { tvdbEpisodeBaseTitle(it.title) == tvdbEpisodeBaseTitle("Weight Loss") }
            .sortedBy { it.displayEpisode }
        assertEquals(2, parts.size)
        val first = resolver.scrobbleEvents(TrackingProviderId.TRAKT, TrackingScrobbleAction.STOP, event(99.0, parts[0]))!!.single()
        assertEquals(TrackingScrobbleAction.PAUSE, first.first)
        assertFalse(first.second.allowCompletion)
        assertEquals(49.5, first.second.progressPercent, 0.01)
        val second = resolver.scrobbleEvents(TrackingProviderId.TRAKT, TrackingScrobbleAction.STOP, event(99.0, parts[1]))!!.single()
        assertEquals(TrackingScrobbleAction.PAUSE, second.first)
        assertEquals(79.0, second.second.progressPercent, 0.01)
        val completed = WatchProgress(original.id, "series", "Example", null, null, null, parts[0].id,
            parts[0].season, parts[0].episode, parts[0].title, 1, 1, 1, tvdbContext = parts[0].tvdbPlaybackContext())
        coEvery { local.getAllRawEntries(1) } returns mapOf("part" to completed)
        val whole = resolver.scrobbleEvents(TrackingProviderId.TRAKT, TrackingScrobbleAction.STOP, event(99.0, parts[1]))!!.single()
        assertEquals(TrackingScrobbleAction.STOP, whole.first)
        assertEquals(100.0, whole.second.progressPercent, 0.0)
    }

    @Test fun `duration rejection and Provider Default still bypass estimated tracking`() = runTest {
        for (provider in TrackingProviderId.entries) {
            val invalid = event(95.0).let { it.copy(media = it.media.copy(catalog = it.media.catalog!!.copy(tvdbCoverageVerified = false))) }
            assertTrue(resolver.scrobbleEvents(provider, TrackingScrobbleAction.STOP, invalid)!!.isEmpty())
            val native = event(30.0).let { it.copy(media = it.media.copy(catalog = it.media.catalog!!.copy(providerDefault = true))) }
            assertEquals(listOf(TrackingScrobbleAction.PAUSE to native), resolver.scrobbleEvents(provider, TrackingScrobbleAction.PAUSE, native))
        }
    }

    @Test fun `ordinary extended cut keeps actual full-video progress without ratio scaling`() = runTest {
        val ordinary = effective.videos.single { it.title.startsWith("Business Ethics") }
        for (provider in TrackingProviderId.entries) {
            val mapped = resolver.scrobbleEvents(provider, TrackingScrobbleAction.PAUSE, event(85.0, ordinary))!!.single()
            assertEquals(85.0, mapped.second.progressPercent, 0.0)
            assertTrue(mapped.second.allowCompletion)
            assertEquals(2, mapped.second.media.episode!!.number)
        }
    }
}
