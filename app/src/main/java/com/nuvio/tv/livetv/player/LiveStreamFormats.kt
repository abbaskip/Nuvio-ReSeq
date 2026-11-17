package com.nuvio.tv.livetv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException

/**
 * What the Live TV players learn about how a provider's links really play, shared by the
 * full-screen player and Multiview so a channel fixed in one plays the same way in the other.
 * ReSeq M1: used by the panes only; the existing single player's equivalent fallbacks stay intact.
 */
@OptIn(UnstableApi::class)
internal object LiveStreamFormats {
    /** Live channels whose HLS playlist couldn't be read: played as TS instead (until the app restarts). */
    val tsInstead: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    /** Live channels whose link looks like HLS but sends plain TS: read as TS (until the app restarts). */
    val plainTs: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Formats tried, in order, for a link that doesn't say what it is ("container unsupported"). */
    val FORMAT_GUESSES = listOf(MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD)

    /** Xtream live links: ".../live/user/pass/123.m3u8" or "host/user/pass/123.m3u8". */
    private val XTREAM_HLS = Regex("""/(?:live/)?[^/]+/[^/]+/\d+\.m3u8$""", RegexOption.IGNORE_CASE)
    /** An Xtream live link with no ending: ".../live/user/pass/123" or ".../user/pass/123". */
    private val XTREAM_BARE = Regex("""/(?:live/)?[^/]+/[^/]+/\d+$""")

    /**
     * The TS version of an Xtream live link: ".../123.m3u8" -> ".../123.ts", and a link with no
     * ending at all (".../user/pass/123", which the server answers as HLS) -> ".../123.ts".
     * Null if it isn't one.
     */
    fun tsLinkFor(url: String): String? {
        val base = url.substringBefore('?')
        if (base.endsWith(".m3u8", ignoreCase = true) && XTREAM_HLS.containsMatchIn(base))
            return base.dropLast(5) + ".ts" + url.substring(base.length)
        if (XTREAM_BARE.containsMatchIn(base)) return base + ".ts" + url.substring(base.length)
        return null
    }

    fun isMalformedManifest(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) return true
        var c: Throwable? = error
        while (c != null) {
            if (c is androidx.media3.common.ParserException && c.message?.contains("malformed", true) == true) return true
            c = c.cause
        }
        return false
    }

    fun isUnrecognizedFormat(error: PlaybackException): Boolean {
        var c: Throwable? = error
        while (c != null) {
            if (c is androidx.media3.exoplayer.source.UnrecognizedInputFormatException) return true
            if (c is androidx.media3.common.ParserException && c.message?.contains("ontainer", true) == true) return true
            c = c.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    }

    /** A link for the start-up report, with the username and password hidden. */
    fun redact(url: String): String = redactLiveStreamUrl(url)
}
