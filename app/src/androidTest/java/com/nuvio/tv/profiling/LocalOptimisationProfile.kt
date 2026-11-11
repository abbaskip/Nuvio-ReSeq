package com.nuvio.tv.profiling

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.*
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.usenet.*
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.livetv.sports.*
import com.nuvio.tv.livetv.storage.StorageTestContext
import com.nuvio.tv.livetv.storage.SyntheticVideo
import com.nuvio.tv.ui.screens.player.NuvioMpvSurfaceView
import com.nuvio.tv.ui.screens.stream.prepareStreamEmission
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureNanoTime

/** Run explicitly on a blank emulator with -e localProfile true. All media and services are local. */
class LocalOptimisationProfile {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private val graph get() = EntryPointAccessors.fromApplication(app, IptvStorageTestEntryPoint::class.java)

    @Before fun emulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localProfile") == "true" &&
            (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
    }

    private fun report(value: String) = inst.sendStatus(0, Bundle().apply { putString("profile", value) })
    private fun timings(label: String, count: Int, work: () -> Unit) {
        val samples = List(count) { measureNanoTime(work) / 1000 }.sorted()
        report("$label us median=${samples[count/2]} p95=${samples[(count-1)*95/100]}")
    }

    @Test fun streamEmissionsAndWatchProgress() = runBlocking {
        val groups = List(12) { a -> AddonStreams("addon$a", null, List(60) { i ->
            Stream("1080p", "Fixture $i", null, "http://localhost/$a/$i", null, null, null,
                null, null, "addon$a", null)
        }) }
        val order = groups.reversed().map { it.addonName }
        val settings = PlayerSettings(streamAutoPlayAddonsEnabled = true)
        inst.runOnMainSync {
            timings("C3 ordering and autoplay filtering, 12 addons 720 streams", 60) {
                val ordered = StreamAutoPlaySelector.orderAddonStreams(groups, order)
                assertEquals(720, StreamAutoPlaySelector.automaticStreams(ordered.flatMap { it.streams },
                    settings, order.toSet()).size)
            }
        }
        val previous = groups.flatMap { it.streams }.mapIndexed { i, stream ->
            if (i % 2 == 0) stream.copy(badges = listOf(StreamBadge("fixture"))) else stream
        }
        withContext(Dispatchers.Main) {
            val wall = mutableListOf<Long>()
            val uiCpu = mutableListOf<Long>()
            repeat(60) {
                val cpu = Debug.threadCpuTimeNanos()
                val elapsed = measureNanoTime {
                    val result = withContext(Dispatchers.Default) {
                        prepareStreamEmission(groups, order, emptySet(), previous) { streams ->
                            StreamAutoPlaySelector.selectAutoPlayStream(streams, StreamAutoPlayMode.REGEX_MATCH, "Fixture 59",
                                StreamAutoPlaySource.ALL_SOURCES, order.toSet(), emptySet(), emptySet())
                        }
                    }
                    assertEquals(20, result.streams.take(20).size)
                }
                wall += elapsed/1000
                uiCpu += (Debug.threadCpuTimeNanos() - cpu)/1000
            }
            report("C3 720 streams badges + regex us wall median=" + wall.sorted()[30] +
                " main CPU median=" + uiCpu.sorted()[30] + " p95=" + uiCpu.sorted()[56])
        }
        val context = StorageTestContext(app)
        val factory = ProfileDataStoreFactory(context)
        val prefs = WatchProgressPreferences(factory, graph.profileManager())
        for (size in listOf(1000, 5000)) {
            val items = List(size) { i -> WatchProgress("fixture$i", "series", "show", null, null,
                null, "fixture$i:1:1", 1, 1, null, 500, 10000, i.toLong()) }
            prefs.saveProgressBatch(items, 1)
            val item = items.last()
            val observers = listOf(prefs.observeAllProgress(1), prefs.observeAllRawProgress(1),
                prefs.observeEpisodeRecords(item.contentId, 1), prefs.getAllEpisodeProgress(item.contentId, 1))
                .map { flow -> launch(Dispatchers.Default) { flow.collect {} } }
            try {
                prefs.observeEpisodeRecords(item.contentId, 1).first { it.isNotEmpty() }
                val samples = List(12) { i ->
                    measureNanoTime { prefs.saveProgress(item.copy(position = 600L + i), 1) } / 1000
                }.sorted()
                report("C6 save with $size records, 4 subscribers, us median=${samples[6]} p95=${samples[10]}")
            } finally { observers.forEach { it.cancelAndJoin() } }
        }
        factory.clearProfile(1)
    }

    @Test fun decodeAllocationsAndCacheReuse() {
        val payloadSize = 720 * 1024
        val wire = java.io.ByteArrayOutputStream().apply {
            write("=ybegin line=128 size=$payloadSize name=fixture\r\n".toByteArray())
            val line = ByteArray(128) { 43 } + byteArrayOf(13, 10)
            repeat(payloadSize/128) { write(line) }
            write("=yend size=$payloadSize\r\n.\r\n".toByteArray())
        }.toByteArray()
        val before = Debug.getRuntimeStats()
        val elapsed = measureNanoTime {
            repeat(1500) { assertEquals(payloadSize, decodeArticle(NewsWireReader(wire.inputStream())).bytes.size) }
        }
        val after = Debug.getRuntimeStats()
        fun diff(key: String) = (after[key]?.toLongOrNull() ?: 0) - (before[key]?.toLongOrNull() ?: 0)
        report("C8 1055 MiB yEnc ms=${elapsed/1000000} GC count=${diff("art.gc.gc-count")} allocated=${diff("art.gc.bytes-allocated")}")
        val used = AtomicInteger()
        val file = object : UsenetFile {
            override val name = "fixture.mp4"
            override val size = 2L * 1024 * 1024
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                minOf(length.toLong(), size-position).toInt().also { used.addAndGet(it) }
        }
        val context = StorageTestContext(app)
        val db = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(context.cacheDir, LeastRecentlyUsedCacheEvictor(10L shl 20), db)
        try {
            val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            fun play(url: String) {
                val source = factory.createDataSource()
                try {
                    source.open(DataSpec(android.net.Uri.parse(url)))
                    val buffer = ByteArray(65536)
                    while (source.read(buffer, 0, buffer.size) >= 0) { }
                } finally { source.close() }
            }
            repeat(2) { sessionNumber ->
                UsenetHttpSession(file, java.io.Closeable {}).use { session ->
                    session.start()
                    val beforeRead = used.get()
                    play(session.url)
                    val afterFirst = used.get()
                    play(session.url)
                    report("C11 session=$sessionNumber networkBytes=${afterFirst-beforeRead} replayBytes=${used.get()-afterFirst} diskBytes=${cache.cacheSpace}")
                    assertEquals(afterFirst, used.get())
                }
            }
        } finally { cache.release(); db.close() }
    }

    @Test fun playerRangeAndScoreRounds() {
        val media = SyntheticVideo.create(StorageTestContext(app).cacheDir)
        // Move the trailing movie index past a sparse padding atom. Chunk offsets still point to
        // the original media, while the demuxer now has a reason to request distant ranges.
        java.io.RandomAccessFile(media, "rw").use { out ->
            val originalEnd = out.length()
            var pos = 0L
            while (pos < originalEnd) {
                out.seek(pos)
                val shortSize = out.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(out::readFully).toString(Charsets.US_ASCII)
                val size = when (shortSize) { 1L -> out.readLong(); 0L -> originalEnd - pos; else -> shortSize }
                require(size >= 8 && size <= originalEnd - pos)
                if (shortSize == 0L) { out.seek(pos); out.writeInt(size.toInt()) }
                if (type == "moov") {
                    out.seek(pos)
                    val index = ByteArray(size.toInt()).also(out::readFully)
                    out.seek(pos + 4)
                    out.writeBytes("free")
                    val gap = 64 * 1024 * 1024
                    out.seek(originalEnd)
                    out.writeInt(gap)
                    out.writeBytes("free")
                    out.seek(originalEnd + gap)
                    out.write(index)
                }
                pos += size
            }
            check(out.length() > 64L * 1024 * 1024)
        }
        val urlPath = "/usenet/12345678-1234-1234-1234-123456789012/video.mp4"
        val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
        val activeRanges = AtomicInteger()
        val peakRanges = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val range = session.headers["range"]
                val mpvRequest = session.uri == urlPath
                if (mpvRequest) ranges.add(range ?: "full")
                val bounds = range?.let { UsenetHttpSession.parseRange(it, media.length()) } ?: (0L until media.length())
                val file = media.inputStream().apply { channel.position(bounds.first) }
                if (mpvRequest) {
                    val active = activeRanges.incrementAndGet()
                    peakRanges.updateAndGet { maxOf(it, active) }
                }
                val input = object : java.io.FilterInputStream(file) {
                    private val closed = java.util.concurrent.atomic.AtomicBoolean()
                    override fun close() {
                        if (mpvRequest && closed.compareAndSet(false, true)) activeRanges.decrementAndGet()
                        super.close()
                    }
                }
                return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                    "video/mp4", input, bounds.last-bounds.first+1).apply {
                    addHeader("Accept-Ranges", "bytes")
                    if (range != null) addHeader("Content-Range", "bytes ${bounds.first}-${bounds.last}/${media.length()}")
                }
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.listeningPort}$urlPath"
            val league = SportsCatalog.leagues.first()
            fun side(i: Int) = Side(TeamInfo(league.id,"$i","Team $i","Team $i","T$i",null,null,null,null),null,null,false,false)
            val fixtures = List(336) { i -> SportsEvent("$i",league.id,i*1800000L,true,GameState.PRE,
                "Team $i",null,null,side(i),side(i+1)) }
            ActivityScenario.launch(TvHiltTestActivity::class.java).use { activity ->
                lateinit var player: ExoPlayer
                val frames = java.util.concurrent.CountDownLatch(1)
                val dropped = AtomicInteger()
                activity.onActivity { host ->
                    val surface = SurfaceView(host)
                    host.setContentView(surface)
                    player = ExoPlayer.Builder(host).build()
                    player.addAnalyticsListener(object : AnalyticsListener {
                        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) { frames.countDown() }
                        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { dropped.addAndGet(droppedFrames) }
                    })
                    player.setVideoSurfaceView(surface)
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    player.setMediaItem(MediaItem.fromUri(url.substringBefore(urlPath) + "/preview.mp4"))
                    player.prepare()
                    player.play()
                }
                try {
                    assertTrue(frames.await(20, java.util.concurrent.TimeUnit.SECONDS))
                    timings("C4 indexed score round 336 fixtures with Media3 preview", 20) {
                        val index = SportsFixtureIndex(fixtures)
                        val identities = SportsEventIdentity.Round()
                        assertEquals(fixtures.size, fixtures.count { a ->
                            index.anyNear(a) { b -> identities.same(a,b,league,league) }
                        })
                    }
                } finally { activity.onActivity { player.release() } }
                report("C4 preview dropped frames=${dropped.get()}")
                ranges.clear()
                peakRanges.set(0)
                lateinit var mpv: NuvioMpvSurfaceView
                activity.onActivity { host ->
                    mpv = NuvioMpvSurfaceView(host)
                    host.setContentView(mpv)
                    mpv.setMedia(url, emptyMap())
                }
                try {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 15000
                    var duration = 0L
                    while (duration < 4000L && android.os.SystemClock.elapsedRealtime() < deadline) {
                        activity.onActivity { duration = mpv.durationMs() }
                        Thread.sleep(100)
                    }
                    assertTrue("mpv should read the distant index", duration >= 4000L)
                    activity.onActivity { mpv.seekToMs(4000) }
                    Thread.sleep(1500)
                    report("C10 mpv movieBytes=${media.length()} requests=${ranges.size} peak=${peakRanges.get()} ranges=${ranges.take(12)}")
                    assertTrue(ranges.isNotEmpty())
                } finally { activity.onActivity { mpv.releasePlayer() } }
            }
        } finally { server.stop() }
    }
}
