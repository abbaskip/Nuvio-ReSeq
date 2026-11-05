package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.data.StalkerPortalClient
import com.nuvio.tv.livetv.model.PlaylistSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Catalogue identity survives a portal session. Commands and temporary links are fetched on demand. */
internal class StalkerVod(private val portal: StalkerPortalClient) {
    private val VodKind.portalType get() = if (this == VodKind.MOVIE) "vod" else "series"

    suspend fun categories(source: PlaylistSource, kind: VodKind): List<Pair<String, String>> {
        val result = portal.request(source, kind.portalType, "get_categories") as? JSONArray
            ?: error("Invalid portal categories.")
        return (0 until result.length()).map { i ->
            val row = result.getJSONObject(i)
            requireNotNull(row.field("id")) to requireNotNull(row.field("title"))
        }.filter { it.first != "*" && it.first != "0" }.distinctBy { it.first }
    }

    /** A false visitor result means the caller found its target and needs no further pages. */
    private suspend fun pages(source: PlaylistSource, kind: VodKind, parameters: Map<String, String>,
        beforePage: () -> Unit = {}, lookupPageLimit: Int? = null, visit: (JSONObject) -> Boolean) {
        var received = 0L
        // Detect ignored page parameters without growing the working set with catalogue size.
        // SQLite also checks all imported IDs for overlap before pruning an existing catalogue.
        val fingerprints = LinkedHashSet<String>()
        var expected: Long? = null
        for (page in 1..100_000) {
            if (lookupPageLimit != null && page > lookupPageLimit) return
            beforePage()
            val result = portal.request(source, kind.portalType, "get_ordered_list", parameters + ("p" to page.toString())) as? JSONObject
                ?: error("Invalid portal listing.")
            val rows = result.getJSONArray("data")
            val count = rows.length()
            val total = result.field("total_items")?.toLongOrNull()?.takeIf { it >= 0 }
            if (page == 1) expected = total
            check(expected == total) { "Portal catalogue changed during import." }
            if (count == 0) {
                check(expected == null || received == expected) { "Incomplete portal listing." }
                return
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            for (i in 0 until count) {
                val row = rows.getJSONObject(i)
                digest.update(requireNotNull(row.field("id")).toByteArray())
                digest.update(0.toByte())
                if (!visit(row)) return
            }
            check(fingerprints.add(okio.ByteString.of(*digest.digest()).hex())) { "Portal repeated a catalogue page." }
            if (fingerprints.size > 64) fingerprints.remove(fingerprints.first())
            received += count
            if (expected != null) {
                check(received <= expected) { "Inconsistent portal listing." }
                if (received == expected) return
            }
        }
        error("Portal pagination did not finish.")
    }

    suspend fun catalog(source: PlaylistSource, kind: VodKind, category: String?,
        visit: (VodItem) -> Boolean) {
        pages(source, kind, mapOf("category" to (category ?: "0"), "sortby" to "name"),
            beforePage = { BackgroundWork.awaitPlaybackIdleBlocking() }) { row ->
            val id = requireNotNull(row.field("id"))
            val name = requireNotNull(row.field("name") ?: row.field("title"))
            val group = row.field("category_id") ?: category.orEmpty()
            check(category == null || group == category) { "Portal ignored the category filter." }
            val keepMetadata = !source.vodSourceOnly
            visit(VodItem(source.id, kind, id, name, if (keepMetadata) artwork(source, row) else null,
                group, row.field("container_extension"), row.tmdbId(),
                row.field("year")?.take(4)?.toIntOrNull() ?: OnDemandDatabase.releaseYearInName(name),
                row.field("rating_imdb"), row.optLong("added"), if (keepMetadata) row.field("genres_str") else null))
        }
    }

    suspend fun browseArtwork(source: PlaylistSource, item: VodItem): String? {
        // Servers commonly return only a dozen rows per page. A poster must not trigger a
        // scan through an entire category when the TV is browsing near the end of a library.
        BackgroundWork.awaitIdle()
        return find(source, item, lookupPageLimit = 3)?.let { artwork(source, it) }
    }

    private suspend fun find(source: PlaylistSource, item: VodItem, lookupPageLimit: Int? = null): JSONObject? {
        var found: JSONObject? = null
        pages(source, item.kind, mapOf("category" to item.categoryId.ifBlank { "0" }, "search" to item.name, "sortby" to "name"),
            lookupPageLimit = lookupPageLimit) { row ->
            if (row.field("id") == item.id) found = row
            found == null
        }
        return found
    }

    private suspend fun episodes(source: PlaylistSource, item: VodItem, visit: (Int, Int, JSONObject) -> Boolean) {
        pages(source, VodKind.SERIES, mapOf("movie_id" to item.id)) { row ->
            val season = row.field("season_number")?.toIntOrNull() ?: row.field("season")?.toIntOrNull()
                ?: seasonPattern.find(row.field("name").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                ?: return@pages true
            val numbers = row.optJSONArray("series")
            if (numbers == null) {
                val number = (row.field("episode_num") ?: row.field("episode_number"))?.toIntOrNull()
                number == null || number <= 0 || visit(season, number, row)
            } else (0 until numbers.length()).all { i ->
                val number = numbers.optString(i).toIntOrNull()
                number == null || number <= 0 || visit(season, number, row)
            }
        }
    }

    suspend fun info(source: PlaylistSource, item: VodItem): VodInfo {
        val row = find(source, item)
        val episodes = mutableListOf<VodEpisode>()
        if (item.kind == VodKind.SERIES) episodes(source, item) { season, number, entry ->
            check(episodes.size < 20_000) { "Portal series is too large." }
            val episodeRow = entry.optJSONArray("series") == null
            val title = if (episodeRow) entry.field("name") else null
            if (entry.field("cmd") != null) episodes += VodEpisode(season, number, "${entry.field("id")}:$number",
                entry.field("container_extension").orEmpty(), title ?: "Episode $number",
                if (episodeRow) entry.field("description") else null, null, marker(item, season, number, entry.field("id")),
                imdbId = if (episodeRow) entry.field("imdb_id")?.takeIf { it.matches(Regex("tt[0-9]+")) } else null,
                tvdbId = if (episodeRow) entry.field("tvdb_id")?.toLongOrNull()?.takeIf { it > 0 } else null,
                portalCoordinates = true)
            true
        }
        return VodInfo(row?.field("description"), row?.let { artwork(source, it) }, null, row?.field("genres_str"),
            row?.field("year"), row?.field("time"), episodes.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episode })),
            tmdbId = row?.tmdbId(), imdbId = row?.field("imdb_id"))
    }

    suspend fun resolve(source: PlaylistSource, item: VodItem, season: Int?, number: Int?, entryId: String?): StalkerPortalClient.Link {
        var command: String? = null
        if (item.kind == VodKind.MOVIE) command = find(source, item)?.field("cmd")
        else episodes(source, item) { s, n, row ->
            if (s == season && n == number && row.field("id") == entryId) {
                check(command == null) { "Ambiguous portal episode." }
                command = row.field("cmd")
            }
            true
        }
        val parameters = mutableMapOf("cmd" to requireNotNull(command) { "Portal title unavailable." },
            "forced_storage" to "undefined", "disable_ad" to "0")
        if (number != null) parameters["series"] = number.toString()
        val result = portal.request(source, "vod", "create_link", parameters) as? JSONObject
        val url = listOf("cmd", "url", "stream_url").firstNotNullOfOrNull { portal.playbackUrl(result?.optString(it).orEmpty()) }
            ?: error("Portal returned no playable link.")
        return StalkerPortalClient.Link(url, mapOf("User-Agent" to source.userAgent.ifBlank { StalkerPortalClient.USER_AGENT }))
    }

    companion object {
        const val PREFIX = "reseq-stalker-vod://"
        private val seasonPattern = Regex("""(?i)(?:\bseason\s*|\bs|^)(\d+)\b""")
        private fun JSONObject.tmdbId() = sequenceOf("tmdb_id", "tmdb", "tmdbid").mapNotNull { field(it)?.toLongOrNull() }.firstOrNull { it > 0 }?.toString()
        private fun JSONObject.field(key: String) = optString(key).trim().takeUnless { it.isEmpty() || it == "null" }
        private fun artwork(source: PlaylistSource, row: JSONObject) = row.field("screenshot_uri")?.let {
            source.stalkerPortal.toHttpUrl().resolve(it)?.toString()
        }
        fun marker(item: VodItem, season: Int? = null, episode: Int? = null, entryId: String? = null): String {
            val url = "http://catalog/".toHttpUrl().newBuilder().addPathSegment(item.uid)
            if (season != null && episode != null) url.addQueryParameter("s", season.toString()).addQueryParameter("e", episode.toString()).addQueryParameter("entry", entryId)
            return PREFIX + url.build().toString().removePrefix("http://")
        }
    }
}
