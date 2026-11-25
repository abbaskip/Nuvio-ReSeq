package com.nuvio.tv.mobile

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.mdblist.*
import com.nuvio.tv.data.simkl.*
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.repository.OfflineWatchQueue
import com.nuvio.tv.data.tvdb.*
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OfflineWatchQueueTest {
    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            transform(data.value).also { data.value = it }
    }

    @Test fun cachedAltTwoSuccessorBelongsToThisOrderAndNeverDisplaysEpisodeZero() {
        val order = officeOrderFixtures().getValue("alttwo")
        val meta = tvdbEpisodeOverlay(recordedProvider(order), 73244, order)
        val newLeads = meta.videos.first { it.title == "New Leads (Extended Cut)" }
        val context = newLeads.tvdbPlaybackContext()!!
        val target = requireNotNull(meta.episodeOrder).simklHistoryTargets(context.episodeIds.toSet(), emptyList()).single()
        assertEquals("tvdb", target.episode.numberingProvider)
        val show = buildTrackingMediaReference("series", meta.id, null).copy(episode = target.episode)
        val request = mdbListResponseElement(buildSimklHistoryMutationBody(listOf(TrackingHistoryItem(show, 1234567890000))))
            .objectValue().arrayValue("shows").single().objectValue().arrayValue("seasons").single().objectValue()
            .arrayValue("episodes").single().objectValue()
        assertEquals(target.episode.tvdbId, request.getValue("ids").objectValue().integer("tvdb")?.toLong())
        val download = MobileVideo(1, "series", meta.id, newLeads.id, "The Office", 6, 20, newLeads.title, context.encode())
        assertEquals("The Office S6E21 · Happy Hour (Extended Cut)", meta.offlineDetails(download).nextEpisodeLabel)
        assertEquals("The Office", download.copy(season = 0, episode = 0, tvdb = null).label)
        val watch = WatchProgress(meta.id, "series", meta.name, null, null, null, newLeads.id, 6, 20, newLeads.title, 1, 1, 120, tvdbContext = context)
        assertTrue(download.matchesCachedWatch(watch))
        assertFalse(download.matchesCachedWatch(watch.copy(tvdbContext = context.copy(orderType = "official"))))
    }

    @Test fun simklCanSeedTheOfficeFromDatedSummaryWithoutInventingEarlierHistory() {
        val entry = SimklLibraryEntry(status = SimklListStatus.WATCHING, lastWatched = "S06E18",
            lastWatchedAt = MDBLIST_TEST_TIME, show = SimklMedia("The Office",
                ids = mapOf("imdb" to kotlinx.serialization.json.JsonPrimitive("tt0386676"))))
        fun seeds(e: SimklLibraryEntry) = SimklSnapshotProjection.create(SimklSyncSnapshot(entries = listOf(e))).nextUp(false)
        assertTrue(SimklSyncSnapshot(entries = listOf(entry)).toSimklWatchedProjection().items.isEmpty())
        val row = seeds(entry).single()
        assertEquals(6, row.season)
        assertEquals(18, row.episode)
        assertEquals(WatchProgress.SOURCE_REMOTE_HISTORY, row.source)
        assertFalse(row.isNativeTrackerPause())
        assertEquals(mdbListTimestamp(MDBLIST_TEST_TIME), row.lastWatched)
        assertTrue(seeds(entry.copy(status = SimklListStatus.ON_HOLD)).isEmpty())
        assertTrue(seeds(entry.copy(status = SimklListStatus.DROPPED)).isEmpty())
        assertTrue(seeds(entry.copy(lastWatchedAt = null)).isEmpty())
        assertTrue(seeds(entry.copy(lastWatched = "E18")).isEmpty())
    }

    @Test fun offlineCompletionSurvivesRestartAndFailedDeliveryThenWritesOneDatedNativeEpisode() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        val memory = Memory()
        val factory = mockk<ProfileDataStoreFactory> { every { get(1, "offline_tracker_watches") } returns memory }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns harness.activeProfile }
        val connectivity = mockk<ConnectivityManager>(relaxed = true) { every { activeNetwork } returns null; every { getNetworkCapabilities(null) } returns null }
        val android = mockk<Context> { every { getSystemService(ConnectivityManager::class.java) } returns connectivity }
        val resolver = mockk<TvdbTrackingResolver>()
        val native = buildTrackingMediaReference("series", "tt0386676", "tt0386676:6:18", seasonNumber = 6, episodeNumber = 18)
        val mappings = mockk<MdbListEpisodeMappingService> { coEvery { toMdbListNumbering(native, any()) } returns native }
        val traktState = MutableStateFlow(TraktAuthState(accessToken = "test", refreshToken = "test", userSlug = "viewer"))
        val simklState = MutableStateFlow(SimklAuthState(isAuthenticated = true, accountId = 12))
        val trakt = mockk<com.nuvio.tv.data.local.TraktAuthDataStore> {
            every { state } returns traktState
        }
        val simkl = mockk<com.nuvio.tv.data.simkl.SimklAuthStorage> {
            every { state } returns simklState
            every { currentScope() } returns SimklAuthScope(1, 1)
        }
        val otherIds = listOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL)
        val providers = otherIds.map { id -> mockk<TrackingProgressProvider> {
            every { providerId } returns id
            coEvery { refreshForContinueWatching(any()) } returns Unit
            coEvery { completedNextUpHistory(any()) } returns emptyList()
        } }
        val writers = otherIds.map { id -> mockk<TrackingHistoryWriter> {
            every { providerId } returns id
            coEvery { addOfflineWatch(1, any()) } returns TrackingMutationResult(1)
        } }
        coEvery { resolver.offlineHistoryTargets(1, any(), any(), any()) } returns listOf(native)
        fun queue() = OfflineWatchQueue(android, factory, profiles, harness.http.store, harness.repository,
            MdbListHistoryService(harness.http.api, harness.repository), mappings, dagger.Lazy { resolver }, trakt, simkl,
            dagger.Lazy { TrackingProgressProviderRegistry(providers.toSet()) }, dagger.Lazy { TrackingHistoryWriterRegistry(writers.toSet()) })
        val watch = WatchProgress("tt0386676", "series", "The Office", null, null, null, "tt0386676:6:20", 6, 20,
            "New Leads", 100, 100, mdbListTimestamp(MDBLIST_TEST_TIME), tvdbContext =
                TvdbPlaybackContext(73244, "alttwo", 10719655, 6, 20, listOf(1692591), "tt0386676:6:20"))
        queue().recordIfOffline(1, watch)
        queue().recordIfOffline(1, watch)
        val retry = queue()
        assertEquals(listOf(watch), retry.pending(1, TrackingProviderId.MDBLIST).first())
        assertEquals(listOf(watch), retry.pending(1, TrackingProviderId.TRAKT).first())
        simklState.value = simklState.value.copy(accountId = 99)
        assertTrue(retry.pending(1, TrackingProviderId.SIMKL).first().isEmpty())
        harness.http.reply(503)
        assertTrue(retry.deliver(1)) // Trakt succeeds even though MDBList failed
        coVerify(exactly = 1) { writers[0].addOfflineWatch(1, match { it.media == native && it.watchedAtEpochMs == watch.lastWatched }) }
        coVerify(exactly = 0) { writers[1].addOfflineWatch(any(), any()) }
        simklState.value = simklState.value.copy(accountId = 12)
        assertEquals(listOf(watch), retry.pending(1, TrackingProviderId.SIMKL).first())
        assertEquals(listOf(watch), retry.pending(1, TrackingProviderId.MDBLIST).first())
        harness.http.reply(body = """{"updated":{"episodes":1}}""")
        assertTrue(retry.deliver(1))
        assertTrue(retry.pending(1, TrackingProviderId.MDBLIST).first().isEmpty())
        assertFalse(queue().deliver(1))
        coVerify(exactly = 1) { writers[0].addOfflineWatch(any(), any()) }
        coVerify(exactly = 1) { writers[1].addOfflineWatch(1, match { it.media == native && it.watchedAtEpochMs == watch.lastWatched }) }
        val posted = mdbListResponseElement(harness.http.engine.requests.last().body).objectValue()
            .arrayValue("shows").single().objectValue().arrayValue("seasons").single().objectValue()
            .arrayValue("episodes").single().objectValue()
        assertEquals(18, posted.integer("number"))
        assertEquals(watch.lastWatched, mdbListTimestamp(requireNotNull(posted.text("watched_at"))))
    }
}
