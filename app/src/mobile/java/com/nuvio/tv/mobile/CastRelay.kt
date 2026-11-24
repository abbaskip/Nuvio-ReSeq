package com.nuvio.tv.mobile

import android.content.Context
import com.nuvio.tv.livetv.player.LiveTvTimeshift
import com.nuvio.tv.livetv.player.REWIND_STARTUP_MS
import com.nuvio.tv.livetv.player.rewindPlaylistDurationMs
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

internal data class CastEndpoint(val url: String, val mime: String, val relay: CastRelay? = null,
    val transportStreamSegments: Boolean = false, val fragmentedMp4: Boolean = false)

/** The receiver can request only the selected media and resources named by its playlist. */
internal class CastRelay private constructor(private val source: CastSource, bind: String,
    private val receiver: InetAddress, private val shift: LiveTvTimeshift?, private val engineFile: Closeable?,
    private val selectedFile: File?) : NanoHTTPD(bind, 0), Closeable {
    private val token = "/" + UUID.randomUUID().toString() + "/"
    private val root = if (selectedFile == null) source.url.toHttpUrl() else null
    @Volatile var deliveryFailed = false
        private set
    @Volatile var receiverRequestedMedia = false
        private set
    @Volatile private var upstreamHttpError: Int? = null
    private var liveMedia: LiveCastMedia? = null
    private var liveService: Closeable? = null
    private val conversionError get() = liveMedia?.errorCode
    fun canRetryLiveFormat(receiverCode: Int?): Boolean {
        if (!receiverRequestedMedia || upstreamHttpError != null) return false
        val localError = conversionError
        if (localError != null) return localError in setOf(
            androidx.media3.transformer.ExportException.ERROR_CODE_MUXING_FAILED,
            androidx.media3.transformer.ExportException.ERROR_CODE_MUXING_TIMEOUT,
            androidx.media3.transformer.ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK)
        return classifyCastFailure(receiverCode, deliveryFailed) != CastFailure.DELIVERY
    }

    fun liveFailureDetail(): String {
        upstreamHttpError?.let { return "The IPTV provider returned HTTP $it to the phone." }
        conversionError?.let { return "The phone reported media conversion error $it." }
        return when {
            !receiverRequestedMedia -> "The phone received no media request from the selected TV."
            deliveryFailed -> "The TV reached the phone, but the phone could not deliver the stream."
            else -> "The TV requested media from the phone."
        }
    }
    private val paths = object : LinkedHashMap<String, HttpUrl>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HttpUrl>?) = size > 2048
    }
    private data class Segment(val base: HttpUrl, val pattern: String)
    private val segments = object : LinkedHashMap<String, Segment>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Segment>?) = size > 512
    }
    private fun segmentTemplate(base: HttpUrl, pattern: String): String {
        val slots = DASH_NUMBER.findAll(pattern).map { it.value }.toList()
        if (slots.isEmpty()) return resource(requireNotNull(base.resolve(pattern.replace("\$\$", "\$"))))
        val id = UUID.nameUUIDFromBytes((token + base + pattern).toByteArray()).toString()
        synchronized(segments) { segments[id] = Segment(base, pattern) }
        return token + id + "/" + slots.joinToString("/")
    }
    private fun lookup(path: String): HttpUrl? {
        if ('/' !in path) return synchronized(paths) { paths[path] }
        val parts = path.split('/')
        val entry = synchronized(segments) { segments[parts.first()] } ?: return null
        val values = parts.drop(1)
        if (values.any { it.length !in 1..22 || it.any { ch -> ch !in '0'..'9' } } ||
            DASH_NUMBER.findAll(entry.pattern).count() != values.size) return null
        val replacements = values.iterator()
        return entry.base.resolve(DASH_NUMBER.replace(entry.pattern) { replacements.next() }.replace("\$\$", "\$"))
    }
    private val client = OkHttpClient.Builder().apply {
        if (engineFile != null) readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
    }.followRedirects(false).dns { host ->
        Dns.SYSTEM.lookup(host).filter { address ->
            !address.isAnyLocalAddress && (!address.isLoopbackAddress || engineFile != null && host == root?.host) && !address.isLinkLocalAddress && !address.isMulticastAddress &&
                (host == root?.host || !address.isSiteLocalAddress && (address.address.size != 16 || address.address[0].toInt() and 0xfe != 0xfc))
        }.ifEmpty { throw java.net.UnknownHostException("Unreachable Cast media host") }
    }.build()
    private val localBase get() = HttpUrl.Builder().scheme("http").host(hostname).port(listeningPort).build()
    private fun resource(url: HttpUrl): String {
        val path = UUID.nameUUIDFromBytes((token + url).toByteArray()).toString()
        synchronized(paths) { paths[path] = url }
        return token + path
    }
    private fun providerHeaders(url: HttpUrl) = source.headers.filterKeys { key ->
        key.lowercase() !in setOf("host", "connection", "content-length", "range", "accept-encoding") &&
            (root != null && root.scheme == url.scheme && root.host == url.host && root.port == url.port || key.equals("User-Agent", true))
    }
    override fun serve(session: IHTTPSession): Response {
        if (!session.uri.startsWith(token) || session.remoteIpAddress.substringBefore('%') != receiver.hostAddress?.substringBefore('%'))
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        if (session.method == Method.GET || session.method == Method.HEAD) receiverRequestedMedia = true
        return try {
            val path = session.uri.removePrefix(token)
            when {
                session.method == Method.OPTIONS -> newFixedLengthResponse("")
                session.method !in setOf(Method.GET, Method.HEAD) -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "")
                liveMedia != null -> liveSegment(requireNotNull(liveMedia), path, session)
                selectedFile != null && path == "video" -> serveCastFile(selectedFile, requireNotNull(source.mime), session)
                selectedFile != null -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
                shift != null -> localSegment(path)
                else -> lookup(path)?.let { upstream(it, session) }
                    ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        } catch (_: Exception) {
            deliveryFailed = true
            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "")
        }.apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
            addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
            addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            addHeader("Access-Control-Allow-Private-Network", "true")
        }
    }
    private fun liveSegment(media: LiveCastMedia, path: String, session: IHTTPSession): Response {
        if (path == "index.m3u8") {
            media.start()
            runBlocking { withTimeout(23_000) { media.window.ready.await() } }
            check(media.errorCode == null)
            return newFixedLengthResponse(Response.Status.OK, HLS, media.window.playlist()).apply {
                addHeader("Cache-Control", "no-store")
            }
        }
        return media.window.read(path) { serveCastFile(it, "video/mp4", session) }
            ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
    }
    private fun localSegment(path: String): Response {
        require(path == "index.m3u8" || Regex("seg[0-9]+\\.ts").matches(path))
        val file = File(requireNotNull(shift?.session).dir, path)
        if (path == "index.m3u8") return newFixedLengthResponse(Response.Status.OK, HLS, file.readText()).apply {
            addHeader("Cache-Control", "no-store")
        }
        return newFixedLengthResponse(Response.Status.OK, "video/mp2t", file.inputStream(), file.length())
    }
    private fun upstream(start: HttpUrl, session: IHTTPSession): Response {
        var url = start
        repeat(6) {
            require(receiverUrl(url.toString()) || engineFile != null && url == root)
            val req = Request.Builder().url(url).method(session.method.name, null)
            providerHeaders(url).forEach { (k,v) -> req.header(k,v) }
            session.headers["range"]?.let { req.header("Range", it) }
            val response = client.newCall(req.build()).execute()
            if (response.isRedirect) {
                url = response.use { url.resolve(it.header("Location").orEmpty()) } ?: error("Invalid redirect")
                return@repeat
            }
            try {
                val body = requireNotNull(response.body)
                val mime = response.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream"
                if (response.code >= 400 && response.code != 416) {
                    deliveryFailed = true
                    upstreamHttpError = response.code
                }
                val status = Response.Status.lookup(response.code) ?: Response.Status.SERVICE_UNAVAILABLE
                if (session.method == Method.GET && response.isSuccessful && (mime.contains("dash+xml", true) || url.encodedPath.endsWith(".mpd", true))) {
                    response.use {
                        require(!body.source().request(4L * 1024 * 1024 + 1))
                        val manifest = rewriteCastDash(body.string(), url, ::resource, ::segmentTemplate)
                        return newFixedLengthResponse(status, "application/dash+xml", manifest)
                    }
                }
                if (session.method == Method.GET && response.isSuccessful && (mime.contains("mpegurl", true) ||
                        url.encodedPath.endsWith(".m3u8", true) || start == root && source.mime?.contains("mpegurl", true) == true)) {
                    response.use {
                        require(!body.source().request(4L * 1024 * 1024 + 1))
                        fun child(path: String) = resource(requireNotNull(url.resolve(path)).also { require(receiverUrl(it.toString())) })
                        val lines = body.string().lineSequence().map { line ->
                            when {
                                line.startsWith("#") -> URI_ATTRIBUTE.replace(line) { "URI=\"" + child(it.groupValues[1]) + "\"" }
                                line.isBlank() -> line
                                else -> child(line.trim())
                            }
                        }.joinToString("\n")
                        return newFixedLengthResponse(status, HLS, lines)
                    }
                }
                val length = if (session.method == Method.HEAD) response.header("Content-Length")?.toLongOrNull() ?: 0L
                    else body.contentLength()
                val stream = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(buffer: ByteArray, off: Int, len: Int): Int = try {
                        super.read(buffer, off, len)
                    } catch (error: java.io.IOException) {
                        deliveryFailed = true
                        throw error
                    }
                }
                val outgoing = if (length >= 0) newFixedLengthResponse(status, mime, stream, length)
                    else newChunkedResponse(status, mime, stream)
                for (key in listOf("Accept-Ranges", "Content-Range")) response.header(key)?.let { outgoing.addHeader(key, it) }
                return outgoing
            } catch (e: Exception) { response.close(); throw e }
        }
        error("Too many redirects")
    }
    override fun close() {
        liveMedia?.close()
        liveService?.close()
        client.dispatcher.cancelAll()
        stop()
        shift?.stop()
        engineFile?.close()
        client.connectionPool.evictAll()
    }
    companion object {
        private const val HLS = "application/x-mpegURL"
        private val URI_ATTRIBUTE = Regex("URI=\"([^\"]+)\"")
        private val DASH_NUMBER = Regex("""\$(?:Number|Time|SubNumber)(?:%0?[0-9]+d)?\$""")
        suspend fun prepare(context: Context, source: CastSource, receiver: InetAddress?, engineFile: Closeable? = null,
            selectedFile: File? = null, forceRelay: Boolean = false, liveFormat: LiveCastFormat? = null): CastEndpoint {
            val headers = if (source.fallbackUserAgent != null && source.headers.keys.none { it.equals("User-Agent", true) })
                source.headers + ("User-Agent" to source.fallbackUserAgent) else source.headers
            val mime = source.mime ?: PlayerMediaSourceFactory.inferMimeType(source.url, null)
                ?: if (source.live) PlayerMediaSourceFactory.probeNetworkMimeType(source.url, headers)
                    ?: error("Unknown live stream format") else "video/mp4"
            if (liveFormat == null && !forceRelay && source.headers.isEmpty() && engineFile == null && selectedFile == null) return CastEndpoint(source.url, mime)
            val ts = liveFormat == null && source.live && mime.equals("video/mp2t", true)
            val bind = try { withContext(Dispatchers.IO) {
                requireNotNull(receiver)
                DatagramSocket().use { socket -> socket.connect(receiver, 8009); requireNotNull(socket.localAddress.hostAddress) }
            } } catch (e: Exception) { engineFile?.close(); throw e }
            val shift = if (ts) LiveTvTimeshift(context) else null
            val server = CastRelay(source.copy(mime = mime, headers = headers), bind, requireNotNull(receiver), shift, engineFile, selectedFile)
            try {
                if (liveFormat != null) {
                    check(source.live && source.iptv)
                    server.liveService = PhoneCastService.acquire(context)
                    val inputClient = server.client.newBuilder().followRedirects(true).addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val scoped = request.newBuilder()
                        // OkHttp may carry application headers onto a redirected request.
                        // Reapply provider-supplied values only for their original origin.
                        server.providerHeaders(requireNotNull(server.root)).keys.forEach(scoped::removeHeader)
                        server.providerHeaders(request.url).forEach { (key, value) -> scoped.header(key, value) }
                        val response = chain.proceed(scoped.build())
                        if (response.code >= 400 && response.code != 416) server.upstreamHttpError = response.code
                        response
                    }.build()
                    server.liveMedia = LiveCastMedia(context, source.copy(mime = mime), liveFormat, inputClient)
                }
                val path = withContext(Dispatchers.IO) {
                    server.start(SOCKET_READ_TIMEOUT, true)
                    if (liveFormat != null) return@withContext server.token + "index.m3u8"
                    if (selectedFile != null) return@withContext server.token + "video"
                    if (shift == null) return@withContext server.resource(requireNotNull(server.root))
                    val buffer = requireNotNull(shift.start(source.url, headers, keepMinutes = 1, label = "Chromecast", kind = LiveTvTimeshift.Kind.TS))
                    val ready = withTimeoutOrNull(25_000) {
                        val playlist = File(buffer.dir, "index.m3u8")
                        while (!playlist.isFile || rewindPlaylistDurationMs(playlist.readText()) < REWIND_STARTUP_MS) delay(250)
                        true
                    }
                    check(ready == true) { "Live channel buffer did not start" }
                    server.token + "index.m3u8"
                }
                return CastEndpoint(requireNotNull(server.localBase.resolve(path)).toString(), if (ts || liveFormat != null) HLS else mime,
                    server, ts, fragmentedMp4 = liveFormat != null)
            } catch (e: Exception) { server.close(); throw e }
        }
    }
}
