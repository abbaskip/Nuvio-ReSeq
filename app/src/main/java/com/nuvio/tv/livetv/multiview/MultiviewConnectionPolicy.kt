package com.nuvio.tv.livetv.multiview

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.PlaylistAccount
import com.nuvio.tv.livetv.model.PlaylistSource

/** Called synchronously before allocating a player or resolving a portal link, including retries. */
internal object MultiviewConnectionPolicy {
    private fun identity(source: PlaylistSource): List<String> = when {
        source.isStalker -> listOf("portal", runCatching {
            com.nuvio.tv.livetv.data.StalkerPortalClient.endpoint(source.stalkerPortal)
                .removeSuffix("/server/load.php").removeSuffix("/portal.php")
        }.getOrDefault(source.stalkerPortal.trim().trimEnd('/')))
        source.isXtream -> listOf("xtream", source.xtreamBase().lowercase(), source.xtreamUsername.trim(), source.xtreamPassword.trim())
        else -> listOf("m3u", source.id)
    }

    fun denial(channel: LiveChannel, active: List<LiveChannel>, sources: List<PlaylistSource>,
        accounts: Map<String, PlaylistAccount>): String? {
        val source = sources.firstOrNull { it.id == channel.sourceId && it.liveEnabled }
            ?: return "This playlist is no longer available. Choose another channel."
        if (!source.isStalker && !source.isXtream) return null
        val same = sources.filter { identity(it) == identity(source) }
        val ids = same.mapTo(HashSet()) { it.id }
        val maximum = if (source.isStalker) 1 else same.mapNotNull { accounts[it.id]?.maxConnections?.takeIf { n -> n > 0 } }.minOrNull()
            ?: return null
        // Saved provider activity is conservative: it may include streams on other devices.
        val external = if (source.isStalker) 0 else same.maxOfOrNull { accounts[it.id]?.activeConnections ?: 0 } ?: 0
        return if (external.coerceAtLeast(0) + active.count { it.sourceId in ids } >= maximum)
            "Your provider allows $maximum streams at once" else null
    }
}
