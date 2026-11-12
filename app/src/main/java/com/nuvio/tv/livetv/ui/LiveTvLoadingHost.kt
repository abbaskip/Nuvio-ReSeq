package com.nuvio.tv.livetv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.ondemand.OnDemandRepository
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LoadingEntryPoint {
    fun liveTvRepository(): LiveTvRepository
    fun onDemandRepository(): OnDemandRepository
}

/**
 * A small "working on it" pill below the top-right headers, anywhere in Nuvio, while playlists,
 * TV guides or On Demand catalogs are downloading, with what's happening and a running count.
 * Hidden while something is playing full screen.
 */
@Composable
fun LiveTvLoadingHost(navController: NavController) {
    val context = LocalContext.current
    val entry = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, LoadingEntryPoint::class.java) }.getOrNull()
    } ?: return
    val live by entry.liveTvRepository().status.collectAsState()
    val vod by entry.onDemandRepository().status.collectAsState()
    val guideNotice by entry.liveTvRepository().guideUpdateNotice.collectAsState()
    val guideBuilding by com.nuvio.tv.livetv.data.BackgroundWork.guideBuilding.collectAsState()
    val route = navController.currentBackStackEntryAsState().value?.destination?.route.orEmpty()
    val liveFullscreen by LiveTvFullscreen.active.collectAsState()
    val playing = route == Screen.LiveTvPlayer.route || route.startsWith("player/") || liveFullscreen
    val progress = when {
        live.loading && !live.quiet -> live.message ?: "Updating Live TV…"
        vod.loading -> vod.message ?: "Importing movies and series…"
        else -> null
    }
    val waiting = guideNotice?.takeIf { it.waiting }?.message
    val error = guideNotice?.takeUnless { it.waiting }?.message ?: live.error ?:
        vod.message?.takeIf { !vod.loading }
    val updating = progress != null || guideBuilding
    var wasUpdating by remember { mutableStateOf(false) }
    var sawVisibleUpdate by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var retainedError by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }
    LaunchedEffect(updating, progress != null, waiting, error, playing) {
        if (updating && !wasUpdating) retainedError = null
        if (error != lastError) {
            lastError = error
            if (error != null) retainedError = error
        }
        wasUpdating = updating
        if (updating || waiting != null) {
            completed = false
            if (!playing && (progress != null || waiting != null)) sawVisibleUpdate = true
        } else if (sawVisibleUpdate) {
            sawVisibleUpdate = false
            completed = retainedError == null
        }
        if (retainedError != null) completed = false
    }
    LaunchedEffect(completed) {
        if (completed) {
            kotlinx.coroutines.delay(3_000)
            completed = false
        }
    }
    val message = progress ?: waiting ?: retainedError ?:
        if (completed) "Channels and guide up to date" else null
    // How long it's been working, so a long first load reads as busy rather than stuck.
    val busy = progress != null
    val visible = message != null && !playing
    val startedAt = androidx.compose.runtime.remember(busy) { System.currentTimeMillis() }
    val elapsedSec by androidx.compose.runtime.produceState(0L, busy, visible) {
        value = 0L
        while (busy && visible) {
            kotlinx.coroutines.delay(1_000)
            value = (System.currentTimeMillis() - startedAt) / 1000
        }
    }
    val elapsed = if (busy && elapsedSec >= 20) " · " + "%d:%02d".format(elapsedSec / 60, elapsedSec % 60) else ""
    // Recording messages (set, started, missed, a clash), for a few seconds, wherever you are.
    val notice by com.nuvio.tv.livetv.record.LiveTvNotice.current.collectAsState()
    androidx.compose.runtime.LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (n.text.length > 60) 6_000 else 4_000)
        com.nuvio.tv.livetv.record.LiveTvNotice.clear(n)
    }
    // Leave the guide info panel and Sports' header/hero row in place above the pill.
    val topInset = when (route) {
        Screen.LiveTv.route -> 188.dp
        Screen.Sports.route -> 244.dp
        else -> 96.dp
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = notice != null && !playing,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LiveText(
                notice?.text.orEmpty(),
                modifier = Modifier
                    .padding(bottom = 64.dp, start = 48.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                color = Color.White,
                size = 15.sp,
                maxLines = 3
            )
        }
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .padding(end = 28.dp, top = topInset)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(50))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (busy) CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = NuvioTheme.colors.Secondary,
                    strokeWidth = 2.dp
                )
                if (busy) Spacer(Modifier.width(10.dp))
                LiveText(message.orEmpty() + elapsed, color = Color.White, size = 12.sp)
            }
        }
    }
}

/** The same spinner, for use next to a status line in settings. */
@Composable
internal fun LoadingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(16.dp), color = NuvioTheme.colors.Secondary, strokeWidth = 2.dp)
}
