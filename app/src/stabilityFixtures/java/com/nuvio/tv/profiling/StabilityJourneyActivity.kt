package com.nuvio.tv.profiling

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.navigation.compose.rememberNavController
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.diagnostics.StabilityDiagnostics as Diag
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.sync.androidtv.TvChannelPreferences
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.usenet.UsenetPlayback
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nuvio.tv.data.tvdb.TvdbCredentialStore
import com.nuvio.tv.data.tvdb.TvdbOrderStore
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.ondemand.OnDemandHomeScreen
import com.nuvio.tv.livetv.ondemand.OnDemandRepository
import com.nuvio.tv.livetv.ondemand.VodKind
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.sports.*
import com.nuvio.tv.ui.screens.home.*
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import java.net.URL
import okio.source
import okio.buffer
import javax.inject.Inject

private const val HTTP = "http://127.0.0.1:38791"
private val SHOWS = listOf("tt9900001", "tt9900002", "tt9900003")

/** Compiled only with -PstabilityFixtures=true into the separate allowed test packages. */
@AndroidEntryPoint
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StabilityJourneyActivity : ComponentActivity() {
    @Inject lateinit var m1: MultiviewAcceptance
    @Inject lateinit var profiles: ProfileManager
    @Inject lateinit var prefs: LiveTvPreferences
    @Inject lateinit var live: LiveTvRepository
    @Inject lateinit var vod: OnDemandRepository
    @Inject lateinit var playback: LiveTvPlaybackController
    @Inject lateinit var guideDb: com.nuvio.tv.livetv.data.EpgDatabase
    @Inject lateinit var sports: SportsRepository
    @Inject lateinit var trailers: TrailerPlayerPool
    @Inject lateinit var progress: WatchProgressRepository
    @Inject lateinit var cache: ContinueWatchingEnrichmentCache
    @Inject lateinit var addonPrefs: AddonPreferences
    @Inject lateinit var addons: AddonRepository
    @Inject lateinit var shuffle: EpisodeShuffleStore
    @Inject lateinit var layout: LayoutPreferenceDataStore
    @Inject lateinit var orders: TvdbOrderStore
    @Inject lateinit var tvdb: TvdbCredentialStore
    @Inject lateinit var tvdbClient: com.nuvio.tv.data.tvdb.TvdbClient
    @Inject lateinit var auth: TraktAuthDataStore
    @Inject lateinit var tracking: TraktSettingsDataStore
    @Inject lateinit var scrobbles: com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
    @Inject lateinit var catalogue: com.nuvio.tv.domain.repository.MetaRepository
    @Inject lateinit var refresh: TrackingProgressRefreshCoordinator
    @Inject lateinit var channelSync: AndroidTvChannelSyncService
    @Inject lateinit var channelPrefs: TvChannelPreferences
    @Inject lateinit var rawProgress: WatchProgressPreferences
    private var unwatchDetails: com.nuvio.tv.ui.screens.detail.MetaDetailsViewModel? = null
    private var unwatchRetainedResume = true
    private lateinit var home: HomeViewModel
    private var route by mutableStateOf("waiting")
    private var trailerView: PlayerView? = null
    private var fakeSession: PlayerSessionTracker.Session? = null
    private val frames = ArrayList<Long>()
    private var recordFrames = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.STABILITY_FIXTURES && packageName in setOf(
            "io.github.abbaskip.nuvioreseq.debug", "io.github.abbaskip.nuvioreseq.iptvdebug"))
        Diag.enabled = true
        val manager = getSystemService(ActivityManager::class.java)
        Diag.event("environment", mapOf("package" to packageName, "memoryClassMb" to manager.memoryClass,
            "largeMemoryClassMb" to manager.largeMemoryClass, "lowRam" to manager.isLowRamDevice,
            "maxHeapBytes" to Runtime.getRuntime().maxMemory(), "fixture" to true))
        home = ViewModelProvider(this)[HomeViewModel::class.java]
        setContent {
            NuvioTheme {
                val nav = rememberNavController()
                Box {
                    key(route) {
                    when (route) {
                        "m1-screen" -> com.nuvio.tv.livetv.multiview.MultiviewScreen(onBack = { route = "home" })
                        "m1" -> m1.Content()
                        "home", "trailer" -> HomeScreen(home, { _, _, _ -> })
                        "sports", "cricket" -> SportsScreen({})
                        "guide" -> com.nuvio.tv.livetv.ui.LiveTvGuideScreen({}, {})
                        "preview" -> AndroidView(factory = { PlayerView(it).apply {
                            useController = false; player = playback.attach().apply { volume = 0f }
                        } })
                        "ondemand" -> OnDemandHomeScreen({}, null, { _, _, _, _ -> }, { _, _, _, _ -> }, {})
                        else -> Text("Fixture $route")
                    }
                    }
                    if (route == "trailer") AndroidView(factory = { PlayerView(it).apply {
                        useController = false
                        player = trailers.acquire()?.apply {
                            volume = 0f; repeatMode = Player.REPEAT_MODE_ALL
                            setMediaItem(MediaItem.fromUri("$HTTP/movie/fixture/fixture/1.mp4")); prepare(); play()
                        }
                        trailerView = this
                    } })
                    com.nuvio.tv.livetv.ui.LiveTvRootPlaybackEffects(playback, route)
                    SportsHost(nav)
                    com.nuvio.tv.livetv.ui.LiveTvLoadingHost(nav)
                }
            }
        }
        lifecycleScope.launch { while (isActive) { sample(); delay(30_000) } }
        window.addOnFrameMetricsAvailableListener({ _, metric, _ ->
            if (recordFrames && frames.size < 10_000) frames.add(metric.getMetric(android.view.FrameMetrics.TOTAL_DURATION))
        }, android.os.Handler(mainLooper))
        lifecycleScope.launch {
            var previous = emptySet<String>()
            com.nuvio.tv.livetv.model.LiveTvLoadReport.lines.collect { lines ->
                lines.filterNot { it in previous }.forEach { Diag.event("load-report", mapOf("line" to it)) }
                previous = lines.toSet()
            }
        }
        lifecycleScope.launch {
            combine(sports.events, sports.matches, sports.loading, sports.matching) { events, matches, loading, matching ->
                val current = events.filter { it.state == GameState.LIVE }.mapTo(HashSet()) { it.id }
                mapOf("events" to events.size, "matches" to matches.size,
                    "liveMatches" to matches.keys.count { it in current }, "loading" to loading,
                    "matching" to matching)
            }.collect { Diag.event("sports-progress", it) }
        }
        command(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); command(intent) }

    private fun command(intent: Intent) {
        val action = intent.getStringExtra("journey") ?: "home"
        lifecycleScope.launch {
            try {
                when (action) {
                    "stage2-contract" -> stage2Contract()
                    "stage2-setup" -> stage2Setup()
                    "stage2-load" -> stage2Load(false, intent.getBooleanExtra("withTrailer", false))
                    "stage2-second" -> stage2Load(true, false)
                    "stage2-hold" -> { fakeSession?.close(); fakeSession=PlayerSessionTracker.open() }
                    "stage2-idle" -> { fakeSession?.close(); fakeSession=null }
                    "stage2-storage" -> {
                        profiles.activeProfileReady.first { it }
                        // Open the real helper (including restart recovery), then restore the
                        // saved channel model. This action never fetches or changes tracker data.
                        val counts=withContext(Dispatchers.IO) {
                            val db=guideDb.readableDatabase
                            listOf("programs","stage","stage_meta","stage_channels","stage_info").associateWith { table ->
                                db.rawQuery("SELECT COUNT(*) FROM $table",null).use { it.moveToFirst();it.getLong(0) }
                            }
                        }
                        val restored=live.loadSavedChannels()
                        route="home"
                        Diag.event("stage2-storage",counts+mapOf("channels" to live.channels.value.size,"restored" to restored,
                            "guideBytes" to getDatabasePath("livetv_guide.db").length(),
                            "guideWalBytes" to java.io.File(getDatabasePath("livetv_guide.db").path+"-wal").length()))
                    }
                    "stage2-game" -> {
                        val requested = intent.getStringExtra("eventId")
                        val target = withTimeout(60_000) { sports.known.first { list ->
                            list.any { if (requested != null) it.id == requested else it.state == GameState.LIVE }
                        } }.first { if (requested != null) it.id == requested else it.state == GameState.LIVE }
                        Diag.event("stage2-game-target",mapOf("id" to target.id,"title" to target.title))
                        SportsLaunch.openEvent(target.id); route = "cricket"
                    }
                    "guide-preview" -> {
                        prefs.updateSettings { it.copy(updateGuideDuringPlayback=true) }
                        Diag.event("guide-fixture-policy",mapOf("updateDuringPlayback" to prefs.currentSettings().updateGuideDuringPlayback))
                        live.ensureLoaded()
                        val channels = withTimeout(60_000) { live.channels.first { it.isNotEmpty() } }
                        playback.attach().apply { volume=0f; repeatMode=Player.REPEAT_MODE_ALL }
                        playback.play(channels.first()); route="guide"
                    }
                    "stage2-dump" -> withContext(Dispatchers.IO) {
                        val saved = sports.matches.value
                        val file = java.io.File(filesDir,"stage2-matches.json")
                        file.bufferedWriter().use { writer ->
                            writer.write("[")
                            saved.toSortedMap().entries.forEachIndexed { index,(id,list) ->
                                if(index>0) writer.write(",")
                                writer.write("{\"id\":"+org.json.JSONObject.quote(id)+",\"matches\":[")
                                list.forEachIndexed { n,m ->
                                    if(n>0) writer.write(",")
                                    writer.write(org.json.JSONObject().put("channel",m.channel.key).put("score",m.score)
                                        .put("how",m.how).put("quality",m.quality ?: org.json.JSONObject.NULL)
                                        .put("catchup",m.catchup).put("start",m.program?.startMs ?: org.json.JSONObject.NULL)
                                        .put("stop",m.program?.stopMs ?: org.json.JSONObject.NULL)
                                        .put("title",m.program?.title ?: org.json.JSONObject.NULL).toString())
                                }
                                writer.write("]}")
                            }
                            writer.write("]")
                        }
                        val events = sports.events.value.map { it.id }.sorted()
                        fun fingerprint(ids: List<String>): String {
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            for (id in ids) {
                                val channels = org.json.JSONArray()
                                for (m in saved[id].orEmpty()) {
                                    channels.put(org.json.JSONArray().put(m.channel.key).put(m.score)
                                        .put(m.how).put(m.quality).put(m.catchup))
                                }
                                digest.update(org.json.JSONArray().put(id).put(channels).toString().toByteArray(Charsets.UTF_8))
                                digest.update(0.toByte())
                            }
                            return okio.ByteString.of(*digest.digest()).hex()
                        }
                        val providerEvents = events.filter { it.startsWith("espn:") }
                        Diag.event("stage2-dump", mapOf("fixtures" to saved.size, "matches" to saved.values.sumOf { it.size },
                            "maxPerFixture" to (saved.values.maxOfOrNull { it.size } ?: 0), "file" to file.name,
                            "events" to events.size, "providerEvents" to providerEvents.size,
                            "allChannelConfidence" to fingerprint(events), "providerChannelConfidence" to fingerprint(providerEvents)))
                    }
                    "frames-reset" -> { frames.clear(); recordFrames = true }
                    "frames-report" -> {
                        recordFrames = false
                        val sorted = frames.sorted()
                        check(sorted.isNotEmpty())
                        Diag.event("guide-frames", mapOf("frames" to sorted.size,
                            "p95Ms" to sorted[sorted.lastIndex * 95 / 100] / 1_000_000.0,
                            "over16ms" to sorted.count { it > 16_666_667 },
                            "guideBuilding" to BackgroundWork.guideBuilding.value))
                    }
                    "guide-update" -> { live.refreshEpgOnly(); route = "guide" }
                    "m1-screen-assert" -> m1.assertScreen(this@StabilityJourneyActivity)
                    "m1-shuffle" -> {
                        route = "home"
                        home.setHomeContentActive(true)
                        val row = withTimeout(60_000) { home.uiState.first { state ->
                            state.continueWatchingItems.any { item -> item.shufflePlayback && when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.contentId == SHOWS[0]
                                is ContinueWatchingItem.NextUp -> item.info.contentId == SHOWS[0]
                            } }
                        } }
                        check(row.continueWatchingItems.count { it.shufflePlayback } == 1)
                        Diag.event("m1-shuffle-passed")
                    }
                    "m1-three", "m1-three-progress", "m1-layout-cycle", "m1-device-info", "m1-multiview", "m1-background-assert", "m1-stop", "m1-rewatch-seed", "m1-rewatch-assert",
                    "m1-stale-pause", "m1-cw-launch", "m1-contract" -> {
                        home.setHomeContentActive(false)
                        route = "m1"
                        m1.command(action, this@StabilityJourneyActivity, home)
                    }
                    "office-split" -> officeSplit()
                    "tracker-unwatch-seed" -> seedTrackerUnwatch()
                    "tracker-unwatch-latest" -> removeTrackerEpisode()
                    "unwatch-seed", "unwatch-seed-history" -> {
                        unwatchRetainedResume = action == "unwatch-seed"
                        seedUnwatch()
                    }
                    "unwatch-latest" -> {
                        val details = checkNotNull(unwatchDetails)
                        home.setHomeContentActive(false)
                        route = "details"
                        val video = details.uiState.value.meta!!.videos.single { it.displayEpisode == 5 }
                        check(details.uiState.value.watchedEpisodes.contains(video.season to video.episode) ||
                            details.uiState.value.episodeProgressMap[video.season to video.episode]?.isCompleted() == true)
                        details.onEvent(com.nuvio.tv.ui.screens.detail.MetaDetailsEvent.OnToggleEpisodeWatched(video))
                        delay(5_000)
                        route = "home"
                        home.setHomeContentActive(true)
                        delay(30_000)
                        sampleUnwatch("after-unwatch")
                        val card = home.uiState.value.continueWatchingItems.single()
                        check(!card.chooseEpisode) { "Unwatch left a permanent series-choice card" }
                        if (unwatchRetainedResume) {
                            check(card is ContinueWatchingItem.InProgress && card.progress.episode == 1 &&
                                card.progress.position == 47_000L && card.progress.tvdbContext?.orderType == "dvd") {
                                "Retained resume did not resolve through the selected TVDB order"
                            }
                        } else {
                            check(card is ContinueWatchingItem.NextUp && card.info.episode == 5) {
                                "Remaining E4 history must offer unwatched E5"
                            }
                        }
                        check(progress.observeNextUpSeeds().first().single().episode == 4) {
                            "Remaining E4 completion was lost from the seed flow"
                        }
                        Diag.event("unwatch-assertions-passed", mapOf("retainedResume" to unwatchRetainedResume))
                    }
                    "seed" -> { withContext(Dispatchers.IO) { withTimeout(240_000) { seed() } }; route = "ready" }
                    "sample" -> sample()
                    "refresh" -> {
                        val failures = refresh.refreshConnected(TrackingRefreshIntent.INVALIDATED)
                        Diag.event("tracker-refresh", mapOf("failures" to failures.size)); sample()
                    }
                    "complete" -> {
                        fakeSession = PlayerSessionTracker.open()
                        home.setHomeContentActive(false)
                        route = "details"
                        withContext(Dispatchers.IO) { URL("$HTTP/control?completed=true").readText() }
                        for (id in SHOWS) progress.saveProgress(seedProgress(id).copy(position = 100_000), syncRemote = false)
                        fakeSession?.close(); fakeSession = null
                        val failures = refresh.refreshConnected(TrackingRefreshIntent.INVALIDATED)
                        Diag.event("closed-on-non-home", mapOf("failures" to failures.size)); sample()
                    }
                    "background" -> { channelSync.onForegroundChanged(false); moveTaskToBack(true) }
                    "home-with-live" -> { route = "home"; sample() }
                    "stream-list" -> { route = "stream/fixture" }
                    "tvdb-missing", "tvdb-invalid", "tvdb-timeout", "tvdb-unavailable", "tvdb-available" -> {
                        val mode = action.removePrefix("tvdb-")
                        tvdb.save(1, if (mode == "missing") null else "synthetic-$mode-key")
                        val start = android.os.SystemClock.elapsedRealtime()
                        val result = runCatching { withTimeout(24_000) { tvdbClient.episodes(1, 9901, "dvd").size } }
                        Diag.event("tvdb-probe", mapOf("mode" to mode, "episodes" to result.getOrNull(),
                            "error" to result.exceptionOrNull()?.javaClass?.simpleName,
                            "durationMs" to android.os.SystemClock.elapsedRealtime()-start))
                        sample()
                    }
                    "import-reminder" -> {
                        sports.updateSettings { it.copy(remindMinutes = 0) }
                        val due = System.currentTimeMillis() + 30_000
                        sports.updateFollows { it.copy(reminders = it.reminders + SportsReminder("fixture-reminder", "Fixture reminder", due, false)) }
                        Diag.event("reminder-scheduled", mapOf("dueMs" to due))
                        prefs.setOnDemandImportTime("stability", 0L)
                        lifecycleScope.launch(BackgroundWork.quiet) { vod.refresh(force = false, onlyId = "stability") }
                        lifecycleScope.launch { delay(10_000); live.refreshAll() }
                    }
                    "nntp-stall" -> lifecycleScope.launch(Dispatchers.IO) {
                        val source = UsenetSource("$HTTP/stall.nzb", listOf("nntp://127.0.0.1:38792"), emptyList())
                        val stream = Stream("Fixture", "Stalled article", null, null, null, null, null, null,
                            null, "Fixture", null, usenet = source)
                        Diag.event("nntp-start")
                        runCatching {
                            val url = checkNotNull(UsenetPlayback.resolve(stream).getStreamUrl())
                            Diag.event("nntp-resolved")
                            try {
                                withContext(Dispatchers.Main) {
                                    route = "nntp"
                                    trailers.acquire()?.apply {
                                        volume = 0f; setMediaItem(MediaItem.fromUri(url)); prepare(); play()
                                    }
                                }
                                repeat(60) {
                                    withContext(Dispatchers.Main) {
                                        trailers.acquire()?.let { player -> Diag.event("nntp-player", mapOf(
                                            "state" to player.playbackState, "positionMs" to player.currentPosition,
                                            "playing" to player.isPlaying)) }
                                    }
                                    delay(1000)
                                }
                            } finally {
                                withContext(NonCancellable + Dispatchers.Main) { trailers.acquire()?.stop() }
                                UsenetPlayback.release(url)
                            }
                        }
                            .onFailure { Diag.event("nntp-result", mapOf("error" to it.javaClass.simpleName)) }
                    }
                    else -> {
                        if (action != "trailer") trailers.acquire()?.stop()
                        if (action != "preview") playback.stop()
                        if (action == "cricket") {
                            val target = withTimeout(30_000) { sports.events.first { events ->
                                events.any { it.id == "espn:cricket/19430:77702" }
                            } }.first { it.id == "espn:cricket/19430:77702" }
                            Diag.event("fixture-cricket-target", mapOf("event" to target.id, "state" to target.state.name))
                            SportsLaunch.openEvent(target.id)
                        }
                        if (action == "preview") {
                            // This activity bypasses LiveTvViewModel, including after the forced
                            // cold restart. Mirror its cache initialization before requesting playback.
                            live.ensureLoaded()
                            val channels = checkNotNull(withTimeoutOrNull(30_000) {
                                live.channels.first { it.isNotEmpty() }
                            }) { "Fixture Live TV channels did not load after initialization" }
                            Diag.event("preview-source-ready", mapOf("channels" to channels.size))
                            playback.attach().volume = 0f
                            playback.play(channels.first())
                        }
                        route = action
                    }
                }
                Diag.event("journey-ready", mapOf("journey" to action, "route" to route))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Diag.event("journey-failed", mapOf("journey" to action, "error" to error.stackTraceToString().take(3000)))
            }
        }
    }

    private suspend fun stage2Contract() = withContext(Dispatchers.Default) {
        val at=1_794_866_820_000L
        val started=android.os.SystemClock.elapsedRealtime()
        val parsed=ArrayList<SportsEvent>()
        URL("$HTTP/sports").openStream().source().buffer().use { input ->
            SportsBaseFeed.parse(input,at) { entry ->
                val league=SportsCatalog.league(entry.leagueId)
                if(league!=null) {
                    val events=when {
                        entry.provider=="espn" && league.espnPath!=null -> SportsFeeds.parseScoreboard(league,entry.payload)
                        entry.provider=="tsdb" && league.id.startsWith("tsdb/") -> FreeSportsDb.events(league,entry.payload,entry.fetchedAt)
                        entry.provider=="calendar" && league.fixtureSlug!=null -> SportsFeeds.parseFixtures(league,entry.payload)
                        else -> emptyList()
                    }
                    parsed.addAll(events.mapNotNull { SportsPageSnapshot.display(SportsBaseFeed.scheduled(it,entry.fetchedAt),entry.fetchedAt,at) })
                }
            }
        }
        Diag.event("stage2-contract-feed",mapOf("events" to parsed.size,"ms" to android.os.SystemClock.elapsedRealtime()-started))
        val channels=live.channels.first { it.isNotEmpty() }
        val settings=sports.settings.value
        // Match the recorded reference input, independent of preview quality observations.
        val quality=mapOf("stage2-one|channel1|Sports Network 001 HD" to "SD")
        val shift=prefs.settings.first().epgOffsetMinutes*60_000L
        val matcher=SportsMatcher(guideDb)
        val guide=ArrayList<SportsEvent>()
        val calendar=java.util.Calendar.getInstance().apply { timeInMillis=at; set(java.util.Calendar.HOUR_OF_DAY,0);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0) }
        val day=calendar.timeInMillis
        for(offset in listOf(0,1,-1)) {
            val begin=android.os.SystemClock.elapsedRealtime()
            val found=matcher.guideEvents(day+offset*86_400_000L,day+(offset+1)*86_400_000L,SportsCatalog.all,
                emptyList(),channels.associateBy { it.key },shift,at)
            guide.addAll(found)
            Diag.event("stage2-contract-discovery",mapOf("day" to offset,"events" to found.size,"ms" to android.os.SystemClock.elapsedRealtime()-begin))
        }
        val begin=android.os.SystemClock.elapsedRealtime()
        val result=matcher.match(SportsMatcher.Input((parsed+guide).distinctBy { it.id },channels,quality,shift,settings,at))
        val elapsed=android.os.SystemClock.elapsedRealtime()-begin
        val file=java.io.File(filesDir,"stage2-contract.json")
        file.bufferedWriter().use { writer ->
            writer.write("[")
            result.matches.toSortedMap().entries.forEachIndexed { index,(id,list) ->
                if(index>0) writer.write(",")
                writer.write("{\"id\":"+org.json.JSONObject.quote(id)+",\"matches\":[")
                list.forEachIndexed { n,m ->
                    if(n>0) writer.write(",")
                    writer.write(org.json.JSONObject().put("channel",m.channel.key).put("score",m.score)
                        .put("how",m.how).put("quality",m.quality ?: org.json.JSONObject.NULL)
                        .put("catchup",m.catchup).put("start",m.program?.startMs ?: org.json.JSONObject.NULL)
                        .put("stop",m.program?.stopMs ?: org.json.JSONObject.NULL)
                        .put("title",m.program?.title ?: org.json.JSONObject.NULL).toString())
                }
                writer.write("]}")
            }
            writer.write("]")
        }
        Diag.event("stage2-contract-complete",mapOf("fixtures" to result.matches.size,"matches" to result.matches.values.sumOf { it.size },
            "qualityInput" to quality,"fixedAt" to at,"matchingMs" to elapsed,"totalMs" to android.os.SystemClock.elapsedRealtime()-started,"file" to file.name,"summary" to result.summary))
    }

    private suspend fun stage2Setup() {
        profiles.activeProfileReady.first { it }
        addonPrefs.removeAddon("https://v3-cinemeta.strem.io")
        addonPrefs.removeAddon("https://opensubtitles-v3.strem.io")
        prefs.editSportsMine { SportsFollows(leagues = setOf("cricket/19430"), setUp = true).toJson() }
        sports.updateSettings { it.copy(onlyOnMyChannels = true) }
    }

    private suspend fun stage2Load(second: Boolean, withTrailer: Boolean) {
        if (!second) stage2Setup() else profiles.activeProfileReady.first { it }
        java.io.File(filesDir,"livetv").mkdirs()
        if (!withTrailer) trailerView?.player?.stop()
        home.setHomeContentActive(withTrailer)
        route = if (withTrailer) "trailer" else "waiting"
        if(withTrailer) withTimeout(30_000) { while(trailerView?.player?.isPlaying!=true) delay(100) }
        val id = if (second) "stage2-two" else "stage2-one"
        val source = PlaylistSource(id, "Stage 2 fixture", "$HTTP/get.php",
            xtreamServer = HTTP, xtreamUsername = if (second) "fixture2" else "fixture",
            xtreamPassword = "fixture", importVod = true)
        Diag.event("stage2-start", mapOf("second" to second, "trailer" to withTrailer))
        prefs.updatePlaylists { prior -> if (second) prior.filterNot { it.id == id } + source else listOf(source) }
        live.refreshAll()
        val expected = if (second) 100_002 else 50_001
        withTimeout(10 * 60_000) { live.channels.first { it.size >= expected } }
        Diag.event("stage2-channels", mapOf("channels" to live.channels.value.size,
            "trailerActive" to PlayerSessionTracker.hasOpenTrailer, "second" to second))
        withContext(Dispatchers.IO) { vod.refresh(force = true, onlyId = id) }
        Diag.event("stage2-imported", mapOf("second" to second,
            "trailerActive" to PlayerSessionTracker.hasOpenTrailer))
    }

    private suspend fun seed() {
        profiles.activeProfileReady.first { it }
        check(prefs.currentPlaylists().all { it.id == "stability" })
        addonPrefs.removeAddon("https://v3-cinemeta.strem.io")
        addonPrefs.removeAddon("https://opensubtitles-v3.strem.io")
        addons.addAddon("$HTTP/addon/manifest.json")
        check(addons.fetchAddon("$HTTP/addon") is com.nuvio.tv.core.network.NetworkResult.Success) { "Fixture addon manifest could not be parsed" }
        withTimeout(30_000) { addons.awaitInstalledAddons(setOf("stability.fixture")) }
        layout.setRandomEpisodeEnabled(true)
        shuffle.save(SHOWS[0], EpisodeShuffleSettings(enabled = true, includeWatched = true), 1)
        orders.save(1, 9901, "dvd", listOf(SHOWS[1]))
        tvdb.save(1, "synthetic-fixture-key")
        for (id in SHOWS) progress.saveProgress(seedProgress(id), syncRemote = false)
        auth.saveToken(TraktTokenResponseDto("fixture-token", "bearer", 86400, "fixture-refresh", createdAt = System.currentTimeMillis()/1000))
        auth.saveUser("fixture", "fixture")
        tracking.setWatchProgressSource(WatchProgressSource.TRAKT)
        val failures = refresh.refreshConnected(TrackingRefreshIntent.INVALIDATED)
        check(failures.isEmpty()) { "Fake tracker refresh failed: ${failures.map { it.cause.javaClass.simpleName }}" }
        prefs.editSportsMine { SportsFollows(leagues = setOf("cricket/19430"), setUp = true).toJson() }
        prefs.updatePlaylists { listOf(PlaylistSource("stability", "Stability fixtures", "$HTTP/get.php",
            xtreamServer = HTTP, xtreamUsername = "fixture", xtreamPassword = "fixture", importVod = true)) }
        live.refreshAll()
        live.channels.first { it.size >= 2000 }
        vod.refresh(force = true, onlyId = "stability")
        val movies = vod.categories(VodKind.MOVIE).filter { it.name == "Fixture movies" }.sumOf { it.count }
        check(movies == 20_000) { "Expected 20000 fixture movies, imported $movies" }
        Diag.event("seed-complete", mapOf("channels" to live.channels.value.size, "movies" to movies))
    }

    private suspend fun officeSplit() {
        val id = "tt0386676"
        profiles.activeProfileReady.first { it }
        addonPrefs.removeAddon("https://v3-cinemeta.strem.io")
        addonPrefs.removeAddon("https://opensubtitles-v3.strem.io")
        addons.addAddon("$HTTP/addon/manifest.json")
        withTimeout(30_000) { addons.awaitInstalledAddons(setOf("stability.fixture")) }
        orders.save(1, 73244, "alttwo", listOf(id))
        tvdb.save(1, "synthetic-fixture-key")
        auth.saveToken(TraktTokenResponseDto("fixture-token", "bearer", 86400, "fixture-refresh",
            createdAt = System.currentTimeMillis() / 1000))
        auth.saveUser("fixture", "fixture")
        tracking.setWatchProgressSource(WatchProgressSource.TRAKT)
        val result = catalogue.getMetaFromAllAddons("series", id).first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
        val video = (result as com.nuvio.tv.core.network.NetworkResult.Success).data.videos.single {
            it.episodeIdentity?.tvdbEpisodeId == 10719652L
        }
        val reference = com.nuvio.tv.core.tracking.buildTrackingMediaReference("series", id, video.id, "The Office",
            seasonNumber = video.season, episodeNumber = video.episode, episodeTitle = video.title)
        val media = reference.copy(catalog = reference.catalog!!.copy(tvdbContext = video.tvdbPlaybackContext(), tvdbCoverageVerified = true))
        for ((action, at) in listOf(com.nuvio.tv.core.tracking.TrackingScrobbleAction.START to 0.0,
            com.nuvio.tv.core.tracking.TrackingScrobbleAction.PAUSE to 47.0,
            com.nuvio.tv.core.tracking.TrackingScrobbleAction.START to 47.0,
            com.nuvio.tv.core.tracking.TrackingScrobbleAction.STOP to 94.3592)) {
            check(scrobbles.scrobble(action, com.nuvio.tv.core.tracking.TrackingScrobbleEvent(media, at)).isEmpty())
        }
        check(refresh.refreshConnected(TrackingRefreshIntent.INVALIDATED).isEmpty())
        home.setHomeContentActive(true)
        route = "home"
        withTimeout(90_000) {
            home.uiState.first { state -> state.continueWatchingItems.filterIsInstance<ContinueWatchingItem.InProgress>()
                .any { it.progress.contentId == id && !it.chooseEpisode && it.progress.tvdbContext?.episodeId == 10719652L &&
                    kotlin.math.abs(it.progress.progressPercentage * 100 - 94.3592f) < 0.01f } }
        }
    }

    private suspend fun seedTrackerUnwatch() {
        profiles.activeProfileReady.first { it }
        addonPrefs.removeAddon("https://v3-cinemeta.strem.io")
        addonPrefs.removeAddon("https://opensubtitles-v3.strem.io")
        addons.addAddon("$HTTP/addon/manifest.json")
        withTimeout(30_000) { addons.awaitInstalledAddons(setOf("stability.fixture")) }
        orders.save(1, 9901, "dvd", listOf(SHOWS[1]))
        tvdb.save(1, "synthetic-fixture-key")
        layout.setNextUpFromFurthestEpisode(false)
        auth.saveToken(TraktTokenResponseDto("fixture-token", "bearer", 86400, "fixture-refresh",
            createdAt = System.currentTimeMillis() / 1000))
        auth.saveUser("fixture", "fixture")
        tracking.setWatchProgressSource(WatchProgressSource.TRAKT)
        check(refresh.refreshConnected(TrackingRefreshIntent.INVALIDATED).isEmpty())
        val details = ViewModelProvider(this)[com.nuvio.tv.ui.screens.detail.MetaDetailsViewModel::class.java]
        unwatchDetails = details
        val state = withTimeout(60_000) { details.uiState.first { state ->
            val video = state.meta?.videos?.singleOrNull { it.displayEpisode == 5 }
            video != null && video.episodeIdentity?.trackingEpisodeId == 990103L &&
                state.episodeProgressMap[video.season to video.episode]?.isCompleted() == true
        } }
        check(state.meta!!.videos.single { it.displayEpisode == 4 }.episodeIdentity?.trackingEpisodeId == 990104L)
        route = "home"
        home.setHomeContentActive(true)
        awaitTrackerCard(6)
        sampleUnwatch("tracker-before")
    }

    private suspend fun awaitTrackerCard(episode: Int) {
        withTimeout(90_000) { home.uiState.first { state -> state.continueWatchingItems.any {
            it is ContinueWatchingItem.NextUp && it.info.contentId == SHOWS[1] && !it.chooseEpisode &&
                it.info.tvdbContext?.episode == episode
        } } }
    }

    private suspend fun removeTrackerEpisode() {
        val details = checkNotNull(unwatchDetails)
        val video = details.uiState.value.meta!!.videos.single { it.displayEpisode == 5 }
        home.setHomeContentActive(false)
        route = "details"
        details.onEvent(com.nuvio.tv.ui.screens.detail.MetaDetailsEvent.OnToggleEpisodeWatched(video))
        delay(5_000)
        route = "home"
        home.setHomeContentActive(true)
        val remaining = progress.getAllEpisodeProgress(SHOWS[1]).first().values.filter { it.isCompleted() }
        Diag.event("tracker-remaining", mapOf("episodes" to remaining.map { it.tvdbContext?.episode }))
        try {
            awaitTrackerCard(5)
        } finally {
            sampleUnwatch("tracker-after")
        }
        check(progress.getAllEpisodeProgress(SHOWS[1]).first().values.filter { it.isCompleted() }
            .map { it.tvdbContext?.episode }.toSet() == setOf(4, 7))
        // The retained 47% pause falls between the watch times of DVD E7 and E4.
        // It must lose even when the furthest episode becomes the preferred seed.
        layout.setNextUpFromFurthestEpisode(true)
        awaitTrackerCard(8)
        sampleUnwatch("tracker-after-furthest")
        Diag.event("unwatch-assertions-passed", mapOf("tracker" to true, "furthest" to true))
    }

    private suspend fun seedUnwatch() {
        profiles.activeProfileReady.first { it }
        addonPrefs.removeAddon("https://v3-cinemeta.strem.io")
        addonPrefs.removeAddon("https://opensubtitles-v3.strem.io")
        addons.addAddon("$HTTP/addon/manifest.json")
        withTimeout(30_000) { addons.awaitInstalledAddons(setOf("stability.fixture")) }
        tracking.setWatchProgressSource(WatchProgressSource.NUVIO_SYNC)
        orders.save(1, 9901, "dvd", listOf(SHOWS[1]))
        tvdb.save(1, "synthetic-fixture-key")
        val details = ViewModelProvider(this)[com.nuvio.tv.ui.screens.detail.MetaDetailsViewModel::class.java]
        unwatchDetails = details
        val state = withTimeout(60_000) { details.uiState.first { it.meta?.episodeOrder != null && it.meta.videos.size >= 6 } }
        val videos = state.meta!!.videos
        for (episode in listOf(4, 5)) {
            val video = videos.single { it.displayEpisode == episode }
            val context = checkNotNull(video.tvdbPlaybackContext())
            progress.markAsCompleted(seedProgress(SHOWS[1]).copy(videoId = video.id,
                season = video.season, episode = video.episode, episodeTitle = video.title,
                tvdbEpisodeId = video.episodeIdentity!!.trackingEpisodeId,
                tvdbEpisodeIds = context.episodeIds, tvdbContext = context))
            if (episode == 4 && unwatchRetainedResume) {
                delay(100)
                // Retained provider-default resume predates the latest completion, like the TV evidence.
                rawProgress.saveProgress(seedProgress(SHOWS[1]).copy(position = 47_000,
                    tvdbEpisodeId = 990101, tvdbEpisodeIds = listOf(990101)), profileId = 1)
                delay(100)
            }
        }
        route = "home"
        home.setHomeContentActive(true)
        delay(30_000)
        sampleUnwatch("before-unwatch")
        val before = home.uiState.value.continueWatchingItems.single()
        check(before is ContinueWatchingItem.NextUp && before.info.episode == 6 && !before.chooseEpisode) {
            "Precondition: completed E5 must offer E6 before unwatch"
        }
    }

    private suspend fun sampleUnwatch(phase: String) {
        fun ContinueWatchingItem.description(): String = when (this) {
            is ContinueWatchingItem.InProgress -> "resume:${progress.episode}:choose=$chooseEpisode:context=${progress.tvdbContext}:at=${progress.lastWatched}"
            is ContinueWatchingItem.NextUp -> "next:${info.episode}:choose=$chooseEpisode:context=${info.tvdbContext}:at=${info.lastWatched}"
        }
        Diag.event("unwatch-flow", mapOf("phase" to phase,
            "cards" to home.uiState.value.continueWatchingItems.map { it.description() },
            "raw" to progress.allProgress.first().map { "${it.episode}:${it.isCompleted()}:${it.lastWatched}:${it.tvdbContext}" },
            "seeds" to progress.observeNextUpSeeds().first().map { "${it.episode}:${it.lastWatched}:${it.tvdbContext}" },
            "snapshot" to cache.getSnapshot().toString()))
    }

    private fun seedProgress(id: String) = WatchProgress(id, "series", "Fixture $id", null, null, null,
        "$id:1:1", 1, 1, "Episode 1", 40_000, 100_000, System.currentTimeMillis())

    private suspend fun sample() {
        val rt = Runtime.getRuntime()
        val stacks = Thread.getAllStackTraces()
        val cards = home.uiState.value.continueWatchingItems + home.uiState.value.upcomingItems
        val ids = cards.map { when (it) {
            is ContinueWatchingItem.InProgress -> it.progress.contentId
            is ContinueWatchingItem.NextUp -> it.info.contentId
        } }
        val projection = cache.getSnapshot()
        val snapshots = projection.inProgress.map { "${it.contentId}:${it.episode}" } +
            projection.nextUp.map { "${it.contentId}:${if (it.chooseEpisode) 0 else it.episode}" }
        val launcher = mutableListOf<String>()
        val channelId = channelPrefs.getChannelId()
        if (channelId != null) withContext(Dispatchers.IO) {
            // TvProvider auto-scopes this URI to our calling package. Also filter our stored channel ID.
            contentResolver.query(TvContractCompat.PreviewPrograms.CONTENT_URI, arrayOf("channel_id", "internal_provider_id", "intent_uri"), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) if (cursor.getLong(0) == channelId) launcher += "${cursor.getString(1)}|${cursor.getString(2)}"
            }
        }
        val watchNext = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf("internal_provider_id", "intent_uri"), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) watchNext += "${cursor.getString(0)}|${cursor.getString(1)}"
            }
        }
        Diag.event("sample", mapOf("route" to route, "heapUsedBytes" to rt.totalMemory()-rt.freeMemory(),
            "nativeBytes" to Debug.getNativeHeapAllocatedSize(),
            "maxMemory" to rt.maxMemory(),
            "memoryClass" to getSystemService(android.app.ActivityManager::class.java).memoryClass,
            "largeMemoryClass" to getSystemService(android.app.ActivityManager::class.java).largeMemoryClass,
            "trailerPlaying" to (trailerView?.player?.isPlaying == true),
            "liveTvAttached" to (playback.player != null), "liveTvPlaying" to (playback.player?.isPlaying == true), "threadCount" to stacks.size,
            "dispatcherBlocked" to stacks.count { (thread, stack) -> thread.name.startsWith("DefaultDispatcher-worker") &&
                stack.any { it.className.contains("usenet", true) || it.className.contains("CompletableFuture") || it.methodName.contains("socketRead", true) } },
            "gc" to Debug.getRuntimeStats(), "cwIds" to ids, "snapshot" to snapshots,
            "snapshotVersion" to projection.revision, "launcher" to launcher, "watchNext" to watchNext,
            "guideBuilding" to BackgroundWork.guideBuilding.value, "heavyJobs" to BackgroundWork.heavyJobs.value,
            "guideWindowChannels" to live.programs.value.size,
            "guideWindowPrograms" to live.programs.value.values.sumOf { it.size },
            "rawProgress" to progress.allProgress.first().map { "${it.contentId}:${it.episode}:${it.isCompleted()}" },
            "sportsEvents" to sports.events.value.size))
        stacks.filter { (thread, _) -> thread.name.startsWith("DefaultDispatcher-worker") || thread.name.startsWith("nuvio-quiet") }
            .filter { (thread, stack) -> stack.any { it.className.contains("usenet", true) || it.className.contains("CompletableFuture") ||
                it.methodName.contains("socketRead", true) || (thread.name.startsWith("nuvio-quiet") && it.className.contains("BlockingCoroutine")) } }
            .forEach { (thread, stack) -> Diag.event("blocked-thread", mapOf("thread" to thread.name,
                "state" to thread.state.name, "stack" to stack.take(12).joinToString("\n"))) }
    }

    override fun onStart() { super.onStart(); if (::channelSync.isInitialized) channelSync.onForegroundChanged(true) }
    override fun onStop() { if (::channelSync.isInitialized) channelSync.onForegroundChanged(false); super.onStop() }
    override fun onDestroy() { trailers.acquire()?.stop(); playback.stop(); fakeSession?.close(); super.onDestroy() }
}

@AndroidEntryPoint
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StabilityVodActivity : ComponentActivity() {
    private lateinit var model: PlayerViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.STABILITY_FIXTURES)
        model = ViewModelProvider(this)[PlayerViewModel::class.java]
        lifecycleScope.launch {
            while (isActive) {
                model.exoPlayer?.let { player ->
                    player.volume = 0f
                    Diag.event("vod-state", mapOf("state" to player.playbackState, "positionMs" to player.currentPosition,
                        "playing" to player.isPlaying))
                }
                delay(1000)
            }
        }
        setContent { NuvioTheme { PlayerScreen(model, { _, _, _, _, _ -> finish() }) } }
    }
    override fun onDestroy() { if (::model.isInitialized) model.stopAndRelease(); super.onDestroy() }
}
