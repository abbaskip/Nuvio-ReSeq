package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.core.report.PlaybackReports
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvStartupResponsivenessTest {
    @Test fun reportWatchdogAndBackWorkWhilePlayerWorkerIsBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clip = java.io.File.createTempFile("mpv-startup-", ".mkv", instrumentation.targetContext.cacheDir)
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val backHandled = CountDownLatch(1)
        try {
            instrumentation.context.assets.open("mpv-startup-hevc-dts.mkv").use { input ->
                clip.outputStream().use(input::copyTo)
            }
            ActivityScenario.launch(TvHiltTestActivity::class.java).use { scenario ->
                lateinit var player: NuvioMpvSurfaceView
                try {
                    scenario.onActivity { activity ->
                        player = NuvioMpvSurfaceView(activity)
                        activity.setContentView(player)
                        player.setMedia(clip.path, emptyMap())
                        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                            override fun handleOnBackPressed() {
                                player.releasePlayer()
                                backHandled.countDown()
                                activity.finish()
                            }
                        })
                    }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                    var ready = false
                    while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity {
                            ready = player.durationMs() > 0 && player.isPositionFromRequestedMedia() &&
                                player.readPlayerMediaInfo()?.videoWidth == 3840 &&
                                player.readTrackSnapshot().audioTracks.any { track ->
                                    track.codec.equals("dts", ignoreCase = true) && track.isSelected
                                }
                        }
                        if (!ready) Thread.sleep(50)
                    }
                    if (!ready) scenario.onActivity {
                        fail("Missing MPV startup properties: duration=" + player.durationMs() +
                            " video=" + player.readPlayerMediaInfo() + " tracks=" + player.readTrackSnapshot())
                    }
                    scenario.onActivity {
                        player.mpv.submit {
                            started.countDown()
                            unblock.await(12, TimeUnit.SECONDS)
                            workerFinished.countDown()
                        }
                    }
                    assertTrue("The native command queue did not start", started.await(2, TimeUnit.SECONDS))
                    scenario.onActivity {
                        player.captureReport()
                        val fields = PlaybackReports.current(player)?.fields
                        assertEquals("3840x2160", fields?.get("coded size"))
                        assertEquals("dts", fields?.get("audio codec"))
                        player.isIdleActive()
                        player.isCoreIdleNow()
                        player.demuxerCacheDurationSec()
                        player.setPaused(true)
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    assertTrue("Back waited for the player", backHandled.await(2, TimeUnit.SECONDS))
                    assertEquals("Back waited for the blocked worker to finish", 1L, workerFinished.count)
                } finally {
                    unblock.countDown()
                    if (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) {
                        scenario.onActivity { player.releasePlayer() }
                    }
                }
            }
        } finally {
            unblock.countDown()
            clip.delete()
        }
    }
}
