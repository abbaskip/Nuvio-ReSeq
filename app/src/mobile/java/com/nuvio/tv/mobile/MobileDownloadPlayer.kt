package com.nuvio.tv.mobile

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.player.ExternalPlaybackMetadata
import com.nuvio.tv.data.local.PlayerPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Decide before creating the internal PlayerViewModel, which starts opening the file on entry. */
@Composable
internal fun downloadUsesInternalPlayer(video: MobileVideo, fromBeginning: Boolean, catalog: MobileCatalogViewModel,
    cancel: () -> Unit, externalOpened: () -> Unit): Boolean {
    val context = LocalContext.current
    var choice by rememberSaveable(video.key) { mutableStateOf<PlayerPreference?>(null) }
    var error by rememberSaveable(video.key) { mutableStateOf<String?>(null) }
    val onOpened by rememberUpdatedState(externalOpened)
    LaunchedEffect(video.key, choice) {
        try {
            when (choice) {
                null -> choice = catalog.playback.playerSettings.first().playerPreference
                PlayerPreference.EXTERNAL -> {
                    val uri = withContext(Dispatchers.IO) { catalog.downloads.shareUri(video) }
                    if (uri == null) {
                        error = "This download is no longer on this device."
                        return@LaunchedEffect
                    }
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                    if (context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                        error = "No installed app can open this video. You can play it in ReSeq."
                        return@LaunchedEffect
                    }
                    val metadata = ExternalPlaybackMetadata(video.contentId, video.type, video.title, video.poster,
                        backdrop = null, logo = null, videoId = video.videoId, season = video.season, episode = video.episode,
                        episodeTitle = video.episodeName, year = null, profileId = video.profile, tvdbContextJson = video.tvdb)
                    val opened = catalog.external.launchPlayer(metadata, uri.toString(), video.label, headers = null,
                        startFromBeginning = fromBeginning, context = context)
                    if (opened) onOpened() else error = "The external player couldn’t open this download."
                }
                else -> Unit
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Couldn’t open the downloaded video." }
    }
    if (choice == PlayerPreference.INTERNAL) return true
    if (choice == PlayerPreference.ASK_EVERY_TIME || error != null) {
        TouchSheet(cancel) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BrowseHeading("Choose player", cancel)
                Label(video.label)
                error?.let { Label(it, small = true) }
                Action("ReSeq player", Modifier.fillMaxWidth()) { choice = PlayerPreference.INTERNAL; error = null }
                if (error == null) Action("External player", Modifier.fillMaxWidth()) { choice = PlayerPreference.EXTERNAL }
            }
        }
    } else LinearProgressIndicator(Modifier.fillMaxWidth())
    return false
}
