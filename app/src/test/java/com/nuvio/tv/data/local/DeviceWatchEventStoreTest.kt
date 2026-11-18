package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.core.sync.*
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.data.tvdb.TvdbTrackingResolver
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

/** Synthetic device + three connected trackers. No network, credentials or Android device. */
class DeviceWatchEventStoreTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }
    private class Fixture(val selected: TrackingProviderId, val split: Boolean) {
        val stores = mutableMapOf<Pair<Int, String>, MemoryStore>()
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::MemoryStore) }
        }
        val active = MutableStateFlow(1)
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns active; every { resolvedActiveProfileId } returns active }
        val local = WatchProgressPreferences(factory, profiles)
        val watched = WatchedItemsPreferences(factory, profiles)
        val context = if (split) TvdbPlaybackContext(73244, "alttwo", 10719652, 6, 17, listOf(10719652), "office:6:17") else null
        val prior = WatchProgress(if (split) "office" else "friends", "series", if (split) "The Office" else "Friends",
            null, null, null, context?.requestVideoId ?: "friends:3:2", context?.season ?: 3, context?.episode ?: 2,
            if (split) "The Delivery Part 1" else "The One Where No One's Ready", 1, 1, 1_000,
            tvdbContext = context, tvdbEpisodeIds = context?.episodeIds)
        val oldWatched = WatchedItem(prior.contentId, "series", prior.name, prior.season, prior.episode, prior.lastWatched)
        val media = buildTrackingMediaReference("series", prior.contentId, prior.videoId,
            seasonNumber = prior.season, episodeNumber = prior.episode).let { it.copy(catalog = it.catalog!!.copy(tvdbContext = context)) }
        val native = media.copy(catalog = media.catalog!!.copy(tvdbContext = null),
            episode = media.episode!!.copy(number = if (split) 16 else 2))
        var confirmed = false
        val removed = mutableListOf<Pair<TrackingProviderId, TrackingWatchEvent>>()
        val providers = TrackingProviderId.entries.map { id -> mockk<TrackingProgressProvider> {
            every { providerId } returns id
            every { isAuthenticated } returns flowOf(true)
            coEvery { watchBaseline(any()) } answers { TrackingWatchBaseline(firstArg(), true, 1_000) }
            coEvery { removeWatchEvent(any(), any()) } answers {
                if (confirmed) { removed += id to secondArg<TrackingWatchEvent>(); true } else false
            }
        } }
        val registry = TrackingProgressProviderRegistry(providers.toSet())
        val resolver = mockk<TvdbTrackingResolver> {
            coEvery { resolveWatchEventTargets(1, any(), any()) } returns listOf(native)
            coEvery { scrobbleEvents(any(), TrackingScrobbleAction.STOP, any(), any()) } returns listOf(
                (if (split) TrackingScrobbleAction.PAUSE else TrackingScrobbleAction.STOP) to
                    TrackingScrobbleEvent(native, if (split) 50.0 else 100.0, allowCompletion = !split))
        }
        var clock = 10_000L
        val cw = mockk<com.nuvio.tv.data.cw.ContinueWatchingRepository>(relaxed = true)
        fun journal() = DeviceWatchEventStore(factory, profiles, local, watched, Provider { registry }, Provider { resolver },
            Provider { cw }).also { it.now = { clock } }
        val journal = journal()
        val mutations = mockk<WatchStateMutationStore>(relaxed = true)
        fun repository(events: DeviceWatchEventStore) = WatchProgressRepositoryImpl(local,
            mockk(relaxed = true) {
                every { resolvedWatchProgressSource(1) } returns flowOf(WatchProgressSource.valueOf(selected.name))
                coEvery { getWatchProgressSource(1) } returns WatchProgressSource.valueOf(selected.name)
            }, mockk(relaxed = true), mockk(relaxed = true), watched, mockk(relaxed = true),
            mockk(relaxed = true) { every { isAuthenticated } returns false }, mockk(relaxed = true), mockk(relaxed = true), profiles,
            registry, TrackingHistoryWriterRegistry(emptySet()), mutations, mockk(relaxed = true), deviceEvents = Provider { events })

        suspend fun rewatch(previous: WatchProgress = prior): WatchProgress {
            local.saveProgress(previous, 1)
            watched.markAsWatched(oldWatched.copy(season = previous.season, episode = previous.episode), 1)
            journal.beforeStart(TrackingScrobbleEvent(media, 0.0))
            clock = 30_000
            val played = journal.record(1, prior.copy(lastWatched = clock))
            local.saveProgress(played, 1)
            local.recordDeviceProgress(1, listOf(played))
            watched.markAsWatched(oldWatched.copy(watchedAt = clock), 1)
            return played
        }
    }

    @Test fun `normal and split rewatch removal restores older device history for every selected tracker before and after confirmation`() = runTest {
        for (source in TrackingProviderId.entries) for (split in listOf(false, true)) for (confirmedFirst in listOf(false, true)) {
            val f = Fixture(source, split)
            val played = f.rewatch()
            assertTrue("Immediate last-watch action: $source split=$split", played.hasRemovableWatchEvent())
            val translatedConfirmation = played.copy(deviceWatchEventId = null, source = WatchProgress.SOURCE_REMOTE_HISTORY,
                trackingProviderId = source.storageId)
            assertTrue(f.journal.decorate(listOf(translatedConfirmation), f.journal.events(1)).single().hasRemovableWatchEvent())
            val nativePause = played.copy(tvdbContext = null, tvdbEpisodeIds = null, deviceWatchEventId = null,
                episode = if (split) 16 else 2, progressPercent = if (split) 50f else 100f,
                source = WatchProgress.SOURCE_REMOTE_PLAYBACK, trackingProviderId = source.storageId)
            f.confirmed = confirmedFirst
            val shown = if (confirmedFirst) played.copy(source = nativePause.source) else played
            f.repository(f.journal).removeLastTraktWatch(played.contentId,
                Video(id = played.videoId, title = played.episodeTitle!!, season = played.season, episode = played.episode,
                    released = null, thumbnail = null, overview = null), shown)
            assertEquals(listOf(f.prior), f.local.getAllRawEntries(1).values.toList())
            assertEquals(listOf(f.oldWatched), f.watched.getAllItems(1))
            assertTrue(f.local.observeDeviceProgress(1, source.name).first().isEmpty())
            val restarted = f.journal()
            // Stale/native echoes cannot rebuild either the raw combined pause or selected Part 1.
            assertTrue(restarted.decorate(listOf(played, nativePause), restarted.events(1)).isEmpty())
            f.confirmed = true
            restarted.reconcile(1)
            assertEquals(TrackingProviderId.entries.toSet(), f.removed.map { it.first }.toSet())
            assertTrue(f.removed.all { it.second.priorWatchedAt == 1_000L && it.second.priorKnown })
            assertTrue(f.removed.all { it.second.completionExpected == !split })
            assertEquals(listOf(f.oldWatched), f.watched.getAllItems(1))
        }
    }

    @Test fun `removing Part 1 neither revokes earlier full completion nor a later Part 2 play sharing its native episode`() = runTest {
        val f = Fixture(TrackingProviderId.TRAKT, true)
        val played = f.rewatch()
        val removed = f.journal.requestRemoval(1, played)
        f.clock = 31_000
        val part2 = played.copy(deviceWatchEventId = null, episode = 18, videoId = "office:6:18",
            tvdbContext = f.context!!.copy(episodeId = 1832422, episode = 18, episodeIds = listOf(1832422), requestVideoId = "office:6:18"),
            tvdbEpisodeIds = listOf(1832422), lastWatched = f.clock, progressPercent = 10f)
        val next = f.journal.record(1, part2)
        val nativeNext = next.copy(deviceWatchEventId = null, tvdbContext = null, tvdbEpisodeIds = null, episode = 16)
        assertEquals(listOf(f.prior, next, nativeNext), f.journal.decorate(listOf(f.prior, played, next, nativeNext), f.journal.events(1)))
        assertFalse(removed.removes(f.prior))
        // An old STOP finishing later must not extend an undo over Part 2.
        f.clock = 90_000
        f.journal.afterStop(1 to removed.id)
        assertEquals(listOf(next, nativeNext), f.journal.decorate(listOf(played, next, nativeNext), f.journal.events(1)))
        coVerify { f.cw.deviceWatchRemoved(1, match { it.id == removed.id && it.lastAt + 2_000 == next.lastWatched - 1 }) }
    }

    @Test fun `own event journal is profile scoped and imports do not acquire removable watch identity`() = runTest {
        val f = Fixture(TrackingProviderId.TRAKT, false)
        val played = f.rewatch()
        assertTrue(f.journal.events(2).isEmpty())
        val imported = f.prior.copy(source = WatchProgress.SOURCE_SIMKL_PLAYBACK, trackingProviderId = "simkl")
        assertEquals(imported, f.journal.record(1, imported))
        assertFalse(imported.hasRemovableWatchEvent())
        assertNull(f.journal.decorate(listOf(imported.copy(deviceWatchEventId = "other-box", lastWatched = 90_000)),
            f.journal.events(1)).single().deviceWatchEventId)
        f.journal.requestRemoval(1, played)
        f.active.value = 2
        f.journal.reconcile(1)
        assertTrue(f.removed.isEmpty())
    }

    @Test fun `local22 first-part receipt is correlated despite a delayed native pause timestamp`() = runTest {
        val f = Fixture(TrackingProviderId.TRAKT, true)
        val time = System.currentTimeMillis()
        val oldDevicePlay = f.prior.copy(lastWatched = time)
        f.local.saveProgress(oldDevicePlay, 1)
        f.local.recordDeviceProgress(1, listOf(oldDevicePlay))
        val selected = oldDevicePlay.copy(source = WatchProgress.SOURCE_TRAKT_PLAYBACK, traktEpisodeId = 182129,
            traktPlaybackId = 77, lastWatched = time + 20_000)
        assertTrue(selected.hasRemovableWatchEvent())
        f.repository(f.journal).removeLastTraktWatch(selected.contentId, Video(id = selected.videoId,
            title = "The Delivery Part 1", season = 6, episode = 17, released = null, thumbnail = null, overview = null), selected)
        assertTrue(f.local.getAllRawEntries(1).isEmpty())
        assertTrue(f.local.observeDeviceProgress(1, "TRAKT").first().isEmpty())
        val native = selected.copy(tvdbContext = null, tvdbEpisodeIds = null, episode = 16)
        val restarted = f.journal()
        assertTrue(restarted.decorate(listOf(oldDevicePlay, native), restarted.events(1)).isEmpty())
        assertFalse(restarted.events(1).single().removes(f.prior))
    }

    @Test fun `a delayed old STOP cannot take the identity of a newer rewatch of the same episode`() = runTest {
        val f = Fixture(TrackingProviderId.TRAKT, false)
        val old = f.rewatch()
        f.clock = 40_000
        f.journal.beforeStart(TrackingScrobbleEvent(f.media, 0.0))
        val recent = f.journal.record(1, old.copy(deviceWatchEventId = null, lastWatched = 60_000))
        assertNotEquals(old.deviceWatchEventId, recent.deviceWatchEventId)
        f.clock = 90_000
        f.journal.afterStop(1 to old.deviceWatchEventId!!)
        assertEquals(recent.deviceWatchEventId, f.journal.currentEvent(TrackingScrobbleEvent(f.media, 100.0))!!.second)
        val rows = f.journal.decorate(listOf(old, recent, recent.copy(deviceWatchEventId = null)), f.journal.events(1))
        assertEquals(listOf(old.deviceWatchEventId, recent.deviceWatchEventId, recent.deviceWatchEventId), rows.map { it.deviceWatchEventId })
        assertEquals(recent.deviceWatchEventId, f.journal.record(1, recent.copy(deviceWatchEventId = null, lastWatched = 61_000)).deviceWatchEventId)
    }

    @Test fun `the real undo observer leaves normal scrobble events unchanged for every connected tracker`() = runTest {
        val f = Fixture(TrackingProviderId.TRAKT, false)
        val written = mutableListOf<Triple<TrackingProviderId, TrackingScrobbleAction, TrackingScrobbleEvent>>()
        val scrobblers = TrackingProviderId.entries.map { id -> mockk<TrackingScrobbler> {
            every { providerId } returns id
            coEvery { scrobble(any(), any()) } answers { written += Triple(id, firstArg(), secondArg()); Unit }
        } }
        val registry = mockk<TrackingProviderRegistry> { every { connectedScrobblers() } returns scrobblers }
        val coordinator = TrackingScrobbleCoordinator(registry, watchEvents = Provider { f.journal })
        val sequence = listOf(TrackingScrobbleAction.START to 0.0, TrackingScrobbleAction.PAUSE to 47.0,
            TrackingScrobbleAction.START to 47.0, TrackingScrobbleAction.STOP to 100.0)
        for ((action, percent) in sequence) assertTrue(coordinator.scrobble(action, TrackingScrobbleEvent(f.media, percent)).isEmpty())
        for (id in TrackingProviderId.entries) assertEquals(sequence.map { (action, percent) -> action to TrackingScrobbleEvent(f.media, percent) },
            written.filter { it.first == id }.map { it.second to it.third })
    }

    @Test fun `immediate Details undo wins over a final tracker write without visiting Home`() = runTest {
        for (selected in TrackingProviderId.entries) for (split in listOf(false, true))
            for (dispatchBeforeUndo in listOf(true, false)) for (firstWatch in listOf(true, false)) {
            val f = Fixture(selected, split)
            val previous = if (!firstWatch) f.prior else f.prior.copy(
                videoId = "${f.prior.contentId}:${f.prior.season}:${f.prior.episode!! - 1}",
                episode = f.prior.episode!! - 1, tvdbContext = null, tvdbEpisodeIds = null)
            if (firstWatch) for (provider in f.providers) coEvery { provider.watchBaseline(any()) } answers {
                TrackingWatchBaseline(firstArg(), true, null)
            }
            val played = f.rewatch(previous)
            val remote = TrackingProviderId.entries.associateWith { MutableStateFlow(listOf(previous)) }
            for (provider in f.providers) {
                val rows = remote.getValue(provider.providerId)
                every { provider.allProgress } returns rows
                every { provider.episodeProgress(any()) } returns rows.map { items ->
                    items.associateBy { it.season!! to it.episode!! }
                }
                coEvery { provider.removeWatchEvent(any(), any()) } coAnswers {
                    val removal = secondArg<TrackingWatchEvent>()
                    val matching = rows.value.filter { removal.contains(it.lastWatched) }
                    rows.value = rows.value - matching.toSet()
                    // Trakt keeps an absent completion pending; the other trackers may
                    // acknowledge absence before the final write reaches their server.
                    matching.isNotEmpty() || provider.providerId != TrackingProviderId.TRAKT
                }
            }
            val inFlight = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val scrobblers = TrackingProviderId.entries.map { id -> mockk<TrackingScrobbler> {
                every { providerId } returns id
                coEvery { scrobble(any(), any()) } coAnswers {
                    inFlight.complete(Unit)
                    finish.await()
                    remote.getValue(id).value += played.copy(deviceWatchEventId = null,
                        source = WatchProgress.SOURCE_REMOTE_HISTORY, trackingProviderId = id.storageId,
                        lastWatched = f.clock)
                }
            } }
            val registry = mockk<TrackingProviderRegistry> { every { connectedScrobblers() } returns scrobblers }
            val coordinator = TrackingScrobbleCoordinator(registry, tvdb = f.resolver.takeIf { split },
                watchEvents = Provider { f.journal })
            val dispatch = CompletableDeferred<Unit>()
            val stop = async(start = CoroutineStart.UNDISPATCHED) {
                dispatch.await()
                coordinator.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(f.media, 100.0))
            }
            if (dispatchBeforeUndo) { dispatch.complete(Unit); inFlight.await() }
            val repository = f.repository(f.journal)
            repository.removeLastTraktWatch(played.contentId,
                Video(played.videoId, played.episodeTitle!!, season = played.season, episode = played.episode,
                    released = null, thumbnail = null, overview = null), played)
            f.clock = 90_000 // slow final STOP/part PAUSE, after the original undo window
            dispatch.complete(Unit)
            inFlight.await()
            finish.complete(Unit)
            assertTrue(stop.await().isEmpty())
            val label = "$selected split=$split dispatchBeforeUndo=$dispatchBeforeUndo firstWatch=$firstWatch"
            coVerify(exactly = 1) { f.cw.deviceWatchRemoved(1, match { it.id == played.deviceWatchEventId && it.removed }) }
            for (scrobbler in scrobblers) coVerify(exactly = 1) { scrobbler.scrobble(
                if (split) TrackingScrobbleAction.PAUSE else TrackingScrobbleAction.STOP,
                if (split) TrackingScrobbleEvent(f.native, 50.0, allowCompletion = false)
                else TrackingScrobbleEvent(f.media, 100.0)) }
            for ((provider, rows) in remote) assertEquals("$label remote=$provider", listOf(previous), rows.value)
            assertEquals(label, mapOf((previous.season!! to previous.episode!!) to previous),
                repository.getAllEpisodeProgress(played.contentId, 1).first())
            assertEquals(label, listOf(previous), f.local.getAllRawEntries(1).values.toList())
            // Restart does not resurrect the removed watch or require a Home refresh.
            assertEquals(label, listOf(previous), f.journal().decorate(
                remote.getValue(selected).value, f.journal().events(1)))
        }
    }
    @Test fun `undo acknowledgement from before a final STOP cannot settle the newer write`() = runTest {
        val f = Fixture(TrackingProviderId.TRAKT, false)
        val played = f.rewatch()
        val reading = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Unit>()
        var calls = 0
        for (provider in f.providers) {
            every { provider.isAuthenticated } returns flowOf(provider.providerId == TrackingProviderId.TRAKT)
            coEvery { provider.removeWatchEvent(any(), any()) } coAnswers {
                if (++calls == 1) { reading.complete(Unit); acknowledge.await() }
                true
            }
        }
        val undo = async(start = CoroutineStart.UNDISPATCHED) {
            f.repository(f.journal).removeLastTraktWatch(played.contentId,
                Video(played.videoId, played.episodeTitle!!, season = played.season, episode = played.episode,
                    released = null, thumbnail = null, overview = null), played)
        }
        reading.await()
        // Same millisecond: a timestamp comparison alone cannot detect this write.
        val finalWrite = async(start = CoroutineStart.UNDISPATCHED) { f.journal.afterStop(1 to played.deviceWatchEventId!!) }
        f.journal.observe(1).first { events -> events.any { it.id == played.deviceWatchEventId && it.stopRevision > 0 } }
        acknowledge.complete(Unit)
        undo.await()
        finalWrite.await()
        assertEquals("Must reconcile again after the final tracker write", 2, calls)
        assertEquals(setOf("TRAKT"), f.journal.events(1).single { it.id == played.deviceWatchEventId }.handled)
    }
}
