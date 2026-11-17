package com.nuvio.tv.data.cw

import android.content.Context
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import com.nuvio.tv.domain.model.WatchProgress
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CwProjectionStoreTest {
    @get:org.junit.Rule val androidRename = com.nuvio.tv.data.cw.AndroidAtomicRenameRule()
    @get:Rule val folder = TemporaryFolder()
    private val p = WatchProgress("show", "series", "Show", null, null, null, "show:1:1", 1, 1, null, 20, 100, 1_000)

    @Test fun `changing furthest after unwatch republishes E8 and rejects late E5 without another history event`() = runTest {
        val profile = MutableStateFlow(1)
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns profile; every { resolvedActiveProfileId } returns profile }
        val settings = mockk<TraktSettingsDataStore> { coEvery { getWatchProgressSource(1) } returns WatchProgressSource.TRAKT }
        val context = mockk<Context> { every { filesDir } returns folder.root }
        val store = CwProjectionStore(File(folder.root, "cw_enrichment"))
        fun watched(episode: Int, at: Long) = p.copy(episode = episode, videoId = "show:1:$episode",
            position = 100, duration = 100, lastWatched = at)
        val recent = watched(4, 2000)
        val furthest = watched(7, 1000)
        val state = CwMembershipReducer.progress(store.read(1, "TRAKT"), listOf(furthest, recent))
        store.write(state)
        val owner = ContinueWatchingRepository(context, profiles, settings, Provider { error("No tracker refresh expected") })
        suspend fun lease(preferFurthest: Boolean) = requireNotNull(owner.nextUpLease(owner.snapshot(), preferFurthest))
        fun offer(seed: WatchProgress, episode: Int) = seed.seriesChoice().copy(episode = episode,
            videoId = "show:1:$episode", chooseEpisode = false)
        val five = offer(recent, 5)
        val eight = offer(furthest, 8)
        val newestLease = lease(false)
        owner.enrich(newestLease, nextUp = listOf(five))
        val furthestLease = lease(true)
        assertEquals(five, owner.projection.value!!.nextUp.single())
        owner.enrich(furthestLease, nextUp = listOf(eight))
        owner.enrich(newestLease, nextUp = listOf(five))
        owner.enrich(lease(true), nextUp = listOf(eight.copy(poster = "late artwork")))
        assertEquals(eight, owner.projection.value!!.nextUp.single())
        assertEquals(eight, store.read(1, "TRAKT").forPresentation().nextUp.single())
        owner.enrich(lease(false), nextUp = listOf(five))
        owner.enrich(furthestLease, nextUp = listOf(eight))
        assertEquals(five, owner.projection.value!!.nextUp.single())
    }

    @Test fun `legacy migration preserves identity progress and files without mixing sources`() {
        val legacy = File(folder.root, "inprogress_1.json")
        legacy.writeText(Gson().toJson(listOf(p.cached())))
        val store = CwProjectionStore(folder.root)
        val migrated = store.read(1, "TRAKT")
        assertEquals(listOf(p.cached()), migrated.inProgress)
        assertTrue(legacy.exists())
        assertEquals(migrated, CwProjectionStore(folder.root).read(1, "TRAKT"))
        assertTrue(store.read(1, "SIMKL").members.isEmpty())
        assertTrue(store.read(2, "TRAKT").members.isEmpty())
    }

    @Test fun `crash before atomic replacement exposes previous complete revision`() {
        val store = CwProjectionStore(folder.root)
        val initial = CwMembershipReducer.progress(store.read(1, "TRAKT"), listOf(p))
        store.write(initial)
        File(folder.root, "projection_1_TRAKT.json.pending").writeText("{incomplete write")
        assertEquals(initial, CwProjectionStore(folder.root).read(1, "TRAKT"))
        val completed = CwMembershipReducer.progress(initial, listOf(p.copy(position = 100, lastWatched = 2_000)))
        store.write(completed)
        val restored = CwProjectionStore(folder.root).read(1, "TRAKT")
        assertEquals(completed, restored)
        assertTrue(restored.inProgress.isEmpty())
        assertTrue(restored.nextUp.single().chooseEpisode)
    }

    @Test fun `removal and duplicate completion survive process restart`() {
        val store = CwProjectionStore(folder.root)
        val initial = CwMembershipReducer.progress(store.read(1, "TRAKT"), listOf(p))
        val removed = CwMembershipReducer.remove(initial, initial.lease,
            CwRemovalProof(CwRemovalReason.USER_REMOVAL, setOf("show"), 2_000))
        store.write(removed)
        val restored = store.read(1, "TRAKT")
        assertEquals(removed, CwMembershipReducer.progress(restored, listOf(p)))
    }

    @Test fun `older completed episodes remain protected after process restart`() {
        val store = CwProjectionStore(folder.root)
        val first = p.copy(position = 100)
        val second = first.copy(videoId = "show:1:2", episode = 2, lastWatched = 2_000)
        store.write(CwMembershipReducer.progress(store.read(1, "TRAKT"), listOf(first, second)))
        val restored = CwProjectionStore(folder.root).read(1, "TRAKT")
        val stale = second.seriesChoice().copy(videoId = first.videoId, season = 1, episode = 1, chooseEpisode = false)
        assertEquals(restored, CwMembershipReducer.enrich(restored, restored.lease, nextUp = listOf(stale)))
    }

    @Test fun `projection without completion ledger still loads nondestructively`() {
        val store = CwProjectionStore(folder.root)
        val before = CwMembershipReducer.progress(store.read(1, "TRAKT"), listOf(p))
        val json = Gson().toJsonTree(before).asJsonObject.apply { remove("completed") }
        File(folder.root, "projection_1_TRAKT.json").writeText(json.toString())
        assertEquals(before, store.read(1, "TRAKT"))
    }
}
