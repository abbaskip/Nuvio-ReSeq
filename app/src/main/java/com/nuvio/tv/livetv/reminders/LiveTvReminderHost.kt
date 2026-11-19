package com.nuvio.tv.livetv.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.Reminder
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun reminderDelayMs(startMs: Long, now: Long): Long? =
    if (now >= startMs + 600_000L) null else (startMs - 60_000L - now).coerceAtLeast(0)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun liveTvPreferences(): LiveTvPreferences
    fun liveTvRepository(): LiveTvRepository
    fun liveTvPlayback(): LiveTvPlaybackController
}

/**
 * Shows "Remind me" messages anywhere in Nuvio: a minute before the show starts, with Watch
 * (switches to the channel, full screen) or Dismiss. Lives next to the app's navigation.
 */
@Composable
fun LiveTvReminderHost(navController: NavController) {
    val context = LocalContext.current
    val entry = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java) }.getOrNull()
    } ?: return
    val scope = rememberCoroutineScope()
    val prefs = remember(entry) { entry.liveTvPreferences() }
    val saved by prefs.guideReminders.collectAsStateWithLifecycle(initialValue = 0 to emptyList())
    val (profile, reminders) = saved
    var due by remember(profile) { mutableStateOf<Reminder?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(reminders, profile, lifecycle) {
        if (due !in reminders) due = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (due == null && reminders.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (reminders.any { it.startMs + 1_800_000L <= now }) prefs.pruneReminders(now, profileId = profile)
                val next = reminders.mapNotNull { r -> reminderDelayMs(r.startMs, now)?.let { r to it } }
                    .minByOrNull { it.second } ?: break
                if (next.second == 0L) {
                    due = next.first
                    entry.liveTvRepository().ensureLoaded(updateCheckDelayMs = 20_000)
                    break
                }
                delay(next.second.coerceAtMost(60_000L))
            }
        }
    }

    val r = due ?: return
    fun finish() {
        scope.launch { prefs.removeReminder(r.channelKey, r.startMs, profile) }
    }
    val first = remember(r) { FocusRequester() }
    // Goes away by itself after a couple of minutes.
    LaunchedEffect(r) { delay(120_000); if (due == r) finish() }
    LiveDialog(onDismiss = { finish() }, width = 460.dp) {
        val now = System.currentTimeMillis()
        LiveText(
            if (now < r.startMs) "Starting in a minute" else "Starting now",
            color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold
        )
        LiveText(r.title, size = 22.sp, weight = FontWeight.Bold, maxLines = 2)
        LiveText(
            "${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(r.startMs))} · ${r.channelName}",
            color = NuvioTheme.colors.TextSecondary, size = 14.sp
        )
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuItem("Watch", Modifier.focusRequester(first)) {
                scope.launch {
                    if (prefs.guideReminders.first().let { it.first != profile || r !in it.second }) return@launch
                    val channel = entry.liveTvRepository().channels.value.firstOrNull { it.key == r.channelKey }
                    val user = prefs.userState.first()
                    val canTune = channel != null && channel.key !in user.hiddenChannels && channel.groupId !in user.hiddenGroups &&
                        !ParentalControls.isLocked(channel.groupId, channel.group, prefs.settings.first(), user.lockedGroups)
                    prefs.removeReminder(r.channelKey, r.startMs, profile)
                    if (canTune && channel != null) {
                        entry.liveTvPlayback().play(channel)
                        com.nuvio.tv.livetv.sports.openLiveTvFullscreen(navController)
                    } else navController.navigate(Screen.LiveTv.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
            MenuItem("Dismiss") { finish() }
        }
    }
    LaunchedEffect(r) { delay(80); runCatching { first.requestFocus() } }
}
