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
import java.net.URL
import javax.inject.Inject

private const val HTTP = "http://127.0.0.1:38791"
private val SHOWS = listOf("tt9900001", "tt9900002", "tt9900003")

/** Compiled only with -PstabilityFixtures=true into the separate allowed test packages. */
@AndroidEntryPoint
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StabilityJourneyActivity : ComponentActivity() {
    @Inject lateinit var profiles: ProfileManager
    @Inject lateinit var prefs: LiveTvPreferences
    @Inject lateinit var live: LiveTvRepository
    @Inject lateinit var vod: OnDemandRepository
    @Inject lateinit var playback: LiveTvPlaybackController
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
                        "home", "trailer" -> HomeScreen(home, { _, _, _ -> })
                        "sports", "cricket" -> SportsScreen({})
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
                }
            }
        }
        lifecycleScope.launch { while (isActive) { sample(); delay(30_000) } }
        command(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); command(intent) }

    private fun command(intent: Intent) {
        val action = intent.getStringExtra("journey") ?: "home"
        lifecycleScope.launch {
            try {
                when (action) {
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
        Diag.event("unwatch-assertions-passed", mapOf("tracker" to true))
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
            "liveTvAttached" to (playback.player != null), "liveTvPlaying" to (playback.player?.isPlaying == true), "threadCount" to stacks.size,
            "dispatcherBlocked" to stacks.count { (thread, stack) -> thread.name.startsWith("DefaultDispatcher-worker") &&
                stack.any { it.className.contains("usenet", true) || it.className.contains("CompletableFuture") || it.methodName.contains("socketRead", true) } },
            "gc" to Debug.getRuntimeStats(), "cwIds" to ids, "snapshot" to snapshots,
            "snapshotVersion" to projection.revision, "launcher" to launcher, "watchNext" to watchNext,
            "guideBuilding" to BackgroundWork.guideBuilding.value, "heavyJobs" to BackgroundWork.heavyJobs.value,
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
