package com.nuvio.tv.profiling

import android.os.Build
import android.os.Bundle
import android.view.FrameMetrics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.IptvStorageTestEntryPoint
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.ui.LiveTvGuideScreen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class GuideScrollProfile {
    @get:Rule val compose = createAndroidComposeRule<TvHiltTestActivity>()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val graph get() = EntryPointAccessors.fromApplication(inst.targetContext, IptvStorageTestEntryPoint::class.java)
    private fun channelLabels() = compose.onAllNodes(hasText("Channel ", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
        .map { it.text }.filter { it.startsWith("Channel ") }

    @Test fun guideScroll() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localProfile") == "true" &&
            (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        val prefs = graph.livePreferences()
        check(prefs.currentPlaylists().isEmpty()) { "Guide profile requires a fresh emulator." }
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession) = newFixedLengthResponse(Response.Status.OK,"audio/x-mpegurl",
                "#EXTM3U\n" + (1..200).joinToString("\n") { "#EXTINF:-1 group-title=\"Fixture\",Channel $it\nhttp://127.0.0.1:$listeningPort/$it.ts" })
        }
        server.start()
        try {
            prefs.updatePlaylists { listOf(PlaylistSource("guide-profile", "Fixture", "http://127.0.0.1:${server.listeningPort}/list.m3u")) }
            val repo = graph.liveRepository()
            repo.refreshAll()
            withTimeout(30000) { repo.channels.first { it.size >= 200 } }
            compose.runOnUiThread {
                val model = androidx.lifecycle.ViewModelProvider(compose.activity)[com.nuvio.tv.livetv.ui.LiveTvViewModel::class.java]
                model.markFullscreenOpened()
            }
            compose.setContent { NuvioTheme { LiveTvGuideScreen({}, {}) } }
            compose.waitUntil(20000) { channelLabels().isNotEmpty() }
            val grid = compose.onNodeWithTag("live_tv_channel_grid")
            grid.performSemanticsAction(SemanticsActions.RequestFocus)
            grid.assertIsFocused()
            val frames = java.util.concurrent.CopyOnWriteArrayList<Long>()
            compose.runOnUiThread {
                compose.activity.window.addOnFrameMetricsAvailableListener({ _, f, _ ->
                    frames.add(f.getMetric(FrameMetrics.TOTAL_DURATION))
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            val first = channelLabels()
            repeat(55) {
                grid.performKeyInput { pressKey(Key.DirectionDown) }
                Thread.sleep(60)
            }
            val end = channelLabels()
            check(first != end && end.isNotEmpty()) { "Guide rows did not move" }
            val samples = frames.sorted()
            check(samples.isNotEmpty())
            inst.sendStatus(0, Bundle().apply {
                putString("profile", "C5 guide 200 channels 55 DPAD_DOWN from=" + first.first() + " to=" + end.first() +
                    " frames=" + samples.size + " medianMs=" + samples[samples.size/2]/1000000 +
                    " p95Ms=" + samples[samples.lastIndex*95/100]/1000000)
            })
        } finally { prefs.updatePlaylists { emptyList() }; server.stop() }
    }
}
