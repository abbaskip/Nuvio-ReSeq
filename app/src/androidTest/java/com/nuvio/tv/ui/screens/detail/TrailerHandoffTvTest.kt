package com.nuvio.tv.ui.screens.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.core.player.BackgroundTrailerController
import com.nuvio.tv.core.player.BackgroundTrailerRequest
import com.nuvio.tv.core.player.LocalTrailerPlayerPool
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.livetv.ondemand.OnDemandPlayerEntryPoint
import com.nuvio.tv.livetv.storage.SyntheticVideo
import com.nuvio.tv.ui.components.BackgroundTrailerLayer
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TrailerHandoffTvTest {
    @get:Rule val compose = createAndroidComposeRule<TvHiltTestActivity>()

    @Test fun homeCanDisposeBeforeDetailsWithoutRestartingTheVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clip = SyntheticVideo.create(context.cacheDir)
        val url = android.net.Uri.fromFile(clip).toString()
        val graph = EntryPointAccessors.fromApplication(context, OnDemandPlayerEntryPoint::class.java)
        val pool = TrailerPlayerPool(context, graph.playerSettingsDataStore(), graph.trailerSettingsDataStore(),
            graph.liveTvPlaybackController(), graph.youTubeExtractor())
        val controller = BackgroundTrailerController()
        val transitions = AtomicInteger()
        try {
            compose.runOnIdle {
                pool.acquire()!!.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (mediaItem != null) transitions.incrementAndGet()
                    }
                })
                controller.update("home", BackgroundTrailerRequest(url, null, muted = true))
            }
            compose.setContent {
                NuvioTheme {
                    CompositionLocalProvider(LocalTrailerPlayerPool provides pool) {
                        BackgroundTrailerLayer(controller)
                    }
                }
            }
            compose.waitUntil(15000) {
                compose.runOnUiThread { controller.isRendering(url) && pool.acquire()!!.currentPosition >= 350 }
            }
            val position = compose.runOnIdle {
                val playing = pool.acquire()!!.currentPosition
                assertTrue(controller.beginTransfer("title", url, null, null))
                controller.release("home")
                assertNull(controller.pendingTransfer("different title"))
                assertEquals(url, controller.pendingTransfer("title")?.videoUrl)
                playing
            }
            compose.runOnIdle {
                controller.update("details", BackgroundTrailerRequest(url, null, muted = true))
                assertNull(controller.pendingTransfer("title"))
                assertTrue(controller.isRenderingFor("details"))
            }
            compose.waitUntil(5000) {
                compose.runOnUiThread { pool.acquire()!!.currentPosition >= position + 250 }
            }
            assertEquals(1, transitions.get())
            compose.runOnIdle { controller.release("details"); assertNull(controller.activeRequest) }
        } finally {
            compose.runOnUiThread { pool.release() }
            clip.delete()
        }
    }
}
