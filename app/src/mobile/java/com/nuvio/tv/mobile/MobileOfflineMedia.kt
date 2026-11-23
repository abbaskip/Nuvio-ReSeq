package com.nuvio.tv.mobile

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.annotation.Keep
import com.google.gson.Gson
import com.nuvio.tv.domain.model.TvdbPlaybackContext
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.streamLinkCacheKeys
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Persist selection identity and display metadata only. Provider URLs and credentials are transient. */
@Keep
internal data class MobileVideo(
    val profile: Int, val type: String, val contentId: String, val videoId: String, val title: String,
    val season: Int? = null, val episode: Int? = null, val episodeName: String? = null,
    val tvdb: String? = null, val poster: String? = null
) {
    val label: String get() {
        val displayed = TvdbPlaybackContext.decode(tvdb)
        val number = displayed?.episode ?: episode ?: return title
        val name = episodeName?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        return "$title S${displayed?.season ?: season ?: 0}E$number$name"
    }
    val key: String get() {
        val episodeContext = TvdbPlaybackContext.decode(tvdb)
        val selection = streamLinkCacheKeys(type, videoId, episodeContext).firstOrNull()
            ?: episodeContext?.storageKey(contentId) ?: videoId
        val input = listOf(profile.toString(), type, contentId, selection).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun sources(manual: Boolean, explicitAuto: Boolean) = Screen.Stream.createRoute(videoId, type, title, poster = poster,
        contentId = contentId, contentName = title, season = season, episode = episode, episodeName = episodeName,
        profileId = profile, manualSelection = manual, autoSelectStream = explicitAuto, tvdbContext = tvdb ?: "provider")

    fun player(url: String, info: StreamPlaybackInfo? = null, localName: String? = null, fromBeginning: Boolean = false) =
        Screen.Player.createRoute(streamUrl = url, title = label, contentId = contentId, contentType = type,
            contentName = title, videoId = videoId, season = season, episode = episode, episodeTitle = episodeName,
            profileId = profile, tvdbContext = tvdb ?: "provider", poster = poster, headers = info?.headers,
            streamName = if (localName != null) "Downloaded" else info?.streamName, filename = localName ?: info?.filename,
            bingeGroup = info?.bingeGroup, infoHash = info?.infoHash, fileIdx = info?.fileIdx,
            sources = info?.sources, videoHash = info?.videoHash, videoSize = info?.videoSize,
            contentLanguage = info?.contentLanguage, addonName = info?.addonName, addonLogo = info?.addonLogo,
            streamDescription = info?.streamDescription, startFromBeginning = fromBeginning,
            year = info?.year, logo = info?.logo, backdrop = info?.backdrop, audioDelayMs = info?.audioDelayMs, audioDelayLanguage = info?.audioDelayLanguage)
}

@Keep
internal data class OfflineFile(val video: MobileVideo, val filename: String, val length: Long)
internal data class DownloadStatus(val video: MobileVideo, val text: String, val active: Boolean)

@Singleton
internal class MobileOfflineMedia @Inject constructor(@ApplicationContext private val context: Context) {
    private val gson = Gson()
    val changes = MutableStateFlow(0L)
    val transfers = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    private fun root(profile: Int): File {
        val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IOException("Download storage is unavailable.")
        return File(movies, "ReSeq/$profile")
    }

    fun folder(video: MobileVideo) = File(root(video.profile), video.key)

    private fun record(dir: File): OfflineFile? = runCatching {
        val entry = gson.fromJson(File(dir, "entry.json").readText(), OfflineFile::class.java)
        require(entry.filename == File(entry.filename).name && entry.video.key == dir.name)
        val media = File(dir, entry.filename)
        entry.takeIf { media.isFile && it.length > 0 && media.length() == it.length }
    }.getOrNull()

    fun find(video: MobileVideo): File? = runCatching {
        val dir = folder(video)
        record(dir)?.let { File(dir, it.filename) }
    }.getOrNull()

    fun saved(profile: Int): List<OfflineFile> = runCatching {
        root(profile).listFiles().orEmpty().mapNotNull(::record).sortedBy { it.video.label.lowercase() }
    }.getOrDefault(emptyList())

    @Synchronized
    fun publish(video: MobileVideo, partial: File, extension: String) {
        val dir = folder(video)
        val base = video.label.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim(' ', '.').take(42).ifEmpty { "Video" }
        val previous = record(dir)
        val target = File(dir, "$base - ${java.util.UUID.randomUUID().toString().take(8)}.$extension")
        val entry = OfflineFile(video, target.name, partial.length())
        require(entry.length > 0)
        val index = File(dir, "entry.tmp")
        index.writeText(gson.toJson(entry))
        if (!partial.renameTo(target)) throw IOException("Could not finish saving the video.")
        if (!index.renameTo(File(dir, "entry.json"))) {
            target.delete()
            throw IOException("Could not index the downloaded video.")
        }
        previous?.let { File(dir, it.filename).delete() }
        changes.update { it + 1 }
    }

    fun localRoute(video: MobileVideo, fromBeginning: Boolean = false): String? = find(video)?.let {
        video.player(Uri.fromFile(it).toString(), localName = it.name, fromBeginning = fromBeginning)
    }

    fun localStream(video: MobileVideo): Stream? = find(video)?.let { file ->
        Stream(name = "Downloaded", title = video.label, description = null, url = Uri.fromFile(file).toString(),
            ytId = null, infoHash = null, fileIdx = null, externalUrl = null, addonName = "Downloads", addonLogo = null,
            behaviorHints = StreamBehaviorHints(null, null, null, null, videoSize = file.length(), filename = file.name))
    }

    @Synchronized
    fun remove(entry: OfflineFile) {
        val dir = folder(entry.video)
        val current = record(dir) ?: return
        if (current.filename != entry.filename) throw IOException("The saved file changed. Reopen Downloads to remove it.")
        if (!File(dir, current.filename).delete()) throw IOException("Could not remove this download.")
        File(dir, "entry.json").delete()
        dir.delete()
        transfers.update { it - entry.video.key }
        changes.update { it + 1 }
    }
}
