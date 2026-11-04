package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** MAG protocol interoperability informed by IPTVnator (MIT); no portal session leaves this process. */
@Singleton
class StalkerPortalClient internal constructor(private val http: OkHttpClient, private val now: () -> Long,
    private val parserDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    @Inject constructor() : this(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).build(), System::currentTimeMillis, BackgroundWork.quiet)

    private val bulkHttp = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()

    private data class Session(val token: String, val expires: Long)
    private val endpoints = ConcurrentHashMap<String, String>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    data class Link(val url: String, val headers: Map<String, String>)

    private fun identity(source: PlaylistSource): String = MessageDigest.getInstance("SHA-256").digest(
        listOf(endpoint(source.stalkerPortal), source.stalkerMac.uppercase(), source.stalkerUsername,
            source.stalkerPassword, source.stalkerSerial).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun session(source: PlaylistSource, key: String, expiredToken: String? = null): Session =
        locks.getOrPut(key) { Mutex() }.withLock {
            if (expiredToken != null && sessions[key]?.token == expiredToken) sessions.remove(key)
            sessions[key]?.takeIf { it.expires > now() } ?: authenticate(source).also { sessions[key] = it }
        }

    private suspend fun request(source: PlaylistSource, type: String, action: String,
        parameters: Map<String, String> = emptyMap()): Any =
        request(source, type, action, parameters) { response -> readResponse(response, action) }

    private suspend fun <T> request(source: PlaylistSource, type: String, action: String,
        parameters: Map<String, String> = emptyMap(), consume: (Response) -> T): T {
        val key = identity(source)
        val current = session(source, key)
        return try { call(source, type, action, parameters, current.token, consume) }
        catch (_: PortalSessionExpired) {
            val renewed = session(source, key, current.token)
            call(source, type, action, parameters, renewed.token, consume)
        }
    }

    private suspend fun authenticate(source: PlaylistSource): Session {
        require(MAC.matches(source.stalkerMac)) { "Enter the MAC address supplied by your provider." }
        val handshake = call(source, "stb", "handshake", mapOf("token" to "", "prehash" to digest(source.stalkerMac.uppercase()).uppercase()), null) as? JSONObject
        val token = handshake?.optString("token")?.takeIf { it.isNotBlank() }
            ?: error("The portal did not authorise this MAC address.")
        val params = linkedMapOf(
            "ver" to "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.6.0; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566",
            "hw_version" to "1.7-BD-00", "image_version" to "218", "client_type" to "STB", "stb_type" to "MAG250", "hd" to "1", "video_out" to "hdmi", "num_banks" to "2",
            "prehash" to digest(source.stalkerMac.uppercase()).uppercase(), "auth_second_step" to "0", "not_valid_token" to handshake.optString("not_valid", "0"),
            "metrics" to JSONObject().put("mac", source.stalkerMac).put("model", "MAG250").put("type", "STB")
                .put("random", handshake.optString("random")).toString())
        if (source.stalkerSerial.isNotBlank()) params["sn"] = source.stalkerSerial
        var profile = call(source, "stb", "get_profile", params, token) as? JSONObject ?: error("Invalid portal profile.")
        if (profile.optInt("status") == 2) {
            check(source.stalkerUsername.isNotBlank() && source.stalkerPassword.isNotBlank()) { "This portal also requires a username and password." }
            val auth = call(source, "stb", "do_auth", mapOf("login" to source.stalkerUsername, "password" to source.stalkerPassword), token)
            check(auth == true || auth == 1) { "Portal login failed." }
            params["auth_second_step"] = "1"
            profile = call(source, "stb", "get_profile", params, token) as? JSONObject ?: error("Invalid portal profile.")
        }
        check(profile.optInt("status") !in 1..2 && profile.optInt("blocked") != 1) { "The portal has blocked or declined this account." }
        return Session(token, now() + TimeUnit.MINUTES.toMillis(30))
    }

    private suspend fun call(source: PlaylistSource, type: String, action: String, parameters: Map<String, String>, token: String?): Any {
        return call(source, type, action, parameters, token) { response -> readResponse(response, action) }
    }

    private suspend fun <T> call(source: PlaylistSource, type: String, action: String,
        parameters: Map<String, String>, token: String?, consume: (Response) -> T): T {
        val configured = endpoint(source.stalkerPortal)
        val candidates = endpoints[source.stalkerPortal]?.let(::listOf) ?: buildList {
            add(configured)
            if (!source.stalkerPortal.trimEnd('/').endsWith(".php", true)) {
                val parsed = configured.toHttpUrl()
                add(parsed.newBuilder().encodedPath(parsed.encodedPath.removeSuffix("/server/load.php") + "/portal.php").build().toString())
            }
        }.distinct()
        for (candidate in candidates) {
            val url = candidate.toHttpUrl().newBuilder()
                .addQueryParameter("type", type).addQueryParameter("action", action)
                .addQueryParameter("JsHttpRequest", "1-xml").apply {
                    parameters.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build()
            val request = Request.Builder().url(url).header("User-Agent", source.userAgent.ifBlank { USER_AGENT })
                .header("X-User-Agent", "Model: MAG250; Link: WiFi")
                .header("Cookie", "mac=${encode(source.stalkerMac.uppercase())}; stb_lang=en; timezone=UTC")
                .header("Referer", source.stalkerPortal.trimEnd('/') + "/")
                .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
            val client = if (action == "get_all_channels" || action == "get_epg_info") bulkHttp else http
            val result = exchange(client.newCall(request), if (client === bulkHttp) parserDispatcher else Dispatchers.IO, consume)
            if (result != null) {
                endpoints[source.stalkerPortal] = candidate
                return result
            }
            endpoints.remove(source.stalkerPortal)

        }
        error("Portal endpoint not found. Check the URL supplied by your provider.")
    }

    // Keep cancellation connected to the socket until the body has finished parsing, including
    // a large guide. Bulk JSON is consumed one channel/programme at a time on a worker.
    private suspend fun <T> exchange(call: Call, dispatcher: CoroutineDispatcher, consume: (Response) -> T): T? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                dispatcher.dispatch(continuation.context, Runnable {
                    try {
                        val result = response.use {
                            if (!continuation.isActive || it.code == 404) null
                            else {
                                if (it.code == 401 || it.code == 403) throw PortalSessionExpired()
                                check(it.isSuccessful) { "Portal request failed (HTTP ${it.code})." }
                                consume(it)
                            }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                })
            }
        })
    }

    private fun readResponse(response: Response, action: String): Any {
        val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            ?: error("The server did not return a Stalker portal response.")
        val result = body.opt("js")
        if (result is JSONObject) checkError(result.optString("error"))
        if (result == false && action !in setOf("handshake", "get_profile", "do_auth")) throw PortalSessionExpired()
        return result?.takeUnless { it == JSONObject.NULL } ?: error("The portal returned no data.")
    }

    private fun checkError(error: String) {
        if (error.contains("auth", true) || error.contains("token", true)) throw PortalSessionExpired()
    }

    private fun readResult(response: Response, consume: (JsonReader) -> Unit) {
        JsonReader(response.body?.charStream() ?: error("Empty portal response.")).use { reader ->
            reader.beginObject()
            var found = false
            while (reader.hasNext()) {
                if (reader.nextName() == "js") {
                    check(!found) { "Invalid portal response." }
                    found = true
                    if (reader.peek() == JsonToken.BOOLEAN && !reader.nextBoolean()) throw PortalSessionExpired()
                    consume(reader)
                } else reader.skipValue()
            }
            reader.endObject()
            check(found && reader.peek() == JsonToken.END_DOCUMENT) { "Invalid portal response." }
        }
    }

    private fun readChannelArray(reader: JsonReader, consume: (JSONObject) -> Unit) {
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) consume(JSONObject(JsonParser.parseReader(reader).toString()))
                    else reader.skipValue()
                }
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "data" -> { check(reader.peek() == JsonToken.BEGIN_ARRAY) { "Invalid portal channels." }; readChannelArray(reader, consume) }
                        "error" -> checkError(reader.nextString())
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            else -> error("Invalid portal channels.")
        }
    }

    suspend fun writePlaylist(source: PlaylistSource, target: File) = withContext(Dispatchers.IO) {
        val genres = request(source, "itv", "get_genres") as? JSONArray ?: error("Invalid portal categories.")
        val names = (0 until genres.length()).mapNotNull { genres.optJSONObject(it) }
            .associate { it.optString("id") to it.optString("title") }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            request(source, "itv", "get_all_channels") { response ->
                try {
                    var count = 0
                    temp.bufferedWriter().use { out ->
                        out.appendLine("#EXTM3U")
                        out.appendLine(PLAYLIST_FORMAT)
                        readResult(response) { reader -> readChannelArray(reader) { channel ->
                            val id = channel.optString("id").takeIf { it.isNotBlank() }
                            val command = sequenceOf("cmd", "mc_cmd", "url")
                                .map { channel.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
                            if (id != null && command != null) {
                                val name = clean(channel.optString("name"))
                                val group = clean(names[channel.optString("tv_genre_id")] ?: "Uncategorized")
                                val logo = clean(channel.optString("logo"))
                                out.appendLine("#EXTINF:-1 tvg-id=\"${epgId(source.id, id)}\" tvg-logo=\"$logo\" group-title=\"$group\" tvg-chno=\"${channel.optInt("number", count + 1)}\",$name")
                                // Only explicitly static channels may bypass create_link. Old cached
                                // markers and channels without these fields still resolve at play time.
                                val resolve = resolutionMode(channel)
                                out.appendLine("reseq-stalker://${source.id}/$id?cmd=${encode(command)}&resolve=$resolve")
                                count++
                            }
                        } }
                    }
                    check(count > 0) { "The portal returned no live channels." }
                } catch (error: Exception) { temp.delete(); throw error }
            }
            temp.copyTo(target, overwrite = true)
        } finally { temp.delete() }
    }

    /** Old imports discarded temporary-link flags; refresh them once after upgrading. */
    internal fun needsPlaylistRefresh(file: File): Boolean = runCatching {
        file.inputStream().buffered().use { input ->
            input.mark(2)
            val zipped = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            val decoded = if (zipped) GZIPInputStream(input) else input
            decoded.bufferedReader().use { it.readLine(); it.readLine() != PLAYLIST_FORMAT }
        }
    }.getOrDefault(true)

    suspend fun writeGuide(source: PlaylistSource, target: File) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            request(source, "itv", "get_epg_info", mapOf("period" to "168")) { response ->
                try {
                    var count = 0
                    temp.bufferedWriter().use { out ->
                        out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
                        fun readChannels(reader: JsonReader) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val id = reader.nextName()
                                when {
                                    id == "data" && reader.peek() == JsonToken.BEGIN_OBJECT -> readChannels(reader)
                                    id == "error" -> checkError(reader.nextString())
                                    reader.peek() == JsonToken.BEGIN_ARRAY -> {
                                        val channel = xml(epgId(source.id, id))
                                        out.appendLine("<channel id=\"$channel\"><display-name>${xml(id)}</display-name></channel>")
                                        readChannelArray(reader) { p ->
                                            val start = timestamp(p, "start_timestamp", "time")
                                            val stop = timestamp(p, "stop_timestamp", "time_to")
                                            if (start != null && stop != null && stop > start) {
                                                out.appendLine("<programme channel=\"$channel\" start=\"${date(start)}\" stop=\"${date(stop)}\"><title>${xml(p.optString("name"))}</title><desc>${xml(p.optString("descr"))}</desc></programme>")
                                                count++
                                            }
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        readResult(response, ::readChannels)
                        out.appendLine("</tv>")
                    }
                    check(count > 0) { "The portal returned no guide listings." }
                } catch (error: Exception) { temp.delete(); throw error }
            }
            temp.copyTo(target, overwrite = true)
        } finally { temp.delete() }
    }

    suspend fun resolve(source: PlaylistSource, marker: String, refreshCommand: Boolean = false): Link {
        val uri = marker.toHttpMarker()
        check(uri.host == source.id) { "The channel belongs to a different playlist." }
        if (refreshCommand) {
            // Direct URLs may contain play tokens minted during an old playlist import.
            // Re-list the exact channel instead of sending its ready-to-play URL to
            // create_link, which some panels turn into an empty-stream URL.
            val id = uri.pathSegments.singleOrNull()?.takeIf { it.isNotBlank() }
                ?: error("Missing portal channel identifier.")
            var fresh: JSONObject? = null
            request(source, "itv", "get_all_channels") { response ->
                readResult(response) { reader -> readChannelArray(reader) { channel ->
                    if (channel.optString("id") == id) {
                        check(fresh == null) { "The portal returned an ambiguous channel identifier." }
                        fresh = channel
                    }
                } }
            }
            val channel = fresh ?: error("The portal no longer lists this channel. Refresh the playlist.")
            val command = sequenceOf("cmd", "mc_cmd", "url").map { channel.optString(it) }
                .firstOrNull { it.isNotBlank() && it != "null" } ?: error("The portal returned no channel command.")
            val mode = resolutionMode(channel)
            LiveTvLoadReport.add("Stalker: channel command refreshed from the portal")
            return resolve(source, "reseq-stalker://${source.id}/$id?cmd=${encode(command)}&resolve=$mode")
        }
        val command = uri.queryParameter("cmd")?.takeIf { it.isNotBlank() } ?: error("Missing portal channel command.")
        fun link(url: String) = Link(url, mapOf("User-Agent" to source.userAgent.ifBlank { USER_AGENT }))
        if (uri.queryParameter("resolve") == "0") {
            val direct = playbackUrl(command)
                ?: runCatching { playbackUrl(URLDecoder.decode(command, "UTF-8")) }.getOrNull()
            if (direct != null) {
                LiveTvLoadReport.add("Stalker: direct channel playback selected")
                return link(direct)
            }
        }

        suspend fun create(cmd: String, legacyStorage: Boolean): String? {
            val parameters = linkedMapOf("cmd" to cmd, "series" to "", "disable_ad" to "0")
            if (legacyStorage) parameters["forced_storage"] = "0"
            val data = request(source, "itv", "create_link", parameters) as? JSONObject ?: return null
            return sequenceOf("cmd", "url", "stream_url").mapNotNull { playbackUrl(data.optString(it)) }.firstOrNull()
        }
        create(command, legacyStorage = true)?.let { return link(it) }
        LiveTvLoadReport.add("Stalker: incomplete playback link; retrying with MAG command decoding and default storage")

        // Some panels store percent-encoded commands and expect the single form decode
        // performed by their MAG client. Normal query encoding otherwise preserves those
        // escapes, and create_link can return HTTP 200 with an empty stream identifier.
        // Retry once with that interpretation and the portal's default storage selection.
        // Query parameters are still encoded by OkHttp: embedded '&' cannot inject fields.
        val compatible = runCatching { URLDecoder.decode(command, "UTF-8") }.getOrDefault(command)
        create(compatible, legacyStorage = false)?.let {
            LiveTvLoadReport.add("Stalker: compatibility playback link resolved")
            return link(it)
        }
        LiveTvLoadReport.add("Stalker: portal returned no usable playback link after compatibility retry")
        error("The portal returned an incomplete playback link. Refresh the playlist or check the portal account.")
    }

    private fun playbackUrl(command: String): String? {
        val link = command.trim().replaceFirst(Regex("^(?:ffmpeg|ffrt[0-9]*|auto)\\s+", RegexOption.IGNORE_CASE), "").trim()
        val url = runCatching { link.toHttpUrl() }.getOrNull() ?: return null
        val host = url.host.lowercase().trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "::1" || host == "0.0.0.0") return null
        // Some portals put their own host in this placeholder instead of localhost.
        if (Regex("/ch/[0-9]+_$").containsMatchIn(url.encodedPath)) return null
        // Only reject a stream parameter when present. Many valid portals use path IDs.
        if (url.queryParameterValues("stream").any { it.isNullOrBlank() || it.equals("null", true) }) return null
        return link
    }

    private fun resolutionMode(channel: JSONObject): String {
        val temporary = listOf("use_http_tmp_link", "use_load_balancing", "wowza_tmp_link", "flussonic_tmp_link")
            .map { channel.optString(it).trim() }
        return when {
            temporary.any { it == "1" || it.equals("true", true) } -> "1"
            temporary.first().let { it == "0" || it.equals("false", true) } -> "0"
            else -> ""
        }
    }

    private class PortalSessionExpired : IllegalStateException("Portal session expired. Reconnect or check your account.")
    companion object {
        private const val PLAYLIST_FORMAT = "#RESEQ-STALKER-FORMAT:2"
        fun validate(portal: String, mac: String): String? = when {
            runCatching { endpoint(portal) }.isFailure -> "Enter a valid HTTP or HTTPS portal URL without embedded credentials."
            !MAC.matches(mac.trim()) -> "Enter the six-pair MAC address supplied by your provider."
            else -> null
        }

        private val MAC = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
        const val USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3"
        fun endpoint(raw: String): String {
            val url = raw.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Enter a portal URL without embedded credentials." }
            val path = url.encodedPath.trimEnd('/')
            val root = path.removeSuffix("/index.html").removeSuffix("/c")
            val endpoint = if (root.endsWith(".php")) root else "$root/server/load.php"
            return url.newBuilder().encodedPath(endpoint).build().toString()
        }
        private fun String.toHttpMarker() = replaceFirst("reseq-stalker://", "http://").toHttpUrl()
        private fun epgId(playlist: String, channel: String) = "stalker:$playlist:$channel"
        private fun clean(value: String) = value.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')
        private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun digest(value: String) = MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun timestamp(value: JSONObject, key: String, fallback: String): Long? = value.optString(key).toLongOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(value.optString(fallback).replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
        private val guideDateFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        private fun date(seconds: Long) = guideDateFormat.format(Instant.ofEpochSecond(seconds))
    }
}
