package com.nuvio.tv.livetv.multiview

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.PlaylistAccount
import com.nuvio.tv.livetv.model.PlaylistSource

/** Called synchronously before allocating a player or resolving a portal link, including retries. */
internal object MultiviewConnectionPolicy {
    private fun m3uLogin(source: PlaylistSource): List<String>? = runCatching {
        val uri = java.net.URI(PlaylistSource.tidyLink(source.url))
        if (!uri.path.orEmpty().endsWith("get.php", true)) return@runCatching null
        val (user, pass) = PlaylistSource.credentialsFromLink(source.url) ?: return@runCatching null
        listOf("xtream", PlaylistSource.cleanXtreamServer(source.url).lowercase(), user, pass)
    }.getOrNull()

    private fun identity(source: PlaylistSource): List<String> = when {
        source.isStalker -> listOf("portal", runCatching {
            com.nuvio.tv.livetv.data.StalkerPortalClient.endpoint(source.stalkerPortal)
                .removeSuffix("/server/load.php").removeSuffix("/portal.php")
        }.getOrDefault(source.stalkerPortal.trim().trimEnd('/')))
        source.isXtream -> listOf("xtream", source.xtreamBase().lowercase(), source.xtreamUsername.trim(), source.xtreamPassword.trim())
        else -> m3uLogin(source) ?: listOf("m3u", source.id)
    }

    fun denial(channel: LiveChannel, active: List<LiveChannel>, sources: List<PlaylistSource>,
        accounts: Map<String, PlaylistAccount>): String? {
        val source = sources.firstOrNull { it.id == channel.sourceId && it.liveEnabled }
            ?: return "This playlist is no longer available. Choose another channel."
        val same = sources.filter { identity(it) == identity(source) }
        val ids = same.mapTo(HashSet()) { it.id }
        val maximum = if (source.isStalker) 1 else same.mapNotNull { accounts[it.id]?.maxConnections?.takeIf { n -> n > 0 } }.minOrNull()
            ?: return null
        // activeConnections is a saved refresh snapshot, not a current reservation. It can
        // include the just-released guide preview or these very panes. Adding it double-counts
        // our connections and can block the first pane forever on a one-stream account.
        // Reserve against the real account maximum here; live provider rejections still handle
        // capacity consumed elsewhere after the snapshot was taken.
        return if (active.count { it.sourceId in ids } >= maximum)
            "Your provider allows $maximum streams at once" else null
    }
}
