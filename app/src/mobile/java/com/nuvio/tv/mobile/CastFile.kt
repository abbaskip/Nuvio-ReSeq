package com.nuvio.tv.mobile

import com.nuvio.tv.core.usenet.UsenetHttpSession
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File

/** The caller has already matched the receiver, session token and the single published file. */
internal fun serveCastFile(file: File, mime: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
    val length = file.length()
    val range = session.headers["range"]
    val bounds = if (range == null) 0L until length else UsenetHttpSession.parseRange(range, length)
    if (bounds == null) return NanoHTTPD.newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "").apply {
        addHeader("Content-Range", "bytes */$length")
    }
    val stream = if (session.method == NanoHTTPD.Method.HEAD) ByteArray(0).inputStream() else file.inputStream().also {
        try { it.channel.position(bounds.first) } catch (e: Exception) { it.close(); throw e }
    }
    return NanoHTTPD.newFixedLengthResponse(if (range == null) Status.OK else Status.PARTIAL_CONTENT,
        mime, stream, bounds.last - bounds.first + 1).apply {
        addHeader("Accept-Ranges", "bytes")
        if (range != null) addHeader("Content-Range", "bytes ${bounds.first}-${bounds.last}/$length")
    }
}
