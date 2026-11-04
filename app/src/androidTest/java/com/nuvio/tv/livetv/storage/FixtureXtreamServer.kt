package com.nuvio.tv.livetv.storage

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic provider on the Onn itself. No real IPTV account, history or Google account involved. */
internal class FixtureXtreamServer : NanoHTTPD("127.0.0.1", 0), AutoCloseable {
    val requests = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile var failSeries = false
    @Volatile var mediaFile: java.io.File? = null
    val base get() = "http://127.0.0.1:$listeningPort"

    init { start(SOCKET_READ_TIMEOUT, false) }
    override fun close() = stop()
    override fun serve(session: IHTTPSession): Response {
        mediaFile?.takeIf { session.uri.startsWith("/series/") || session.uri.startsWith("/movie/") }?.let { file ->
            val range = session.headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            val start = range.coerceIn(0, file.length())
            val stream = file.inputStream().apply { skip(start) }
            val response = newFixedLengthResponse(if (start == 0L) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                "video/mp4", stream, file.length() - start)
            response.addHeader("Accept-Ranges", "bytes")
            if (start > 0) response.addHeader("Content-Range", "bytes $start-${file.length() - 1}/${file.length()}")
            return response
        }
        val action = session.parameters["action"]?.firstOrNull().orEmpty()
        requests.computeIfAbsent(action) { AtomicInteger() }.incrementAndGet()
        if (failSeries && action in listOf("get_series", "get_series_categories"))
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Synthetic provider failure")
        val selected = session.parameters["category_id"]?.firstOrNull()
        val body: Any = when (action) {
            "get_vod_categories", "get_series_categories" -> JSONArray().put(category("en", "English")).put(category("fr", "French"))
            "get_series" -> JSONArray().apply {
                listOf("201" to "en", "202" to "fr", "203" to "en").filter { selected == null || selected == it.second }.forEach { (id, category) ->
                    put(JSONObject().put("series_id", id).put("name", if (id == "203") "Friends (2021) (en)" else "Friends ($category)")
                        // English intentionally lacks an import-time ID: lookup must clean its suffix.
                        .put("category_id", category).put("tmdb", when (id) { "201" -> JSONObject.NULL; "203" -> "99999"; else -> "1668" })
                        .put("year", if (id == "203") "2021" else "1994").put("cover", "$base/artwork/$id.png").put("rating", "8.5"))
                }
            }
            "get_vod_streams" -> JSONArray().apply {
                if (selected == null || selected == "en") put(JSONObject().put("stream_id", "101").put("name", "Synthetic Movie (en)")
                    .put("category_id", "en").put("tmdb", "12345").put("year", "2001").put("container_extension", "mp4")
                    .put("stream_icon", "$base/artwork/101.png"))
            }
            "get_series_info" -> {
                val id = session.parameters["series_id"]?.firstOrNull() ?: "201"
                val language = if (id == "202") "fr" else "en"
                JSONObject().put("info", JSONObject().put("tmdb_id", if (id == "203") "99999" else "1668")
                    .put("releaseDate", if (id == "203") "2021-01-01" else "1994-09-22"))
                    .put("episodes", JSONObject().put("1", JSONArray().put(JSONObject().put("id", id + "01")
                        .put("container_extension", "mp4").put("season", 1).put("episode_num", 1)
                        .put("title", "Friends ($language) S01E01"))))
            }
            "get_vod_info" -> JSONObject().put("info", JSONObject().put("tmdb_id", "12345").put("releaseDate", "2001-01-01"))
            else -> if (session.uri.startsWith("/artwork/")) {
                val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlS8AAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
                return newFixedLengthResponse(Response.Status.OK, "image/png", png.inputStream(), png.size.toLong())
            } else JSONObject()
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
    }

    private fun category(id: String, name: String) = JSONObject().put("category_id", id).put("category_name", name)
}
