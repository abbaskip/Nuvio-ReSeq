package com.nuvio.tv.core.usenet

// Adapted from haaihond/NuvioTV PR #4426 at 8f451a88bcce7ad5e91f5e997dda46fa381315a3.
// Licensed under GPL-3.0, as is this project. See docs/usenet-upstream-review.md.

/** Results-screen ownership until an internal or external player actually launches.
 * Call on the UI thread. Releasing an abandoned URL must not affect a newer session.
 */
internal class PendingUsenetPlayback(private val releaseSession: (String) -> Unit) {
    private var url: String? = null
    /** Handed to an external player, which cannot release it; the results screen does when the user is back. */
    private var external: String? = null

    fun replace(next: String) {
        if (url == next) return
        release()
        url = next
    }

    fun handoff(launchedUrl: String?) {
        if (url == launchedUrl) url = null
    }

    /**
     * Like [handoff], for an external player. The results screen stops while it plays, so
     * [release] keeps the session; [externalPlayerReturned] releases it.
     */
    fun handoffExternal(launchedUrl: String?) {
        if (url != launchedUrl || launchedUrl == null) return
        url = null
        externalPlayerReturned()
        external = launchedUrl
    }

    /** Back from the external player: nothing plays its session any more. */
    fun externalPlayerReturned() {
        val returned = external ?: return
        external = null
        releaseSession(returned)
    }

    fun release(expectedUrl: String? = url) {
        if (url != expectedUrl) return
        val abandoned = url ?: return
        url = null
        releaseSession(abandoned)
    }
}
