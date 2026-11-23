package com.nuvio.tv.mobile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.Reminder
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.reminders.ReminderEntryPoint
import com.nuvio.tv.livetv.reminders.reminderDelayMs
import com.nuvio.tv.livetv.sports.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MobileReminders(profile: Int, sports: SportsRepository, tune: (LiveChannel) -> Unit) {
    val context = LocalContext.current
    val entry = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java) }
    val prefs = remember(entry) { entry.liveTvPreferences() }
    val live = remember(entry) { entry.liveTvRepository() }
    val saved by prefs.guideReminders.collectAsStateWithLifecycle(initialValue = 0 to emptyList())
    val settings by sports.settings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val watch by rememberUpdatedState(tune)
    var guide by remember(profile) { mutableStateOf<Reminder?>(null) }
    var fixture by remember(profile) { mutableStateOf<SportsEvent?>(null) }
    var countdown by remember(fixture) { mutableStateOf<Int?>(null) }
    val shown = remember(profile) { mutableSetOf<String>() }
    val alert by SportsLive.alerts.collectAsStateWithLifecycle()
    suspend fun tuneAllowed(key: String) {
        val channel = live.channels.value.firstOrNull { it.key == key }
        val user = prefs.userState.first()
        val rules = prefs.settings.first()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || prefs.guideReminders.first().first != profile) return
        if (channel != null && key !in user.hiddenChannels && channel.groupId !in user.hiddenGroups &&
            !ParentalControls.isLocked(channel.groupId, channel.group, rules, user.lockedGroups)) watch(channel)
        else Toast.makeText(context, "Channel unavailable for this profile.", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(profile, saved) {
        if (saved.first != profile) { guide = null; return@LaunchedEffect }
        if (guide !in saved.second) guide = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val now = System.currentTimeMillis()
            if (saved.second.any { it.startMs + 1_800_000L <= now }) prefs.pruneReminders(now, profileId = profile)
            val next = saved.second.mapNotNull { r -> reminderDelayMs(r.startMs, now)?.let { r to it } }.minByOrNull { it.second }
            if (next != null) {
                delay(next.second)
                if (reminderDelayMs(next.first.startMs, System.currentTimeMillis()) == 0L) {
                    live.ensureLoaded(updateCheckDelayMs = 20_000L)
                    guide = next.first
                }
            }
        }
    }
    val dismissGuide: (Reminder) -> Unit = { r -> scope.launch { prefs.removeReminder(r.channelKey, r.startMs, profile) } }
    guide?.let { r ->
        LaunchedEffect(r) { delay(120_000); dismissGuide(r) }
        TouchSheet({ dismissGuide(r) }) {
            SectionTitle(r.title)
            Label("Starting soon · " + r.channelName)
            Row { Action("Watch") { scope.launch {
                val (active, reminders) = prefs.guideReminders.first()
                if (active == profile && r in reminders) { prefs.removeReminder(r.channelKey, r.startMs, profile); tuneAllowed(r.channelKey) }
            } }; Action("Dismiss") { dismissGuide(r) } }
        }
    }
    LaunchedEffect(sports) { sports.start() }
    LaunchedEffect(profile, settings.showInSidebar) {
        if (!settings.showInSidebar) { fixture = null; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(15_000)
                if (fixture != null || guide != null) continue
                val follows = sports.follows.value
                val config = sports.settings.value
                val now = System.currentTimeMillis()
                val seen = shown.toSet()
                val next = withContext(BackgroundWork.quiet) {
                    nextSportsReminder(sports.events.value, follows, config, seen, now, config.remindMinutes.coerceAtLeast(0) * 60_000L)
                }
                if (next != null) { shown += next.id; fixture = next }
                if (follows.reminders.any { it.startMs < now - 10_800_000L }) sports.updateFollows { f ->
                    f.copy(reminders = f.reminders.filter { it.startMs >= now - 10_800_000L })
                }
            }
        }
    }
    fixture?.takeIf { guide == null }?.let { event ->
        val best = sports.bestChannel(event.id)
        fun dismiss() { fixture = null; sports.dropReminder(event.id) }
        fun select(channel: LiveChannel) { dismiss(); scope.launch { tuneAllowed(channel.key) } }
        LaunchedEffect(event, best?.channel?.key) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (sports.switchesFor(event) && best?.sure == true) {
                    val now = System.currentTimeMillis()
                    delay((event.startMs - 70_000 - now).coerceAtLeast(0))
                    for (remain in 10 downTo 1) { countdown = remain; delay(1000) }
                    if (sports.switchesFor(event) && sports.bestChannel(event.id)?.channel?.key == best.channel.key) select(best.channel)
                    else dismiss()
                } else { delay(120_000); dismiss() }
            }
        }
        TouchSheet(::dismiss) {
            SectionTitle(event.title)
            Label(countdown?.let { "Switching channels in ${it}s" } ?: "Game starting soon")
            best?.let { Action("Watch " + it.channel.name) { select(it.channel) } }
            Action(if (sports.switchesFor(event)) "Cancel switch" else "Dismiss", onClick = ::dismiss)
        }
    }
    LaunchedEffect(alert?.id) {
        val current = alert ?: return@LaunchedEffect
        delay(7_000)
        SportsLive.alerts.compareAndSet(current, null)
    }
    if (settings.showInSidebar && settings.scoringAlerts && !settings.hideScores) alert?.let { score ->
        Label(score.team.name + " scored · " + score.event.sides.joinToString(" – ") { it.score ?: "" },
            Modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp), strong = true)
    }
}
