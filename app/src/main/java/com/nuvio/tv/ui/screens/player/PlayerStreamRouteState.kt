package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import com.nuvio.tv.core.usenet.UsenetPlayback
import com.nuvio.tv.core.usenet.UsenetHttpSession
import com.nuvio.tv.domain.model.UsenetSource
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.tvdbPlaybackContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.json.JSONArray

/**
 * Player route arguments for [stream], in the form [PlayerNavigationArgs.from] reads them back:
 * keys it URL-decodes a second time are stored encoded, the others raw. Mirrors the arguments a
 * normal launch passes (see `StreamScreenViewModel.getStreamForPlayback`).
 */
internal fun playerStreamRouteValues(
    stream: Stream,
    streamUrl: String,
    headers: Map<String, String>,
    videoId: String?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    tvdbContextJson: String? = null
): Map<String, String> {
    fun encoded(value: String?): String =
        value?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""

    val hints = stream.behaviorHints
    return mapOf(
        "streamUrl" to streamUrl,
        "headers" to encoded(
            headers.takeIf { it.isNotEmpty() }
                ?.let { map -> JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString() }
        ),
        "videoId" to videoId.orEmpty(),
        "season" to season?.toString().orEmpty(),
        "episode" to episode?.toString().orEmpty(),
        "episodeTitle" to encoded(episodeTitle),
        "streamName" to encoded(stream.name ?: stream.addonName),
        "bingeGroup" to hints?.bingeGroup.orEmpty(),
        "filename" to encoded(hints?.filename),
        "videoHash" to hints?.videoHash.orEmpty(),
        "videoSize" to hints?.videoSize?.toString().orEmpty(),
        "addonName" to encoded(stream.addonName),
        "addonLogo" to encoded(stream.addonLogo),
        "streamDescription" to encoded(stream.description),
        "infoHash" to stream.getEffectiveInfoHash().orEmpty(),
        "fileIdx" to stream.getEffectiveFileIdx()?.toString().orEmpty(),
        "sources" to encoded(
            stream.sources?.let { sources -> JsonArray(sources.map(::JsonPrimitive)).toString() }
        ),
        "audioDelayMs" to hints?.audioDelayMs?.toString().orEmpty(),
        "audioDelayLanguage" to encoded(hints?.audioDelayLanguage),
        // The new stream resumes from its saved progress, whatever the first one was opened with.
        "startFromBeginning" to "false",
        "tvdbContext" to encoded(tvdbContextJson),
        "fromLinkCache" to "false"
    )
}

/**
 * Points the player route at [stream] after an in-player episode or source switch. The player
 * switches in place, so without this a player restored after process death (TV standby, a long
 * screensaver) reopens the stream it was first launched with, usually an earlier episode.
 */
internal fun PlayerRuntimeController.persistStreamRouteState(stream: Stream, isTorrent: Boolean) {
    // TV Mix owns its session and route; a transient selection must not replace it.
    if (isMixPlayback) return
    val selectedContext = currentTvdbContext() ?: metaVideos.singleOrNull {
        it.id == currentVideoId && it.season == currentSeason && it.episode == currentEpisode
    }?.tvdbPlaybackContext()
    val values = playerStreamRouteValues(
        stream = stream,
        // Same form the stream screen passes; the startup turns it back into a torrent session.
        streamUrl = if (isTorrent) "torrent://${stream.getEffectiveInfoHash()}" else currentStreamUrl,
        headers = if (isTorrent) emptyMap() else currentHeaders,
        videoId = currentVideoId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        tvdbContextJson = if (navigationArgs.tvdbContextJson == "provider") "provider" else selectedContext?.encode()
    )
    values.forEach { (key, value) -> savedStateHandle[key] = value }
    rememberUsenetRestoreSource(currentStreamUrl)
}

private const val USENET_RESTORE_KEY = "reseqUsenetRestore"

/** Store the original descriptor in the app-private state, never in a URL or a log. */
internal fun PlayerRuntimeController.rememberUsenetRestoreSource(url: String) {
    if (isMixPlayback) return
    if (!UsenetHttpSession.isSessionUrl(url)) {
        savedStateHandle.remove<String>(USENET_RESTORE_KEY)
        return
    }
    val original = UsenetPlayback.originalStream(url) ?: return
    val source = original.usenet ?: return
    savedStateHandle[USENET_RESTORE_KEY] = JSONObject().apply {
        put("url", url); put("profile", profileId); put("video", currentVideoId)
        put("nzb", source.nzbUrl); put("servers", JSONArray(source.servers)); put("filters", JSONArray(source.filters))
        put("headers", JSONObject(original.behaviorHints?.proxyHeaders?.request.orEmpty()))
        original.fileIdx?.let { put("fileIdx", it) }
    }.toString()
}

/** A restored NNTP loopback URL belongs to a dead process; re-prepare its original source. */
internal fun PlayerRuntimeController.restoreUsenetSessionIfNeeded(url: String, startPaused: Boolean): Boolean {
    if (!UsenetHttpSession.isSessionUrl(url) || UsenetPlayback.hasSession(url) || isMixPlayback) return false
    val descriptor = runCatching { JSONObject(savedStateHandle.get<String>(USENET_RESTORE_KEY) ?: "") }.getOrNull()
    if (descriptor == null || descriptor.optString("url") != url || descriptor.optInt("profile", -1) != profileId ||
        descriptor.optString("video") != currentVideoId.orEmpty()) {
        _uiState.update { it.copy(error = "This Usenet session has ended. Choose the stream again.", showLoadingOverlay = false) }
        return true
    }
    fun strings(key: String): List<String> = descriptor.getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }
    val request = descriptor.optJSONObject("headers")
    val headers = request?.keys()?.asSequence()?.associateWith(request::getString).orEmpty()
    val source = Stream(name = streamName, title = currentEpisodeTitle, description = null, url = null,
        ytId = null, infoHash = null, fileIdx = descriptor.takeIf { it.has("fileIdx") }?.getInt("fileIdx"),
        externalUrl = null, behaviorHints = StreamBehaviorHints(null, currentStreamBingeGroup, null, ProxyHeaders(headers, null)),
        addonName = currentAddonName.orEmpty(), addonLogo = null,
        usenet = UsenetSource(descriptor.getString("nzb"), strings("servers"), strings("filters")))
    usenetPrepareJob?.cancel()
    usenetPrepareJob = scope.launch {
        try {
            val restored = UsenetPlayback.resolve(source, currentVideoId, currentSeason, currentEpisode)
            ensureActive()
            if (currentStreamUrl != url) { restored.url?.let(UsenetPlayback::release); return@launch }
            currentStreamUrl = requireNotNull(restored.url)
            currentHeaders = emptyMap()
            currentFilename = restored.behaviorHints?.filename ?: currentFilename
            savedStateHandle["streamUrl"] = currentStreamUrl
            rememberUsenetRestoreSource(currentStreamUrl)
            _uiState.update { it.copy(currentStreamUrl = currentStreamUrl) }
            usenetPrepareJob = null
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = startPaused)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (currentStreamUrl == url) _uiState.update { it.copy(error = error.message, showLoadingOverlay = false) }
        }
    }
    return true
}
