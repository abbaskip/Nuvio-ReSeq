package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TieredStreamAutoPlayTest {
    private val addons = setOf("AIOLists", "Approved", "Backup")
    private val settings = PlayerSettings(
        streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH,
        streamAutoPlayRegex = "2160p",
        streamAutoPlaySelectedAddons = setOf("Approved"),
        streamAutoPlayTimeoutSeconds = 10,
        streamAutoPlayImmediateEnabled = true,
        streamAutoPlayImmediateAddons = setOf("AIOLists"),
        streamAutoPlayAnySourceFallbackEnabled = true,
        streamAutoPlayAnySourceFallbackSeconds = 30,
    )

    private fun stream(addon: String, title: String = "1080p", id: String = title) = Stream(
        name = title, title = title, description = null, url = "https://example.test/$addon/$id",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
        behaviorHints = null, addonName = addon, addonLogo = null,
    )

    @Test fun `immediate addons bypass normal source and regex filters after the full settling window`() {
        val session = TieredStreamAutoPlay(settings.copy(streamAutoPlayAddonsEnabled = false), addons)
        val chosen = stream("AIOLists")
        val snapshot = StreamAutoPlaySnapshot(listOf(chosen), complete = true)
        assertNull(session.evaluate(snapshot, 1_000).stream)
        assertNull(session.evaluate(snapshot, 1_499).stream)
        assertEquals(chosen, session.evaluate(snapshot, 1_500).stream)
    }

    @Test fun `settling uses the latest addon order and is not restarted by repeated list updates`() {
        val session = TieredStreamAutoPlay(settings, addons)
        val initial = stream("AIOLists", "720p")
        val better = stream("AIOLists", "1080p")
        session.evaluate(StreamAutoPlaySnapshot(listOf(initial)), 1_000)
        session.evaluate(StreamAutoPlaySnapshot(listOf(better, initial)), 1_200)
        val latest = StreamAutoPlaySnapshot(listOf(better.copy(description = "batch complete"), initial), true)
        assertNull(session.evaluate(latest, 1_499).stream)
        assertEquals(latest.streams.first(), session.evaluate(latest, 1_500).stream)
    }

    @Test fun `ordinary approved results do not cut short an immediate addon batch`() {
        val session = TieredStreamAutoPlay(settings, addons)
        val preferred = stream("AIOLists")
        val snapshot = StreamAutoPlaySnapshot(listOf(stream("Approved", "2160p"), preferred), true)
        assertNull(session.evaluate(snapshot, 0).stream)
        assertEquals(preferred, session.evaluate(snapshot, 500).stream)
    }

    @Test fun `an empty immediate selection does not mean all addons`() {
        val session = TieredStreamAutoPlay(settings.copy(streamAutoPlayImmediateAddons = emptySet()), addons)
        val snapshot = StreamAutoPlaySnapshot(listOf(stream("AIOLists")))
        assertNull(session.evaluate(snapshot, 0).stream)
        assertNull(session.evaluate(snapshot, 500).stream)
    }

    @Test fun `uninstalled addons cannot trigger the immediate tier`() {
        val session = TieredStreamAutoPlay(settings, setOf("Approved", "Backup"))
        val snapshot = StreamAutoPlaySnapshot(listOf(stream("AIOLists")))
        session.evaluate(snapshot, 0)
        assertNull(session.evaluate(snapshot, 500).stream)
    }

    @Test fun `approved tier waits for its deadline and respects addon and regex restrictions`() {
        val session = TieredStreamAutoPlay(settings, addons)
        val approved = stream("Approved", "2160p")
        val snapshot = StreamAutoPlaySnapshot(listOf(stream("Backup", "2160p"), stream("Approved"), approved))
        session.evaluate(snapshot, 0)
        assertNull(session.evaluate(snapshot, 9_999).stream)
        assertEquals(approved, session.evaluate(snapshot, 10_000).stream)
    }

    @Test fun `completed searches can still play an approved source before the maximum wait`() {
        val session = TieredStreamAutoPlay(settings, addons)
        val approved = stream("Approved", "2160p")
        assertEquals(approved, session.evaluate(StreamAutoPlaySnapshot(listOf(approved), true), 1_000).stream)
    }

    @Test fun `picker appears after the approved deadline and fallback uses the total search time`() {
        val session = TieredStreamAutoPlay(settings, addons)
        val backup = stream("Backup")
        val snapshot = StreamAutoPlaySnapshot(listOf(backup), true)
        assertFalse(session.evaluate(snapshot, 0).showPicker)
        val waiting = session.evaluate(snapshot, 10_000)
        assertTrue(waiting.showPicker)
        assertEquals(20, waiting.progress?.remainingSeconds)
        assertEquals(0f, waiting.progress!!.fraction, 0f)
        assertNull(session.evaluate(snapshot, 29_999).stream)
        assertEquals(backup, session.evaluate(snapshot, 30_000).stream)
    }

    @Test fun `fallback ignores approved source switches and regex restrictions`() {
        val session = TieredStreamAutoPlay(settings.copy(streamAutoPlaySource = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            streamAutoPlayAddonsEnabled = false, streamAutoPlayPluginsEnabled = false), addons)
        val backup = stream("Backup")
        val snapshot = StreamAutoPlaySnapshot(listOf(backup))
        session.evaluate(snapshot, 0)
        assertEquals(backup, session.evaluate(snapshot, 30_000).stream)
    }

    @Test fun `late approved results wait for the fallback deadline even when the search finishes`() {
        val session = TieredStreamAutoPlay(settings, addons)
        session.evaluate(StreamAutoPlaySnapshot(emptyList()), 0)
        session.evaluate(StreamAutoPlaySnapshot(emptyList()), 10_000)
        val late = stream("Approved", "2160p")
        val snapshot = StreamAutoPlaySnapshot(listOf(late), true)
        assertNull(session.evaluate(snapshot, 12_000).stream)
        assertNull(session.evaluate(snapshot, 29_999).stream)
        assertEquals(late, session.evaluate(snapshot, 30_000).stream)
    }

    @Test fun `a removed approved source is not selected from stale data`() {
        val session = TieredStreamAutoPlay(settings, addons)
        session.evaluate(StreamAutoPlaySnapshot(listOf(stream("Approved", "2160p"))), 1_000)
        assertNull(session.evaluate(StreamAutoPlaySnapshot(emptyList()), 10_000).stream)
    }

    @Test fun `disabling fallback never selects backup sources`() {
        val session = TieredStreamAutoPlay(settings.copy(streamAutoPlayAnySourceFallbackEnabled = false), addons)
        val snapshot = StreamAutoPlaySnapshot(listOf(stream("Backup")), true)
        assertNull(session.evaluate(snapshot, 0).stream)
        assertNull(session.evaluate(snapshot, 30_000).stream)
    }

    @Test fun `external error pages and unready torrents cannot autoplay in any tier`() {
        val unready = stream("AIOLists").copy(url = null, infoHash = "abc123", debridCacheStatus = StreamDebridCacheStatus(
            providerId = "test", providerName = "Test", state = StreamDebridCacheState.CHECKING))
        val external = stream("AIOLists").copy(url = null, externalUrl = "https://example.test/error")
        val session = TieredStreamAutoPlay(settings, addons)
        val snapshot = StreamAutoPlaySnapshot(listOf(unready, external), true)
        session.evaluate(snapshot, 0)
        assertNull(session.evaluate(snapshot, 500).stream)
        assertNull(session.evaluate(snapshot, 30_000).stream)
    }

    @Test fun `binge group preference cannot bypass approved addon restrictions`() {
        val backup = stream("Backup", "2160p").copy(behaviorHints = StreamBehaviorHints(null, "group", null, null))
        val approved = stream("Approved", "2160p")
        val session = TieredStreamAutoPlay(settings, addons, preferredBingeGroup = "group")
        val snapshot = StreamAutoPlaySnapshot(listOf(backup, approved))
        session.evaluate(snapshot, 0)
        assertEquals(approved, session.evaluate(snapshot, 10_000).stream)
    }

    @Test fun `fallback can wait for the first source when its deadline arrives before any results`() {
        val session = TieredStreamAutoPlay(settings, addons)
        session.evaluate(StreamAutoPlaySnapshot(emptyList()), 0)
        val waiting = session.evaluate(StreamAutoPlaySnapshot(emptyList()), 30_000)
        assertEquals(0, waiting.progress?.remainingSeconds)
        assertFalse(waiting.finished)
        val backup = stream("Backup")
        assertEquals(backup, session.evaluate(StreamAutoPlaySnapshot(listOf(backup)), 31_000).stream)
    }

    @Test fun `manual selection mode disables the new tiers`() {
        val manual = settings.copy(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)
        assertFalse(TieredStreamAutoPlay.isEnabled(manual))
        assertNull(TieredStreamAutoPlay(manual, addons).evaluate(StreamAutoPlaySnapshot(listOf(stream("AIOLists"))), 30_000).stream)
    }

    @Test fun `unlimited approved waiting cannot enable a later timed fallback`() {
        val unlimited = settings.copy(streamAutoPlayTimeoutSeconds = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED,
            streamAutoPlayImmediateEnabled = false)
        assertFalse(TieredStreamAutoPlay.isEnabled(unlimited))
        val snapshot = StreamAutoPlaySnapshot(listOf(stream("Backup")), true)
        assertNull(TieredStreamAutoPlay(unlimited, addons).evaluate(snapshot, 30_000).stream)
    }

    @Test fun `fallback timeout always remains greater than every finite approved timeout`() {
        PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES.filter { it != PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED }.forEach { approved ->
            listOf(Int.MIN_VALUE, 0, 10, 30, 45, 300, Int.MAX_VALUE).forEach { requested ->
                val fallback = PlayerSettings.normalizeAutoPlayFallbackSeconds(approved, requested)
                assertTrue(fallback > approved)
                assertTrue(fallback in PlayerSettings.STREAM_AUTOPLAY_FALLBACK_TIMEOUT_VALUES)
            }
        }
    }

    @Test fun `instant approved selection still starts the first matching source as soon as it arrives`() {
        val session = TieredStreamAutoPlay(settings.copy(streamAutoPlayTimeoutSeconds = 0), addons)
        session.evaluate(StreamAutoPlaySnapshot(listOf(stream("Backup"))), 0)
        val approved = stream("Approved", "2160p")
        assertEquals(approved, session.evaluate(StreamAutoPlaySnapshot(listOf(approved)), 1_000).stream)
    }

    @Test fun `an unconfigured regex does not become a match-all approved rule when fallback is enabled`() {
        val session = TieredStreamAutoPlay(settings.copy(streamAutoPlayRegex = ""), addons)
        val source = stream("Approved")
        val snapshot = StreamAutoPlaySnapshot(listOf(source))
        session.evaluate(snapshot, 0)
        assertNull(session.evaluate(snapshot, 10_000).stream)
        assertEquals(source, session.evaluate(snapshot, 30_000).stream)
    }

    @Test fun `a hung empty search eventually ends the fallback wait`() {
        val session = TieredStreamAutoPlay(settings, addons)
        val empty = StreamAutoPlaySnapshot(emptyList())
        session.evaluate(empty, 0)
        val stopped = session.evaluate(empty, 90_000)
        assertTrue(stopped.finished)
        assertTrue(stopped.showPicker)
        assertNull(stopped.stream)
        assertNull(stopped.progress)
    }

    @Test fun `a final choice cannot be replaced by later source emissions`() {
        val session = TieredStreamAutoPlay(settings, addons)
        val chosen = stream("AIOLists")
        val snapshot = StreamAutoPlaySnapshot(listOf(chosen))
        session.evaluate(snapshot, 0)
        assertEquals(chosen, session.evaluate(snapshot, 500).stream)
        assertEquals(chosen, session.evaluate(StreamAutoPlaySnapshot(listOf(stream("Backup"))), 40_000).stream)
    }

    @Test fun `coroutine waits exactly half a second while receiving the full latest addon batch`() = runTest {
        val first = stream("AIOLists", "720p")
        val better = stream("AIOLists", "1080p")
        val updates = MutableStateFlow(StreamAutoPlaySnapshot(listOf(first)))
        val pending = async { TieredStreamAutoPlay.awaitSelection(settings, updates, addons, nowMs = { testScheduler.currentTime }) }
        runCurrent()
        advanceTimeBy(200)
        updates.value = StreamAutoPlaySnapshot(listOf(better, first), true)
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(better, pending.await())
    }

    @Test fun `cancelling manual selection or navigation prevents a pending fallback and stops collecting`() = runTest {
        var collectorCancelled = false
        var progressCalls = 0
        val pending = async {
            TieredStreamAutoPlay.awaitSelection(settings, flow {
                try { emit(StreamAutoPlaySnapshot(listOf(stream("Backup")))); awaitCancellation() }
                finally { collectorCancelled = true }
            }, addons, nowMs = { testScheduler.currentTime }, onProgress = { progressCalls++ })
        }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        pending.cancelAndJoin()
        val callsAtCancel = progressCalls
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(collectorCancelled)
        assertEquals(callsAtCancel, progressCalls)
    }

    @Test fun `backgrounding cannot launch a source while waiting for the addon batch`() = runTest {
        var foreground = true
        val chosen = stream("AIOLists")
        val completeBatchFirst = stream("AIOLists", "first in completed batch")
        val updates = MutableStateFlow(StreamAutoPlaySnapshot(listOf(chosen)))
        val pending = async { TieredStreamAutoPlay.awaitSelection(settings,
            updates, addons,
            nowMs = { testScheduler.currentTime }, canSelect = { foreground }) }
        runCurrent()
        advanceTimeBy(250)
        foreground = false
        updates.value = StreamAutoPlaySnapshot(listOf(completeBatchFirst, chosen), true)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(pending.isCompleted)
        foreground = true
        advanceTimeBy(100)
        runCurrent()
        assertEquals(completeBatchFirst, pending.await())
    }
}
