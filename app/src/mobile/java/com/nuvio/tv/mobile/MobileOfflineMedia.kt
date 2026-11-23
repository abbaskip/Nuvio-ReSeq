package com.nuvio.tv.mobile

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.annotation.Keep
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.gson.Gson
import com.nuvio.tv.domain.model.TvdbPlaybackContext
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.resolvedEpisodeForPlayback
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.streamLinkCacheKeys
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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

    fun sources(manual: Boolean, explicitAuto: Boolean, fromBeginning: Boolean = false) = Screen.Stream.createRoute(videoId, type, title, poster = poster,
        contentId = contentId, contentName = title, season = season, episode = episode, episodeName = episodeName,
        profileId = profile, manualSelection = manual, autoSelectStream = explicitAuto, startFromBeginning = fromBeginning, tvdbContext = tvdb ?: "provider")

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
internal data class OfflineDetails(val synopsis: String?, val release: String?, val genres: String?, val runtime: String?,
    val episodeSynopsis: String?, val episodeAirDate: String?) {
    fun keepingMissingFrom(previous: OfflineDetails?) = OfflineDetails(
        synopsis?.takeIf(String::isNotBlank) ?: previous?.synopsis,
        release?.takeIf(String::isNotBlank) ?: previous?.release,
        genres?.takeIf(String::isNotBlank) ?: previous?.genres,
        runtime?.takeIf(String::isNotBlank) ?: previous?.runtime,
        episodeSynopsis?.takeIf(String::isNotBlank) ?: previous?.episodeSynopsis,
        episodeAirDate?.takeIf(String::isNotBlank) ?: previous?.episodeAirDate
    )
}

internal fun Meta.offlineDetails(video: MobileVideo): OfflineDetails {
    val episode = videos.resolvedEpisodeForPlayback(TvdbPlaybackContext.decode(video.tvdb), video.videoId)
    return OfflineDetails(description, releaseInfo, genres.joinToString(" · ").ifBlank { null },
        episode?.runtime?.let { "$it min" } ?: runtime, episode?.overview, episode?.released?.take(10))
}

@Keep
internal data class OfflineFile(val video: MobileVideo, val filename: String, val length: Long,
    val details: OfflineDetails? = null) {
    @Transient var artwork: String? = null
}
internal data class DownloadStatus(val video: MobileVideo, val text: String, val active: Boolean)

@Singleton
internal class MobileOfflineMedia @Inject constructor(@ApplicationContext private val context: Context) {
    private val gson = Gson()
    val changes = MutableStateFlow(0L)
    val transfers = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    private val viewedTitles = android.util.LruCache<Triple<Int, String, String>, Meta>(4)

    fun rememberTitle(profile: Int, type: String, id: String, meta: Meta) {
        viewedTitles.put(Triple(profile, type, id), meta)
    }

    fun viewedTitle(video: MobileVideo): Meta? = viewedTitles.get(Triple(video.profile, video.type, video.contentId))

    suspend fun fetchCover(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        return try {
            withTimeoutOrNull(3_000) {
                val request = ImageRequest.Builder(context).data(url).size(320, 480).allowHardware(false).build()
                val cover = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                cover?.image?.toBitmap()?.let { bitmap -> java.io.ByteArrayOutputStream().use { bytes ->
                    if (bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bytes)) bytes.toByteArray() else null
                } }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

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
        entry.takeIf { media.isFile && it.length > 0 && media.length() == it.length }?.apply {
            artwork = File(dir, "${filename}.jpg").takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
        }
    }.getOrNull()

    fun find(video: MobileVideo): File? = runCatching {
        val dir = folder(video)
        record(dir)?.let { File(dir, it.filename) }
    }.getOrNull()

    fun saved(profile: Int): List<OfflineFile> = runCatching {
        root(profile).listFiles().orEmpty().mapNotNull(::record).sortedBy { it.video.label.lowercase() }
    }.getOrDefault(emptyList())

    @Synchronized
    fun publish(video: MobileVideo, partial: File, extension: String, details: OfflineDetails? = null, cover: ByteArray? = null) {
        val dir = folder(video)
        val base = video.label.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim(' ', '.').take(42).ifEmpty { "Video" }
        val previous = record(dir)
        val target = File(dir, "$base - ${java.util.UUID.randomUUID().toString().take(8)}.$extension")
        val entry = OfflineFile(video, target.name, partial.length(), details?.keepingMissingFrom(previous?.details) ?: previous?.details)
        require(entry.length > 0)
        val index = File(dir, "entry.tmp")
        index.writeText(gson.toJson(entry))
        if (!partial.renameTo(target)) throw IOException("Could not finish saving the video.")
        if (!index.renameTo(File(dir, "entry.json"))) {
            target.delete()
            throw IOException("Could not index the downloaded video.")
        }
        val artwork = File(dir, "${target.name}.jpg")
        try {
            if (cover != null) artwork.writeBytes(cover)
            else previous?.let { File(dir, "${it.filename}.jpg") }?.takeIf(File::isFile)?.copyTo(artwork)
        } catch (_: IOException) { artwork.delete() }
        previous?.let { File(dir, it.filename).delete(); File(dir, "${it.filename}.jpg").delete() }
        changes.update { it + 1 }
    }

    fun shareUri(video: MobileVideo): Uri? = find(video)?.let {
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
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
    fun updateDetails(entry: OfflineFile, meta: Meta, cover: ByteArray? = null) {
        val dir = folder(entry.video)
        val current = record(dir) ?: return
        if (current.filename != entry.filename) return
        val details = meta.offlineDetails(entry.video).keepingMissingFrom(current.details)
        if (details != current.details) {
            val index = File(dir, "entry.tmp")
            index.writeText(gson.toJson(current.copy(details = details)))
            if (index.renameTo(File(dir, "entry.json"))) changes.update { it + 1 }
        }
        if (cover != null && current.artwork == null) {
            val tmp = File(dir, "cover.tmp")
            try {
                tmp.writeBytes(cover)
                if (tmp.renameTo(File(dir, "${current.filename}.jpg"))) changes.update { it + 1 }
            } finally { tmp.delete() }
        }
    }

    @Synchronized
    fun remove(entry: OfflineFile) {
        val dir = folder(entry.video)
        val current = record(dir) ?: return
        if (current.filename != entry.filename) throw IOException("The saved file changed. Reopen Downloads to remove it.")
        if (!File(dir, current.filename).delete()) throw IOException("Could not remove this download.")
        File(dir, "entry.json").delete()
        File(dir, "${current.filename}.jpg").delete()
        dir.delete()
        transfers.update { it - entry.video.key }
        changes.update { it + 1 }
    }
}
