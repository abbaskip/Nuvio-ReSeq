package com.nuvio.tv.data.cw

import com.google.gson.Gson
import com.nuvio.tv.domain.model.WatchProgress
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CwProjectionStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val p = WatchProgress("show", "series", "Show", null, null, null, "show:1:1", 1, 1, null, 20, 100, 1_000)

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
