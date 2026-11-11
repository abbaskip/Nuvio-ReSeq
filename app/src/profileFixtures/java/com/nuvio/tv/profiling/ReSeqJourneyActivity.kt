package com.nuvio.tv.profiling

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.usenet.UsenetPlayback
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.UsenetSource
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.ondemand.OnDemandHomeScreen
import com.nuvio.tv.livetv.ondemand.OnDemandRepository
import com.nuvio.tv.livetv.ondemand.VodKind
import com.nuvio.tv.livetv.sports.SportsFollows
import com.nuvio.tv.livetv.sports.SportsLaunch
import com.nuvio.tv.livetv.sports.SportsScreen
import com.nuvio.tv.livetv.ui.LiveTvGuideScreen
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.inject.Inject

/** Shell-only entry points in the two benchmark targets. The shipping APK has neither this
 * component nor its fixtures. Setup happens before profile capture or timed launches. */
@AndroidEntryPoint
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReSeqJourneyActivity : ComponentActivity() {
    @Inject lateinit var preferences: LiveTvPreferences
    @Inject lateinit var live: LiveTvRepository
    @Inject lateinit var vod: OnDemandRepository
    private var player: ExoPlayer? = null
    private var sessionUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        when (intent.getStringExtra("journey")) {
            "seed" -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { withTimeout(60_000L) { seed() } }
                setContent { Text("Fixture ready") }
            }
            "guide" -> {
                ViewModelProvider(this)[LiveTvViewModel::class.java].markFullscreenOpened()
                setContent { NuvioTheme { LiveTvGuideScreen({}, {}) } }
            }
            "sports" -> setContent { NuvioTheme { SportsScreen({}) } }
            "event" -> {
                SportsLaunch.openEvent("espn:basketball/nba:77701")
                setContent { NuvioTheme { SportsScreen({}) } }
            }
            "vod" -> setContent {
                NuvioTheme { OnDemandHomeScreen(null, { _, _, _, _ -> }, { _, _, _, _ -> }, {}) }
            }
            "usenet" -> playLocalArticle()
            else -> error("Unknown profile journey")
        }
    }

    private suspend fun seed() {
        val source = PlaylistSource("reseq-profile", "Profile fixture", "$HTTP/get.php",
            xtreamServer = HTTP, xtreamUsername = "fixture", xtreamPassword = "fixture", importVod = true)
        check(preferences.currentPlaylists().all { it.id == source.id }) { "Use a clean benchmark emulator" }
        val snapshot = URL("$HTTP/sports").readText()
        val sportsDir = File(filesDir, "sports").apply { mkdirs() }
        File(sportsDir, "base-feed.json").writeText(snapshot)
        File(sportsDir, "base-feed-state.json").writeText(JSONObject()
            .put("url", BuildConfig.SPORTS_BASE_FEED_URL).put("checkedAt", System.currentTimeMillis()).toString())
        preferences.editSportsMine { SportsFollows(leagues = setOf("basketball/nba"), setUp = true).toJson() }
        preferences.updatePlaylists { listOf(source) }
        live.refreshAll()
        live.channels.first { it.size >= 60 }
        vod.refresh(force = true, onlyId = source.id)
        check(vod.categories(VodKind.MOVIE).any { it.name == "Fixture movies" })
    }

    private fun playLocalArticle() {
        val view = PlayerView(this).apply { useController = false }
        setContentView(view)
        lifecycleScope.launch {
            val source = UsenetSource("$HTTP/clip.nzb", listOf("nntp://127.0.0.1:38792"), emptyList())
            val stream = Stream("Profile", "Local clip", null, null, null, null, null, null, null,
                "Fixture", null, usenet = source)
            val url = checkNotNull(UsenetPlayback.resolve(stream).getStreamUrl())
            sessionUrl = url
            val exo = ExoPlayer.Builder(this@ReSeqJourneyActivity).build()
            player = exo
            view.player = exo
            exo.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    view.contentDescription = "Local article rendered"
                    reportFullyDrawn()
                }
            })
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            exo.play()
        }
    }

    override fun onDestroy() {
        player?.release()
        sessionUrl?.let(UsenetPlayback::release)
        super.onDestroy()
    }

    private companion object {
        const val HTTP = "http://127.0.0.1:38791"
    }
}
