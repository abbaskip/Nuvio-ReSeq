package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.mutationKey
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStateSyncServiceTest {
    @Test
    fun `progress push sends only pending mutations to the requested profile`() = runTest {
        val postgrest = mockk<Postgrest>()
        val preferences = mockk<WatchProgressPreferences>(relaxed = true)
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val pending = progress("pending")
        coEvery { mutationStore.pendingProgressDeletes(2) } returns emptySet()
        coEvery { mutationStore.pendingProgressUpserts(2) } returns mapOf("pending" to pending)
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            calls += firstArg<String>() to secondArg<JsonObject>()
            mockk<PostgrestResult>(relaxed = true)
        }
        val service = progressService(postgrest, preferences, mutationStore)

        service.pushToRemote(2).getOrThrow()

        assertEquals(listOf("sync_push_watch_progress"), calls.map { it.first })
        assertEquals(2, calls.single().second.getValue("p_profile_id").jsonPrimitive.int)
        assertEquals(
            listOf("pending"),
            calls.single().second.getValue("p_entries").jsonArray.map {
                it.jsonObject.getValue("progress_key").jsonPrimitive.content
            }
        )
        coVerify(exactly = 0) { preferences.getAllRawEntries(any()) }
        coVerify(exactly = 1) {
            mutationStore.acknowledgeProgressUpserts(mapOf("pending" to pending), 2)
        }
    }

    @Test
    fun `progress flush deletes before upserting for the same profile`() = runTest {
        val postgrest = mockk<Postgrest>()
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        coEvery { mutationStore.pendingProgressDeletes(3) } returns setOf("removed")
        coEvery { mutationStore.pendingProgressUpserts(3) } returns mapOf("saved" to progress("saved"))
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            calls += firstArg<String>() to secondArg<JsonObject>()
            mockk<PostgrestResult>(relaxed = true)
        }
        val service = progressService(
            postgrest,
            mockk(relaxed = true),
            mutationStore
        )

        service.pushToRemote(3).getOrThrow()

        assertEquals(
            listOf("sync_delete_watch_progress", "sync_push_watch_progress"),
            calls.map { it.first }
        )
        assertTrue(calls.all { it.second.getValue("p_profile_id").jsonPrimitive.int == 3 })
    }

    @Test
    fun `failed progress push keeps its pending mutation`() = runTest {
        val postgrest = mockk<Postgrest>()
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val pending = progress("pending")
        coEvery { mutationStore.pendingProgressDeletes(4) } returns emptySet()
        coEvery { mutationStore.pendingProgressUpserts(4) } returns mapOf("pending" to pending)
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } throws IllegalStateException("offline")
        val service = progressService(
            postgrest,
            mockk(relaxed = true),
            mutationStore,
            canRefreshJwt = false
        )

        assertTrue(service.pushToRemote(4).isFailure)
        coVerify(exactly = 0) { mutationStore.acknowledgeProgressUpserts(any(), any()) }
    }

    @Test
    fun `watched push sends only pending mutations to the requested profile`() = runTest {
        val postgrest = mockk<Postgrest>()
        val preferences = mockk<WatchedItemsPreferences>(relaxed = true)
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val pending = watched("pending")
        coEvery { mutationStore.pendingWatchedDeletes(2) } returns emptySet()
        coEvery { mutationStore.pendingWatchedUpserts(2) } returns mapOf(pending.mutationKey() to pending)
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            calls += firstArg<String>() to secondArg<JsonObject>()
            mockk<PostgrestResult>(relaxed = true)
        }
        val service = watchedService(postgrest, preferences, mutationStore)

        service.pushToRemote(2).getOrThrow()

        assertEquals(listOf("sync_push_watched_items"), calls.map { it.first })
        assertEquals(2, calls.single().second.getValue("p_profile_id").jsonPrimitive.int)
        assertEquals(
            listOf("pending"),
            calls.single().second.getValue("p_items").jsonArray.map {
                it.jsonObject.getValue("content_id").jsonPrimitive.content
            }
        )
        coVerify(exactly = 0) { preferences.getAllItems(any()) }
        coVerify(exactly = 1) {
            mutationStore.acknowledgeWatchedUpserts(match { it.toList() == listOf(pending) }, 2)
        }
    }

    @Test fun `single TVDB cloud push uses verified native identity and canonical key without changing local progress`() = runTest {
        val postgrest = mockk<Postgrest>()
        val mutations = mockk<WatchStateMutationStore>(relaxed = true)
        val calls = mutableListOf<JsonObject>()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            calls += secondArg<JsonObject>()
            mockk<PostgrestResult>(relaxed = true)
        }
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(1, "alternate", 101, 5, 16,
            listOf(101), "tt123:5:16", providerSources = listOf(com.nuvio.tv.domain.model.EpisodeSourceIdentity(
                "tt123:5:14", com.nuvio.tv.domain.model.EpisodeCoordinates(5, 14), listOf(101), "Example")))
        val local = progress("tt123").copy(contentType = "series", videoId = context.requestVideoId,
            season = 5, episode = 16, tvdbContext = context)
        val key = context.storageKey(local.contentId)
        progressService(postgrest, mockk(relaxed = true), mutations).pushSingleToRemote(key, local, 1).getOrThrow()
        val payload = calls.single().getValue("p_entries").jsonArray.single().jsonObject
        assertEquals(14, payload.getValue("episode").jsonPrimitive.int)
        assertEquals("tt123:5:14", payload.getValue("video_id").jsonPrimitive.content)
        assertEquals("tt123_s5e14", payload.getValue("progress_key").jsonPrimitive.content)
        assertEquals(16, local.episode)
        coVerify { mutations.acknowledgeProgressUpserts(mapOf(key to local), 1) }
    }

    @Test fun `unresolved combined progress never reaches the native cloud progress RPC`() = runTest {
        val postgrest = mockk<Postgrest>()
        val mutations = mockk<WatchStateMutationStore>(relaxed = true)
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(1, "alternate", 101, 5, 16,
            listOf(101, 102), "tt123:5:16", 44)
        val local = progress("tt123").copy(contentType = "series", season = 5, episode = 16,
            position = 44 * 60_000L, duration = 44 * 60_000L, tvdbContext = context)
        val key = context.storageKey(local.contentId)
        coEvery { mutations.pendingProgressDeletes(1) } returns emptySet()
        coEvery { mutations.pendingProgressUpserts(1) } returns mapOf(key to local)
        val service = progressService(postgrest, mockk(relaxed = true), mutations)
        service.pushSingleToRemote(key, local, 1).getOrThrow()
        service.pushToRemote(1).getOrThrow()
        coVerify(exactly = 0) { postgrest.rpc(any(), any<JsonObject>()) }
        coVerify(exactly = 2) { mutations.acknowledgeProgressUpserts(mapOf(key to local), 1) }
        assertEquals(context, local.tvdbContext)
    }

    @Test fun `bulk TVDB aliases coalesce by native identity using the newest position`() = runTest {
        val postgrest = mockk<Postgrest>()
        val mutations = mockk<WatchStateMutationStore>(relaxed = true)
        val calls = mutableListOf<JsonObject>()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            calls += secondArg<JsonObject>(); mockk<PostgrestResult>(relaxed = true)
        }
        val source = com.nuvio.tv.domain.model.EpisodeSourceIdentity("tt123:1:1",
            com.nuvio.tv.domain.model.EpisodeCoordinates(1, 1), listOf(101), "Example")
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(1, "dvd", 101, 1, 2,
            listOf(101), "tt123:1:2", providerSources = listOf(source))
        val newer = progress("tt123").copy(contentType = "series", season = 1, episode = 2,
            tvdbContext = context, lastWatched = 200, position = 500)
        val older = newer.copy(tvdbContext = context.copy(orderType = "alternate"), lastWatched = 100, position = 100)
        val pending = linkedMapOf(context.storageKey("tt123") to newer, older.tvdbContext!!.storageKey("tt123") to older)
        coEvery { mutations.pendingProgressDeletes(1) } returns emptySet()
        coEvery { mutations.pendingProgressUpserts(1) } returns pending
        progressService(postgrest, mockk(relaxed = true), mutations).pushToRemote(1).getOrThrow()
        val row = calls.single().getValue("p_entries").jsonArray.single().jsonObject
        assertEquals("tt123_s1e1", row.getValue("progress_key").jsonPrimitive.content)
        assertEquals(500, row.getValue("position").jsonPrimitive.int)
        coVerify { mutations.acknowledgeProgressUpserts(pending, 1) }
    }

    @Test fun `TVDB resume reads reuse the cloud RPC without writes and keep profile caches separate`() = runTest {
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns io.github.jan.supabase.serializer.KotlinXSerializer()
        val profiles = mutableListOf<Int>()
        coEvery { postgrest.rpc("sync_pull_watch_progress", any<JsonObject>()) } coAnswers {
            profiles += secondArg<JsonObject>().getValue("p_profile_id").jsonPrimitive.int
            PostgrestResult("[]", io.ktor.http.Headers.Empty, postgrest)
        }
        val service = progressService(postgrest, mockk(relaxed = true), mockk(relaxed = true))
        repeat(2) { assertTrue(service.readTvdbResume(1).isEmpty()) }
        assertTrue(service.readTvdbResume(2).isEmpty())
        assertEquals(listOf(1, 2), profiles)
        coVerify(exactly = 0) { postgrest.rpc("sync_push_watch_progress", any<JsonObject>()) }
        coVerify(exactly = 0) { postgrest.rpc("sync_push_watched_items", any<JsonObject>()) }
    }

    @Test fun `combined cloud payloads trigger native completion only after the corresponding boundary`() = runTest {
        val postgrest = mockk<Postgrest>()
        val watched = mutableSetOf<Int>()
        val episodes = mutableSetOf<Int>()
        coEvery { postgrest.rpc("sync_push_watch_progress", any<JsonObject>()) } coAnswers {
            secondArg<JsonObject>().getValue("p_entries").jsonArray.forEach { value ->
                val row = value.jsonObject
                val number = row.getValue("episode").jsonPrimitive.int
                episodes += number
                if (row.getValue("duration").jsonPrimitive.content.toLong() >= 60_000 &&
                    row.getValue("position").jsonPrimitive.content.toLong() >= row.getValue("duration").jsonPrimitive.content.toLong() * 0.9) watched += number
            }
            mockk<PostgrestResult>(relaxed = true)
        }
        val refs = listOf(14 to 101L, 15 to 102L).map { (number, id) ->
            com.nuvio.tv.domain.model.EpisodeSourceIdentity("tt123:5:$number",
                com.nuvio.tv.domain.model.EpisodeCoordinates(5, number), listOf(id), "Example", 22)
        }
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(1, "alternate", 103, 5, 16,
            listOf(101, 102), "tt123:5:16", 44, providerSources = refs)
        val original = progress("tt123").copy(contentType = "series", videoId = context.requestVideoId,
            season = 5, episode = 16, tvdbContext = context, duration = 2_800_000)
        val service = progressService(postgrest, mockk(relaxed = true), mockk(relaxed = true))
        service.pushSingleToRemote(context.storageKey("tt123"), original.copy(position = 1_372_000), 1).getOrThrow()
        assertTrue(watched.isEmpty())
        service.pushSingleToRemote(context.storageKey("tt123"), original.copy(position = 2_100_000), 1).getOrThrow()
        assertEquals(setOf(14), watched)
        service.pushSingleToRemote(context.storageKey("tt123"), original.copy(position = 2_800_000), 1).getOrThrow()
        assertEquals(setOf(14, 15), watched)
        assertEquals(setOf(14, 15), episodes)
    }

    @Test fun `TVDB cloud resume excludes ambiguous pre-fix rows without deleting or rewriting them`() = runTest {
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns io.github.jan.supabase.serializer.KotlinXSerializer()
        val cutoff = com.nuvio.tv.data.tvdb.TVDB_NATIVE_CLOUD_PROGRESS_FROM
        val body = """[
            {"user_id":"test","content_id":"tt123","content_type":"series","video_id":"tt123:5:14",
             "season":5,"episode":14,"position":100,"duration":1000,"last_watched":${cutoff - 1},"progress_key":"tt123_s5e14"},
            {"user_id":"test","content_id":"tt123","content_type":"series","video_id":"tt123:5:15",
             "season":5,"episode":15,"position":200,"duration":1000,"last_watched":${cutoff + 1},"progress_key":"tt123_s5e15"}
        ]"""
        coEvery { postgrest.rpc("sync_pull_watch_progress", any<JsonObject>()) } returns
            PostgrestResult(body, io.ktor.http.Headers.Empty, postgrest)
        val mutations = mockk<WatchStateMutationStore>(relaxed = true)
        val prefs = mockk<WatchProgressPreferences>(relaxed = true)
        val rows = progressService(postgrest, prefs, mutations).readTvdbResume(1)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.episode == 15 })
        coVerify(exactly = 0) { postgrest.rpc("sync_push_watch_progress", any<JsonObject>()) }
        coVerify(exactly = 0) { postgrest.rpc("sync_delete_watch_progress", any<JsonObject>()) }
        coVerify(exactly = 0) { mutations.queueProgressDeletes(any(), any()) }
        coVerify(exactly = 0) { prefs.removeProgressKeys(any(), any()) }
    }

    private fun progressService(
        postgrest: Postgrest,
        preferences: WatchProgressPreferences,
        mutationStore: WatchStateMutationStore,
        canRefreshJwt: Boolean = false
    ): WatchProgressSyncService {
        val authManager = mockk<AuthManager>(relaxed = true)
        coEvery { authManager.refreshSessionIfJwtExpired(any()) } returns canRefreshJwt
        return WatchProgressSyncService(
            authManager = authManager,
            postgrest = postgrest,
            watchProgressPreferences = preferences,
            trackingProviderRegistry = TrackingProgressProviderRegistry(emptySet()),
            traktSettingsDataStore = mockk(relaxed = true),
            profileManager = profileManager(),
            syncClientIdentity = syncClientIdentity(),
            mutationStore = mutationStore
        )
    }

    private fun watchedService(
        postgrest: Postgrest,
        preferences: WatchedItemsPreferences,
        mutationStore: WatchStateMutationStore
    ): WatchedItemsSyncService {
        val authManager = mockk<AuthManager>(relaxed = true)
        coEvery { authManager.refreshSessionIfJwtExpired(any()) } returns false
        return WatchedItemsSyncService(
            authManager = authManager,
            postgrest = postgrest,
            watchedItemsPreferences = preferences,
            trackingProviderRegistry = TrackingProgressProviderRegistry(emptySet()),
            traktSettingsDataStore = mockk(relaxed = true),
            profileManager = profileManager(),
            syncClientIdentity = syncClientIdentity(),
            mutationStore = mutationStore
        )
    }

    private fun profileManager(): ProfileManager = mockk<ProfileManager>().also {
        every { it.activeProfileId } returns MutableStateFlow(1)
    }

    private fun syncClientIdentity(): SyncClientIdentity = mockk<SyncClientIdentity>().also {
        every { it.currentClientId() } returns "nuvio-tv-test-client"
    }

    private fun progress(contentId: String) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 2_000L,
        duration = 10_000L,
        lastWatched = 100L
    )

    private fun watched(contentId: String) = WatchedItem(
        contentId = contentId,
        contentType = "movie",
        title = contentId,
        season = null,
        episode = null,
        watchedAt = 100L
    )
}
