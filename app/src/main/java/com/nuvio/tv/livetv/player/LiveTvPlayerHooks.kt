package com.nuvio.tv.livetv.player

import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.record.LiveTvRecorder
import com.nuvio.tv.livetv.ui.LiveTvSession
import com.nuvio.tv.livetv.ui.backupName
import com.nuvio.tv.livetv.ui.stationNote
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** Owned by playback so guide, Home, Sports and reminder tunes use the same failure chain. */
@Singleton
class LiveTvPlayerHooks @Inject constructor(
    private val prefs: LiveTvPreferences,
    private val repository: LiveTvRepository,
    private val session: LiveTvSession,
    private val recorder: dagger.Lazy<LiveTvRecorder>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = prefs.settings.stateIn(scope, SharingStarted.Eagerly, LiveTvSettings())
    private val userState = prefs.userState.stateIn(scope, SharingStarted.Eagerly, LiveUserState())
    @Volatile private var backupIndex: Map<String, List<LiveChannel>>? = null
    private var attached = false
    private var lastTune: String? = null

    internal fun beforePlay(playback: LiveTvPlaybackController, channel: LiveChannel) {
        if (!switchingToBackup && channel.key != lastTune) backupTried.clear()
        lastTune = channel.key
        if (attached) return
        attached = true
        playback.onLiveFailure = { key -> switchToBackup(playback, key) }
        scope.launch(Dispatchers.Default) {
            playback.state.map { it.channelKey != null && it.catchupTitle == null }.distinctUntilChanged().collectLatest { active ->
                backupIndex = null
                if (active) repository.channels.collectLatest { list -> backupIndex = list.groupBy { backupName(it.name) } }
            }
        }
        playback.onAudioChosen = { key, audio -> scope.launch { prefs.setChannelAudio(key, audio) } }
        playback.onCatchupHlsUnsupported = { id -> scope.launch {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: this provider doesn't do HLS replays; using TS for the next week")
            prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(catchupNoHlsSince = System.currentTimeMillis()) else it } }
        } }
        playback.onCatchupHlsWorks = { id -> scope.launch {
            if (prefs.currentPlaylists().any { it.id == id && it.catchupNoHlsSince > 0 }) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: HLS replays work on this provider again")
                prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(catchupNoHlsSince = 0L) else it } }
            }
        } }
    }

    /** Channels already tried in a row of backup switches (cleared when you change channel). */
    private val backupTried = HashSet<String>()
    private var switchingToBackup = false

    /**
     * Switches from a channel that won't play to another one with the same name (a different
     * group or playlist: "US: ESPN HD" / "ESPN Backup"), other playlists first. Each one is tried
     * once; when none are left it stops and the channel shows its error as before.
     */
    private fun switchToBackup(playback: LiveTvPlaybackController, failedKey: String): Boolean {
        if (!settings.value.autoBackupChannel) return false
        // Not worked out yet (just started): no backup this time, rather than a long search here.
        val index = backupIndex ?: return false
        val failed = playback.playingChannel?.takeIf { it.key == failedKey }
            ?: repository.channels.value.firstOrNull { it.key == failedKey } ?: return false
        backupTried += failedKey
        val name = backupName(failed.name)
        if (name.length < 2) return false
        val s = settings.value
        val user = userState.value
        val pick = index[name].orEmpty().asSequence()
            .filter { it.key !in backupTried }
            // Local stations: "FOX (WNYW)" isn't a backup for "FOX (KTTV)".
            .filter { c -> val a = stationNote(failed.name); val b = stationNote(c.name); a == null || b == null || a == b }
            .filter { !com.nuvio.tv.livetv.parental.ParentalControls.isLocked(it.groupId, it.group, s, user.lockedGroups) }
            .sortedBy { if (it.sourceId != failed.sourceId) 0 else 1 }
            .firstOrNull() ?: return false
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Channel ${failed.name} won't play: switched to ${pick.name} (${pick.group})")
        com.nuvio.tv.livetv.record.LiveTvNotice.show("${failed.name} isn't working right now. Switched to ${pick.name} from ${pick.group}.")
        switchingToBackup = true
        try { preview(playback, pick, session.lastGuideGroup) } finally { switchingToBackup = false }
        return true
    }

    fun preview(playback: LiveTvPlaybackController, channel: LiveChannel, fallbackGroup: String) {
        if (!switchingToBackup) backupTried.clear()
        session.catchup.value = null
        // Another channel from a provider that's recording: the recording holds a connection.
        recorder.get().recordings.value.firstOrNull {
            it.isRecording && it.channelKey != channel.key &&
                repository.channels.value.firstOrNull { c -> c.key == it.channelKey }?.sourceId == channel.sourceId
        }?.let { rec ->
            if (playback.playingChannel?.key != channel.key) {
                com.nuvio.tv.livetv.record.LiveTvNotice.show(
                    "Recording \"${rec.title}\" is using a connection to this provider. If this channel doesn't play, your account may allow only one at a time."
                )
            }
        }
        playback.play(channel)
        // Remember the group you watched in, so Live TV reopens there.
        val group = session.currentGroupId.value?.takeIf { it != ChannelGroup.SEARCH }
            ?: fallbackGroup
        scope.launch {
            prefs.recordWatched(channel.key, addToRecent = !ParentalControls.isAdult(channel.group) && !ParentalControls.isAdult(channel.name))
            prefs.setLastGroup(group)
        }
    }

}
