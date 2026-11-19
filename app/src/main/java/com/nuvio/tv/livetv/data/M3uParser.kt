package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.CatchupInfo
import java.io.BufferedReader

/** Raw channel entry as read from a playlist, before numbering and keying. */
data class M3uEntry(
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val chno: Int?,
    val url: String,
    val headers: Map<String, String>,
    val catchup: CatchupInfo?,
    val drm: com.nuvio.tv.livetv.model.DrmInfo? = null
)

data class M3uPlaylist(
    val epgUrls: List<String>,
    val entries: List<M3uEntry>
)

/**
 * Streaming M3U / M3U8 (extended) parser. Handles the attributes IPTV providers
 * actually use: tvg-id, tvg-name, tvg-logo, group-title, tvg-chno, catchup*,
 * #EXTGRP, #EXTVLCOPT user agent / referrer and Kodi style "url|User-Agent=..".
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
    private val attrRegexUnquoted = Regex("""([A-Za-z0-9_\-]+)\s*=\s*([^\s",]+)""")

    /** With [onEntry], entries are delivered immediately and the returned entries list is empty. */
    fun parse(reader: BufferedReader, onEntry: ((M3uEntry) -> Unit)? = null): M3uPlaylist =
        parse(reader, { true }, onEntry)

    fun parse(reader: BufferedReader, acceptUrl: (String) -> Boolean, onEntry: ((M3uEntry) -> Unit)?): M3uPlaylist =
        parseChecked(reader, acceptUrl, {}, onEntry)

    internal inline fun parseChecked(reader: BufferedReader, acceptUrl: (String) -> Boolean, checkpoint: () -> Unit,
        noinline onEntry: ((M3uEntry) -> Unit)?): M3uPlaylist {
        var seen = 0
        val epgUrls = mutableListOf<String>()
        val entries = ArrayList<M3uEntry>(if (onEntry == null) 4096 else 0)

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        val directives = ArrayList<String>(4)
        // Catch-up set once for the whole playlist on its #EXTM3U line (TiviMate supports this):
        // used for every channel that doesn't set its own.
        val headerCatchup = mutableMapOf<String, String>()

        reader.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    listOf("catchup", "catchup-type", "catchup-source", "catchup-days", "catchup-correction", "tvg-rec", "timeshift")
                        .forEach { k -> attrs[k]?.takeIf { it.isNotBlank() }?.let { headerCatchup[k] = it } }
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        attrs[key]?.split(',')?.map { it.trim() }?.filter { it.startsWith("http") }
                            ?.let { epgUrls.addAll(it) }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                    // Header lines (#EXTVLCOPT, #EXTHTTP) can come before or after #EXTINF; DRM
                    // playlists usually put them before. They used to be thrown away here, so
                    // those channels were requested without their User-Agent and refused.
                    // They're cleared once the entry's link is read, instead.
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", true) || line.startsWith("#KODIPROP:", true) ||
                    line.startsWith("#EXTHTTP:", true) -> directives.add(line)
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    if (info != null) {
                        if (++seen % 256 == 0) checkpoint()
                        val pipe = line.indexOf('|')
                        val url = if (pipe > 0) line.substring(0, pipe).trim() else line.trim()
                        if (acceptUrl(url)) {
                            val entry = if (directives.isEmpty()) buildEntry(info, pendingGroup, line, emptyMap(), headerCatchup)
                                else entryWithDirectives(info, pendingGroup, line, directives, headerCatchup)
                            if (onEntry == null) entries.add(entry) else onEntry(entry)
                        }
                    }
                    pendingInfo = null
                    pendingGroup = null
                    directives.clear()
                }
            }
        }
        return M3uPlaylist(epgUrls.distinct(), entries)
    }

    private fun entryWithDirectives(info: String, group: String?, url: String, directives: List<String>,
        headerCatchup: Map<String, String>): M3uEntry {
        val pendingHeaders = mutableMapOf<String, String>()
        val pendingProps = mutableMapOf<String, String>()
        for (line in directives) when {
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                val opt = line.substringAfter(':')
                val key = opt.substringBefore('=').trim().lowercase()
                val value = opt.substringAfter('=', "").trim()
                when (key) {
                    "http-user-agent" -> pendingHeaders["User-Agent"] = value
                    "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                    "http-origin" -> pendingHeaders["Origin"] = value
                }
            }
            line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                val prop = line.substringAfter(':')
                val key = prop.substringBefore('=').trim().lowercase()
                    .removePrefix("inputstream.adaptive.").removePrefix("inputstream.")
                pendingProps[key] = prop.substringAfter('=', "").trim()
            }
            // TiviMate-style request headers: #EXTHTTP:{"User-Agent":"…","Referer":"…"}
            line.startsWith("#EXTHTTP:", ignoreCase = true) -> runCatching {
                val o = org.json.JSONObject(line.substringAfter(':'))
                o.keys().forEach { k -> pendingHeaders[normalizeHeader(k)] = o.optString(k) }
            }
        }
        listOf("stream_headers", "manifest_headers", "common_headers").forEach { key ->
            val v = pendingProps[key]
            if (!v.isNullOrBlank()) v.split('&').forEach { part ->
                val k = part.substringBefore('=').trim()
                if (k.isNotEmpty()) pendingHeaders[normalizeHeader(k)] =
                    runCatching { java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault(part.substringAfter('=', ""))
            }
        }
        return buildEntry(info, group, url, pendingHeaders, headerCatchup).copy(drm = drmFrom(pendingProps))
    }

    /**
     * DRM from "#KODIPROP" lines: license_type (clearkey / com.widevine.alpha /
     * com.microsoft.playready), license_key (URL, possibly "url|Header=…", or ClearKey
     * "kid:key" pairs / JSON), manifest_type.
     */
    private fun drmFrom(props: Map<String, String>): com.nuvio.tv.livetv.model.DrmInfo? {
        val type = (props["license_type"] ?: props["drm"])?.lowercase() ?: return null
        val scheme = when {
            "clearkey" in type || "org.w3" in type -> "clearkey"
            "widevine" in type -> "widevine"
            "playready" in type -> "playready"
            else -> return null
        }
        val raw = props["license_key"].orEmpty()
        // "url|Header=value&Header2=value|R{SSM}|" (Kodi): the URL, then optional headers.
        val parts = raw.split('|')
        val license = parts.firstOrNull().orEmpty().trim()
        val headers = LinkedHashMap<String, String>()
        parts.getOrNull(1)?.takeIf { it.contains('=') }?.split('&')?.forEach { h ->
            val k = h.substringBefore('=').trim()
            if (k.isNotEmpty()) headers[normalizeHeader(k)] = java.net.URLDecoder.decode(h.substringAfter('=', ""), "UTF-8")
        }
        // Newer Kodi style: license request headers on their own line.
        props["license_headers"]?.takeIf { it.isNotBlank() }?.split('&')?.forEach { h ->
            val k = h.substringBefore('=').trim()
            if (k.isNotEmpty()) headers[normalizeHeader(k)] =
                runCatching { java.net.URLDecoder.decode(h.substringAfter('=', ""), "UTF-8") }.getOrDefault(h.substringAfter('=', ""))
        }
        if (license.isBlank() && scheme != "clearkey") return null
        return com.nuvio.tv.livetv.model.DrmInfo(
            scheme = scheme,
            license = if (scheme == "clearkey") raw.trim() else license,
            licenseHeaders = headers,
            manifestType = props["manifest_type"]?.lowercase()
        )
    }

    private fun buildEntry(
        info: String,
        extGroup: String?,
        urlLine: String,
        vlcHeaders: Map<String, String>,
        playlistCatchup: Map<String, String> = emptyMap()
    ): M3uEntry {
        // The channel's own attributes win over the playlist-wide catch-up defaults.
        val own = attributes(info)
        val attrs = if (playlistCatchup.isEmpty()) own else playlistCatchup + own
        // Display name follows the first comma that is outside quotes.
        val name = displayName(info).ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Channel" }

        val needHeaders = vlcHeaders.isNotEmpty() || urlLine.indexOf('|') > 0 ||
            "user-agent" in attrs || "http-user-agent" in attrs || "http-referrer" in attrs
        val headers = if (needHeaders) LinkedHashMap(vlcHeaders) else null
        var url = urlLine
        val pipe = urlLine.indexOf('|')
        if (pipe > 0) {
            url = urlLine.substring(0, pipe)
            urlLine.substring(pipe + 1).split('&').forEach { part ->
                val k = part.substringBefore('=').trim()
                val v = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                if (k.isNotEmpty()) headers!![normalizeHeader(k)] = v
            }
        }
        attrs["user-agent"]?.let { headers!!["User-Agent"] = it }
        attrs["http-user-agent"]?.let { headers!!["User-Agent"] = it }
        attrs["http-referrer"]?.let { headers!!["Referer"] = it }

        val catchupType = attrs["catchup"] ?: attrs["catchup-type"]
        val catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull()
        val catchup = if (catchupType != null || (catchupDays ?: 0) > 0) {
            CatchupInfo(
                type = (catchupType ?: "default").lowercase(),
                source = attrs["catchup-source"],
                days = catchupDays ?: 1,
                correctionHours = attrs["catchup-correction"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            )
        } else null

        return M3uEntry(
            name = name.trim(),
            tvgId = attrs["tvg-id"]?.trim()?.ifEmpty { null },
            tvgName = attrs["tvg-name"]?.trim()?.ifEmpty { null },
            logo = (attrs["tvg-logo"] ?: attrs["logo"])?.trim()?.ifEmpty { null },
            group = (attrs["group-title"]?.trim()?.ifEmpty { null } ?: extGroup)
                ?.split(';')?.firstOrNull()?.trim(),
            chno = (attrs["tvg-chno"] ?: attrs["channel-number"] ?: attrs["tvg-num"])?.trim()?.toIntOrNull(),
            url = url.trim(),
            headers = headers.orEmpty(),
            catchup = catchup
        )
    }

    private fun normalizeHeader(key: String): String = when (key.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> key
    }

    private fun keyChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'
    private fun attrSpace(c: Char) = c == ' ' || c in '\t'..'\r'

    internal fun attributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        var i = 0
        while (i < line.length) {
            if (!keyChar(line[i])) { i++; continue }
            val start = i
            while (i < line.length && keyChar(line[i])) i++
            val end = i
            while (i < line.length && attrSpace(line[i])) i++
            if (i == line.length || line[i] != '=') continue
            i++
            while (i < line.length && attrSpace(line[i])) i++
            if (i == line.length) break
            val key = line.substring(start, end).lowercase()
            if (line[i] == '"') {
                val valueStart = ++i
                while (i < line.length && line[i] != '"') {
                    // The two legacy expressions can find an unquoted key inside a quoted
                    // value (often a logo query string). Preserve that obscure precedence.
                    if (line[i] == '=') return legacyAttributes(line)
                    i++
                }
                if (i == line.length) return legacyAttributes(line)
                map[key] = line.substring(valueStart, i++)
            } else {
                val valueStart = i
                while (i < line.length && !attrSpace(line[i]) && line[i] != ',' && line[i] != '"') {
                    if (line[i] == '=') return legacyAttributes(line)
                    i++
                }
                if (i > valueStart && key != "extinf" && key !in map) map[key] = line.substring(valueStart, i)
            }
        }
        return map
    }

    private fun legacyAttributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        attrRegex.findAll(line).forEach { m -> map[m.groupValues[1].lowercase()] = m.groupValues[2] }
        attrRegexUnquoted.findAll(line).forEach { m ->
            val key = m.groupValues[1].lowercase()
            if (key !in map && key != "extinf") map[key] = m.groupValues[2]
        }
        return map
    }

    internal fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }
}
