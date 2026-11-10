package com.nuvio.tv.livetv.sync

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/** Conditional reads apply only to the media representation already compared with this TV's
 * baseline. Drive's upload precondition uses a different ETag. */
internal class DriveMediaPoll {
    private data class Validator(val url: HttpUrl, val baseline: Any, val etag: String)
    class Download(val text: String, val acknowledgeUnchanged: () -> Unit)

    @Volatile private var baseline = Any()
    @Volatile private var confirmed: Validator? = null

    fun invalidate() {
        baseline = Any()
        confirmed = null
    }

    fun download(
        client: Call.Factory,
        request: Request,
        force: Boolean,
        readBody: (Response) -> String
    ): Download? {
        val current = baseline
        val validator = confirmed?.takeIf { !force && it.url == request.url && it.baseline === current }
        val conditional = validator?.let {
            request.newBuilder().header("If-None-Match", it.etag).build()
        } ?: request
        return client.newCall(conditional).execute().use { response ->
            if (validator != null && response.code == 304) return null
            val text = readBody(response)
            val etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
            Download(text) {
                confirmed = etag?.let { Validator(request.url, current, it) }
            }
        }
    }
}
