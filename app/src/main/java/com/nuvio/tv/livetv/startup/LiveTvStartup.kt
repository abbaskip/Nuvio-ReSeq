package com.nuvio.tv.livetv.startup

import android.content.Context
import androidx.navigation.NavController
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.effectiveStartPage
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveTvStartupEntryPoint {
    fun liveTvPreferences(): LiveTvPreferences
    fun onDemandRepository(): com.nuvio.tv.livetv.ondemand.OnDemandRepository
    fun liveTvRepository(): com.nuvio.tv.livetv.data.LiveTvRepository
    fun profileManager(): com.nuvio.tv.core.profile.ProfileManager
}

/**
 * Gets Live TV ready in the background shortly after the app starts (when it's in use), so
 * the guide is already there when you open it instead of loading only then.
 */
object LiveTvPreload {
    @Volatile private var started = false

    suspend fun start(context: android.content.Context) {
        if (started) return
        started = true
        clearOldBuffers(context.applicationContext, System.currentTimeMillis())
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, LiveTvStartupEntryPoint::class.java
        )
        // Reading the saved channel list and guide is quick and only uses storage, so it starts
        // almost straight away, as the app opens. Checking for updates (network, heavy) waits
        // until the app has settled.
        kotlinx.coroutines.delay(300)
        val prefs = entry.liveTvPreferences()
        val settings = prefs.settings.first()
        val playlists = prefs.playlists.first()
        if (!settings.showInSidebar || playlists.none { it.liveEnabled }) return
        entry.liveTvRepository().ensureLoaded(updateCheckDelayMs = 20_000)
    }
}

/**
 * Pause and Rewind Anytime buffers left behind when the app was closed or crashed mid-buffer
 * (up to about 35 MB a minute, in the app's own storage). Normally they were only cleared hours
 * later, the next time a buffer stopped. Buffers started from now on are left alone.
 */
private fun clearOldBuffers(context: android.content.Context, before: Long) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        runCatching {
            val roots = listOf(java.io.File(context.cacheDir, "timeshift")) +
                com.nuvio.tv.livetv.record.RecordingStorage.choices(context).mapNotNull { c ->
                    c.dir.parentFile?.let { java.io.File(it, "timeshift") }
                }
            roots.distinct().forEach { root ->
                root.listFiles()?.forEach { f ->
                    val started = f.name.toLongOrNull() ?: 0L
                    if (started < before - 1_000L) runCatching { f.deleteRecursively() }
                }
            }
        }
    }
}

/**
 * "Open Live TV when Nuvio starts": the first time the home screen appears after the app
 * starts, go straight to Live TV (Back from Live TV still leads to Nuvio's menu and home).
 */
object LiveTvStartup {
    @Volatile private var handled = false

    suspend fun maybeOpenLiveTv(context: Context, nav: NavController) {
        if (handled) return
        handled = true
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, LiveTvStartupEntryPoint::class.java)
        val settings = runCatching { entry.liveTvPreferences().settings.first() }.getOrNull() ?: return
        val route = when (settings.effectiveStartPage) {
            "LIVE_TV" -> Screen.LiveTv.route
            // On Demand only when something has been imported; otherwise stay on Home.
            "ON_DEMAND" -> Screen.OnDemand.route.takeIf {
                runCatching { entry.onDemandRepository().also { r -> r.start() }.hasContent.value || waitForOnDemand(entry) }.getOrDefault(false)
            }
            "SEARCH" -> Screen.Search.route
            "LIBRARY" -> Screen.Library.route
            "DISCOVER" -> Screen.Discover.route
            else -> null
        } ?: return
        nav.navigate(route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** The On Demand index is checked in the background at start-up; give it a moment. */
    private suspend fun waitForOnDemand(entry: LiveTvStartupEntryPoint): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(2_000) { entry.onDemandRepository().hasContent.first { it } } ?: false
}
