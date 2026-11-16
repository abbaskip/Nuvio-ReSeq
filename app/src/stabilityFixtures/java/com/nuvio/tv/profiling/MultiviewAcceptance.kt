package com.nuvio.tv.profiling

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.media3.ui.PlayerView
import com.nuvio.tv.core.diagnostics.StabilityDiagnostics as Diag
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.cw.*
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nuvio.tv.data.tvdb.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.*
import com.nuvio.tv.livetv.data.*
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.multiview.*
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.ui.screens.detail.*
import com.nuvio.tv.ui.screens.home.HomeViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.URL
import javax.inject.Inject

/** Compiled only into the shell-protected fixture package. Never present in the signed beta. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultiviewAcceptance @Inject constructor(
    private val profiles: ProfileManager, private val live: LiveTvRepository,
    private val prefs: LiveTvPreferences, private val playback: LiveTvPlaybackController,
    private val addonPrefs: AddonPreferences, private val addons: AddonRepository,
    private val orders: TvdbOrderStore, private val tvdb: TvdbCredentialStore,
    private val auth: TraktAuthDataStore, private val tracking: TraktSettingsDataStore,
    private val catalogue: MetaRepository, private val progress: WatchProgressRepository,
    private val refresh: TrackingProgressRefreshCoordinator, private val scrobbles: TrackingScrobbleCoordinator,
    private val resolver: TvdbTrackingResolver, private val writers: TrackingHistoryWriterRegistry,
    private val cw: ContinueWatchingRepository,
) {
    private val http = "http://127.0.0.1:38791"
    private val show = "tt0386676"
    private var owner: MultiviewLifecycle<MultiviewPlaybackSession>? = null
    private var session by mutableStateOf<MultiviewPlaybackSession?>(null)
    private var optionsOpen by mutableStateOf(false)
    private var details: MetaDetailsViewModel? = null
    private var selected: Video? = null
    private var watch: WatchProgress? = null
    private var originalTime = 0L
    private var fixtureCw: ContinueWatchingRepository? = null
    private var backgroundReleased = false

    suspend fun assertScreen(activity: ComponentActivity) {
        fun players(view: android.view.View): List<androidx.media3.common.Player> = when (view) {
            is PlayerView -> listOfNotNull(view.player)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { players(view.getChildAt(it)) }
            else -> emptyList()
        }
        withTimeout(30_000) {
            while (true) {
                val active = players(activity.window.decorView).distinct()
                if (active.size == 2 && active.all { it.isPlaying && it.videoSize.width > 0 }) break
                delay(200)
            }
        }
        check(playback.player == null)
        check(BackgroundWork.liveChannelOpen.value)
        Diag.event("m1-production-screen-passed")
    }

    @Composable fun Content() {
        session?.let { current ->
            val state by current.state.collectAsState()
            Row(Modifier.fillMaxSize()) {
                state.panes.forEach { pane ->
                    AndroidView(factory = { context ->
                        (android.view.LayoutInflater.from(context).inflate(com.nuvio.tv.R.layout.live_tv_texture_player_view, null) as PlayerView)
                            .apply { useController = false }
                    }, modifier = Modifier.weight(1f).fillMaxHeight(), update = { it.player = pane.player },
                        onRelease = { it.player = null })
                }
            }
        }
        if (optionsOpen) EpisodeOptionsOverlay(episode = checkNotNull(selected), isWatched = true,
            isPending = false, hasProgress = true, lastWatchDate = "fixture rewatch",
            onRemoveWatch = {
                details!!.removeEpisodeWatch(selected!!, watch!!, true)
                optionsOpen = false
            }, onDismiss = { optionsOpen = false }, onPlay = {}, onToggleWatched = {})
    }

    suspend fun command(action: String, activity: ComponentActivity, home: HomeViewModel) {
        when (action) {
            "m1-device-info" -> {
                val am = activity.getSystemService(android.app.ActivityManager::class.java)
                val memory = android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo)
                Diag.event("m1-device-info", mapOf("limit" to MultiviewDevicePolicy.read(activity),
                    "heapMb" to com.nuvio.tv.ui.screens.settings.MemoryBudget.heapMb,
                    "physicalRamMb" to memory.totalMem / (1024 * 1024), "lowRam" to am.isLowRamDevice))
            }
            "m1-multiview" -> {
                profiles.activeProfileReady.first { it }
                prefs.updateSettings { it.copy(autoReconnect = false, autoBackupChannel = false) }
                prefs.updatePlaylists { listOf(PlaylistSource("m1-fixture", "M1 fixtures", "$http/m1/list.m3u")) }
                live.refreshAll()
                val channels = withTimeout(30_000) { live.channels.first { it.size == 2 } }
                playback.play(channels.first())
                val limit = MultiviewDevicePolicy.read(activity)
                check(limit >= 2) { "Emulator capability limit is $limit" }
                owner = MultiviewLifecycle(playback::releaseNow, {
                    check(playback.player == null) { "Single player must release first" }
                    MultiviewPlaybackSession(activity, maxPanes = limit, autoReconnect = { false },
                        startFailure = { c, active -> MultiviewConnectionPolicy.denial(c, active,
                            listOf(PlaylistSource("m1-fixture", "Fixture", "$http/m1/list.m3u")), emptyMap()) })
                }, { BackgroundWork.liveChannelOpen.value = it }, MultiviewPlaybackSession::onForeground,
                    MultiviewPlaybackSession::onBackground, MultiviewPlaybackSession::release)
                session = owner!!.session
                activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> owner?.onForeground()
                        Lifecycle.Event.ON_STOP -> {
                            owner?.onBackground()
                            backgroundReleased = session?.state?.value?.panes?.all { it.player == null } ?: true
                            check(backgroundReleased)
                            Diag.event("m1-background-release-observed")
                        }
                        Lifecycle.Event.ON_DESTROY -> owner?.close()
                        else -> Unit
                    }
                })
                owner!!.onForeground()
                channels.forEachIndexed { i, c -> session!!.assignChannel(i, c) }
                withTimeout(30_000) { while (session!!.state.value.panes.any { !it.isPlaying || it.player?.videoSize?.width == 0 }) delay(200) }
                check(BackgroundWork.liveChannelOpen.value)
                Diag.event("m1-two-panes", mapOf("limit" to limit, "heapMb" to com.nuvio.tv.ui.screens.settings.MemoryBudget.heapMb,
                    "playing" to session!!.state.value.panes.count { it.isPlaying },
                    "videoWidths" to session!!.state.value.panes.map { it.player!!.videoSize.width }))
            }
            "m1-background-assert" -> {
                check(backgroundReleased)
                check(BackgroundWork.liveChannelOpen.value)
                Diag.event("m1-background-passed")
            }
            "m1-stop" -> {
                session!!.stopAll()
                check(session!!.state.value.panes.all { it.player == null && it.channelKey == null })
                owner!!.close(); session = null
                check(!BackgroundWork.liveChannelOpen.value)
            }
            "m1-rewatch-seed" -> {
                setup("rewatch")
                details = ViewModelProvider(activity)[MetaDetailsViewModel::class.java]
                val state = withTimeout(60_000) { details!!.uiState.first { s ->
                    val parts = s.meta?.videos.orEmpty().filter { it.title.startsWith("The Delivery") }
                    parts.size == 2 && parts.all { s.episodeProgressMap[it.season to it.episode]?.hasRemovableTraktWatch() == true }
                } }
                selected = state.meta!!.videos.first { it.title.startsWith("The Delivery") }
                watch = state.episodeProgressMap[selected!!.season to selected!!.episode]!!
                originalTime = watch!!.lastWatched
                optionsOpen = true
            }
            "m1-rewatch-assert" -> {
                check(!optionsOpen) { "The actual episode options action was not invoked" }
                withTimeout(60_000) { details!!.uiState.first { s ->
                    val parts = s.meta!!.videos.filter { it.title.startsWith("The Delivery") }
                    parts.all { video -> s.episodeProgressMap[video.season to video.episode]?.let {
                        it.isCompleted() && it.lastWatched < originalTime
                    } == true }
                } }
                cw.refreshProjection()
                check(cw.snapshot().members.containsKey(show))
                check(progress.getAllEpisodeProgress(show).first().values.count { it.isCompleted() } >= 3)
                Diag.event("m1-rewatch-passed", mapOf("bothPartsWatched" to true, "cwMember" to true))
            }
            "m1-stale-pause" -> {
                val meta = setup("pause")
                val raw = withTimeout(30_000) { progress.allProgress.first { list -> list.any { it.contentId == show && it.progressPercent == 49.75f } } }
                    .first { it.contentId == show }
                val first = meta.videos.first { it.title.startsWith("The Delivery") }
                val stale = CachedNextUpItem(show, "series", "The Office", null, null, null, first.id,
                    first.season!!, first.episode!!, first.title, thumbnail = null, lastWatched = raw.lastWatched - 60_000,
                    sortTimestamp = raw.lastWatched - 60_000, tvdbContext = first.tvdbPlaybackContext(), chooseEpisode = false)
                // A new owner reads a deliberately stale durable card, exactly like an upgrade/cold restore.
                val isolated = object : android.content.ContextWrapper(activity) {
                    override fun getFilesDir() = java.io.File(activity.filesDir, "m1-stale-owner").apply { mkdirs() }
                }
                CwProjectionStore(java.io.File(isolated.filesDir, "cw_enrichment")).write(CwProjection(profileId = 1,
                    source = "TRAKT", members = mapOf(show to CwMember(progress = raw, nextUp = stale))))
                val repository = ContinueWatchingRepository(isolated, profiles, tracking, javax.inject.Provider { progress },
                    tvdbOrders = orders, tvdbTracking = javax.inject.Provider { resolver })
                fixtureCw = repository
                check(repository.snapshot().nextUp.isEmpty())
                repository.refreshProjection()
                val rebuilt = repository.snapshot()
                val partTwo = rebuilt.nextUp.single { it.contentId == show }
                check(partTwo.episodeTitle == "The Delivery Part 2 (Extended Cut)")
                check(partTwo.lastWatched == raw.lastWatched)
                repository.enrich(rebuilt.lease, nextUp = listOf(stale.copy(lastWatched = raw.lastWatched)))
                check(repository.snapshot().nextUp.single { it.contentId == show } == partTwo)
                Diag.event("m1-stale-pause-passed", mapOf("episode" to partTwo.episodeTitle))
            }
            "m1-contract" -> {
                val meta = setup("contract")
                val writer = writers.writer(TrackingProviderId.TRAKT)!!
                val lecture = meta.videos.single { it.episodeIdentity?.tvdbEpisodeId == 10718533L }
                for (video in listOf(lecture, meta.videos.first { it.title.startsWith("The Delivery") })) {
                    val reference = buildTrackingMediaReference("series", show, video.id, "The Office",
                        seasonNumber = video.season, episodeNumber = video.episode, episodeTitle = video.title).let {
                        it.copy(catalog = it.catalog!!.copy(tvdbContext = video.tvdbPlaybackContext(), tvdbCoverageVerified = true)) }
                    for ((action, at) in listOf(TrackingScrobbleAction.START to 0.0, TrackingScrobbleAction.PAUSE to 47.0,
                        TrackingScrobbleAction.START to 47.0, TrackingScrobbleAction.STOP to 94.3592)) {
                        check(scrobbles.scrobble(action, TrackingScrobbleEvent(reference, at)).isEmpty())
                    }
                    for (watched in listOf(true, false)) {
                        val targets = resolver.resolveAll(1, TrackingProviderId.TRAKT, reference, completed = true.takeIf { watched })!!
                        if (watched) writer.addToHistory(1, targets.map { TrackingHistoryItem(it, 1_700_000_000_000) })
                        else writer.removeFromHistory(1, targets)
                    }
                }
                Diag.event("m1-contract-delivered")
            }
            else -> error("Unknown M1 journey")
        }
    }

    private suspend fun setup(mode: String): Meta {
        withContext(Dispatchers.IO) { URL("$http/m1/control?mode=$mode").readText() }
        profiles.activeProfileReady.first { it }
        addonPrefs.removeAddon("https://v3-cinemeta.strem.io")
        addonPrefs.removeAddon("https://opensubtitles-v3.strem.io")
        addons.addAddon("$http/addon/manifest.json")
        withTimeout(30_000) { addons.awaitInstalledAddons(setOf("stability.fixture")) }
        orders.save(1, 73244, "alttwo", listOf(show))
        tvdb.save(1, "synthetic-fixture-key")
        auth.saveToken(TraktTokenResponseDto("fixture-token", "bearer", 86400, "fixture-refresh", createdAt = System.currentTimeMillis() / 1000))
        auth.saveUser("fixture", "fixture")
        tracking.setWatchProgressSource(WatchProgressSource.TRAKT)
        check(refresh.refreshConnected(TrackingRefreshIntent.INVALIDATED).isEmpty())
        val result = catalogue.getMetaFromAllAddons("series", show).first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
        return (result as com.nuvio.tv.core.network.NetworkResult.Success).data
    }
}
