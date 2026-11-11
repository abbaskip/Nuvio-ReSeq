package com.nuvio.tv.livetv.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DriveMediaPollTest {
    @Test fun onlyConfirmedUnchangedMediaUsesTheValidator() {
        MockWebServer().use { server ->
            server.start()
            val poll = DriveMediaPoll()
            val http = OkHttpClient()
            var bodyReads = 0
            fun get(path: String = "/backup", force: Boolean = false) = poll.download(http,
                Request.Builder().url(server.url(path)).build(), force) {
                    check(it.isSuccessful)
                    bodyReads++
                    requireNotNull(it.body).string()
                }
            fun body(etag: String?) {
                val response = MockResponse().setBody("fixture")
                if (etag != null) response.setHeader("ETag", etag)
                server.enqueue(response)
            }
            body("\"one\"")
            get() // A failed or unapplied merge must be retried.
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            body("\"one\"")
            get()!!.acknowledgeUnchanged()
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            server.enqueue(MockResponse().setResponseCode(304))
            assertNull(get())
            assertEquals("\"one\"", server.takeRequest().getHeader("If-None-Match"))
            assertEquals(2, bodyReads)
            body("\"two\"")
            val changed = get()!!
            assertEquals("fixture", changed.text)
            assertEquals("\"one\"", server.takeRequest().getHeader("If-None-Match"))
            changed.acknowledgeUnchanged()
            body("\"two\"")
            get(force = true)
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            body("\"other\"")
            get("/other")
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            poll.invalidate()
            changed.acknowledgeUnchanged() // An old completion cannot validate a new baseline.
            body(null)
            get()!!.acknowledgeUnchanged()
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            body(null)
            get()
            assertNull(server.takeRequest().getHeader("If-None-Match"))
        }
    }
}
