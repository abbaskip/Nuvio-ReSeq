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
    @Inject lateinit var livePlayer: com.nuvio.tv.livetv.player.LiveTvPlaybackController
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
            "settings" -> setContent { NuvioTheme { com.nuvio.tv.ui.screens.settings.SettingsScreen() } }
            "vod" -> setContent {
                NuvioTheme { OnDemandHomeScreen({}, null, { _, _, _, _ -> }, { _, _, _, _ -> }, {}) }
            }
            "compare" -> compareImports()
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

    private fun compareImports() {
        val view = PlayerView(this).apply { useController = false }
        setContentView(view)
        lifecycleScope.launch {
            withTimeout(360_000) {
                val clock = { android.os.SystemClock.elapsedRealtime() }
                val pl = PlaylistSource("perf-large", "Comparison", "$HTTP/large.m3u", useEmbeddedEpg = false)
                val report = JSONObject()
                withContext(Dispatchers.IO) {
                    check(preferences.currentPlaylists().isEmpty())
                    preferences.updatePlaylists { listOf(pl) }
                    val start = clock()
                    live.refreshInternal(false, false, setOf(pl.id))
                    val channels = live.channels.value
                    check(channels.size == 2_000)
                    report.put("playlistMs", clock() - start).put("channels", channels.size)
                    report.put("channelKeyHash", channels.map { it.key to it.number }.hashCode())
                    val guide = com.nuvio.tv.livetv.model.EpgSource("perf-guide", "Comparison guide", "$HTTP/large.xml")
                    preferences.updateEpgSources { listOf(guide) }
                    val guideStart = clock()
                    live.refreshInternal(false, true)
                    val programs = preferences.currentEpgSources().single().programCount
                    check(programs > 0) { "Empty comparison guide" }
                    report.put("guideMs", clock() - guideStart).put("programs", programs)
                }
                val exo = livePlayer.attach()
                view.player = exo
                var underruns = 0
                var rebuffers = 0
                exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onAudioUnderrun(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) { underruns++ }
                })
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_BUFFERING) rebuffers++ }
                })
                livePlayer.play(live.channels.value.first(), overrideUrl = "$HTTP/continuous.ts")
                livePlayer.state.first { it.isPlaying }
                kotlinx.coroutines.delay(2_000)
                fun sample(): IntArray {
                    val c = checkNotNull(exo.videoDecoderCounters)
                    c.ensureUpdated()
                    return intArrayOf(c.renderedOutputBufferCount, c.droppedBufferCount, underruns, rebuffers)
                }
                suspend fun interval(name: String, work: (() -> Unit)? = null) {
                    val before = sample()
                    work?.invoke()
                    kotlinx.coroutines.delay(20_000)
                    val after = sample()
                    val fields = listOf("rendered", "dropped", "underruns", "rebuffers")
                    val counts = JSONObject()
                    fields.forEachIndexed { index, key -> counts.put(key, after[index] - before[index]) }
                    check(counts.getInt("rendered") > 0) { "Fixture video stopped rendering" }
                    report.put(name, counts)
                }
                interval("idle")
                // A changed playlist forces real decoding and a guide update while video is visible.
                val changed = pl.copy(url = "$HTTP/large-alt.m3u")
                withContext(Dispatchers.IO) { preferences.updatePlaylists { listOf(changed) } }
                lateinit var job: kotlinx.coroutines.Job
                interval("updating") {
                    job = lifecycleScope.launch(Dispatchers.IO) { live.refreshInternal(false, true, setOf(pl.id)) }
                }
                livePlayer.stop()
                view.player = null
                livePlayer.detach()
                job.join()
                check(live.channels.value.any { it.name == "Updated Channel 1" })
                if (intent.getBooleanExtra("testM3uVod", false)) withContext(Dispatchers.IO) {
                    val optIn = changed.copy(importVod = true)
                    preferences.updatePlaylists { listOf(optIn) }
                    val start = clock()
                    vod.refresh(force = true, onlyId = optIn.id)
                    check(vod.status.value.message == null) { "Plain M3U catalogue did not finish" }
                    val movies = vod.categories(VodKind.MOVIE).filter { it.playlistId == optIn.id }.sumOf { it.count }
                    check(movies == 98_000) { "Incomplete plain M3U catalogue: $movies" }
                    report.put("vodMovies", movies).put("vodImportMs", clock() - start)
                    val database = getDatabasePath("m3u-vod-links.db")
                    report.put("vodLinkBytes", listOf(database, File(database.path + "-wal")).sumOf { it.length() })
                    report.put("vodProcessHighWaterKiB", File("/proc/self/status").useLines { lines ->
                        lines.first { it.startsWith("VmHWM:") }.trim().split(Regex("\\s+"))[1].toLong()
                    })
                }
                android.util.Log.i("ReSeqPerfCheck", report.toString())
                setContent { Text("Comparison complete $report") }
            }
        }
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
