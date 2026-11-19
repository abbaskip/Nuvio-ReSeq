package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.PlaylistSource

/** One numbering and de-duplication sequence across all enabled playlists, in provider order. */
internal class M3uChannelList(private val multi: Boolean) {
    val channels = ArrayList<LiveChannel>()
    private val usedKeys = HashSet<String>()
    private var counter = 1

    fun appendFrom(pl: PlaylistSource): (M3uEntry) -> Unit {
        val defaultHeaders = if (pl.userAgent.isBlank()) emptyMap() else mapOf("User-Agent" to pl.userAgent)
        return { e ->
            val groupTitle = e.group?.ifBlank { null } ?: "Uncategorized"
            val baseKey = "${pl.id}|${e.tvgId ?: ""}|${e.name}"
            var key = baseKey
            var n = 2
            while (!usedKeys.add(key)) { key = "$baseKey#${n++}" }
            val number = e.chno ?: counter
            counter = maxOf(counter, number) + 1
            val headers = when {
                e.headers.isEmpty() -> defaultHeaders
                defaultHeaders.isEmpty() || "User-Agent" in e.headers -> e.headers
                else -> e.headers + defaultHeaders
            }
            channels += LiveChannel(
                key = key,
                sourceId = pl.id,
                sourceName = pl.name,
                name = e.name,
                tvgId = e.tvgId,
                tvgName = e.tvgName,
                logo = e.logo,
                groupId = if (multi) "${pl.id}::$groupTitle" else groupTitle,
                // Names stay exactly as the playlist has them (the playlist is shown, if you want
                // it, with "Group by playlist").
                group = groupTitle,
                number = number,
                url = e.url,
                headers = headers,
                catchup = e.catchup,
                drm = e.drm
            )
        }
    }
}
