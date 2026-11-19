package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.M3uEntry
import com.nuvio.tv.livetv.model.PlaylistSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Credentials are used only on their originating HTTP server. Only their digest is compared. */
internal class M3uLogin(val server: String, val username: String, val password: String) {
    fun forCatalog(source: PlaylistSource) = source.copy(xtreamServer = server,
        xtreamUsername = username, xtreamPassword = password)

    fun key() = m3uDigest(server, username, password)

    companion object {
        fun direct(source: PlaylistSource): M3uLogin? {
            val link = PlaylistSource.tidyLink(source.url).toHttpUrlOrNull() ?: return null
            if (!link.pathSegments.last().equals("get.php", true)) return null
            val fields = link.queryParameterNames.associateBy { it.lowercase() }
            val user = fields["username"]?.let(link::queryParameter) ?: return null
            val pass = fields["password"]?.let(link::queryParameter) ?: return null
            return from(link, user, pass)
        }

        private val streamId = Regex("""[0-9]+(?:\.[A-Za-z0-9]+)?""")
        fun channel(raw: String): M3uLogin? {
            val link = raw.toHttpUrlOrNull() ?: return null
            val parts = link.pathSegments
            val offset = when {
                parts.size == 4 && parts[0].lowercase() in setOf("live", "movie", "series") -> 1
                parts.size == 3 -> 0
                else -> return null
            }
            if (!streamId.matches(parts.last())) return null
            return from(link, parts[offset], parts[offset + 1])
        }

        fun native(source: PlaylistSource): M3uLogin? {
            if (!source.isXtream) return null
            return from(source.xtreamBase().toHttpUrlOrNull() ?: return null,
                source.xtreamUsername.trim(), source.xtreamPassword.trim())
        }

        private fun from(link: HttpUrl, user: String, pass: String): M3uLogin? {
            if (user.isBlank() || pass.isBlank()) return null
            val origin = link.newBuilder().encodedPath("/").username("").password("").query(null).fragment(null).build()
            return M3uLogin(origin.toString().trimEnd('/'), user, pass)
        }
    }
}

private val digests = object : ThreadLocal<MessageDigest>() {
    override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
}
internal fun m3uDigest(vararg values: String): String {
    val digest = requireNotNull(digests.get())
    digest.reset()
    values.forEach { digest.update(it.toByteArray()); digest.update(0.toByte()) }
    return digest.digest().toHexString()
}

internal class M3uTitle(val id: String, val entryId: String, val name: String, val group: String,
    val season: Int?, val episode: Int?, val ext: String) {
    val kind get() = if (season == null) VodKind.MOVIE else VodKind.SERIES

    companion object {
        private val episodeTag = Regex("""(?i)(?<![\p{L}\d])S(\d{1,3})[ ._-]*E(\d{1,4})(?!\d)""")
        private val seriesTag = Regex("""(?i)(?<!\d)(\d{1,3})x(\d{1,4})(?!\d)""")
        private val extPattern = Regex("""[a-z0-9]{2,5}""")

        fun from(entry: M3uEntry): M3uTitle? {
            if (!LiveTvRepository.isVodEntry(entry.url)) return null
            val link = entry.url.toHttpUrlOrNull() ?: return null
            val path = link.encodedPath
            val group = entry.group?.ifBlank { null } ?: "Uncategorized"
            val isSeries = path.contains("/series/", true)
            val tag = if (path.contains("/movie/", true) || path.contains("/movies/", true)) null
                else episodeTag.find(entry.name) ?: if (isSeries) seriesTag.find(entry.name) else null
            val name = tag?.let { entry.name.take(it.range.first).trimEnd(' ', '.', '-', '|', ':', '_', '[', '(') }
                ?.takeIf(String::isNotEmpty) ?: entry.name
            val season = tag?.groupValues?.get(1)?.toIntOrNull() ?: if (isSeries) 1 else null
            val number = tag?.groupValues?.get(2)?.toIntOrNull()
            val entryId = m3uDigest(link.host, link.pathSegments.last(), link.queryParameter("id").orEmpty(),
                entry.tvgId.orEmpty(), group, entry.name)
            val titleId = if (season == null) entryId else m3uDigest(group, name.lowercase())
            val ext = link.pathSegments.last().substringAfterLast('.', "").lowercase().takeIf(extPattern::matches) ?: "mp4"
            return M3uTitle("m3u-$titleId", entryId, name, group, season, number, ext)
        }
    }
}
