package com.nuvio.tv.livetv.sync

import android.app.Application
import android.content.Context
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.tvdb.TvdbCredentialStore
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveTvdbRoundTripTest {
    @Test fun freshDeviceRestoreRotationConflictAndRemoval() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE)
        val profiles = mockk<ProfileDataStore>()
        every { profiles.profilesList } returns MutableStateFlow(listOf(UserProfile(1, "Profile", "#ffffff")))
        val keys = mockk<TvdbCredentialStore>()
        var local: String? = "fixture-original"
        var explicitRemoval = false
        every { keys.read(1) } answers { local }
        every { keys.explicitlyRemoved(1) } answers { explicitRemoval }
        every { keys.changes } returns MutableStateFlow(0L)
        every { keys.replaceIfUnchanged(1, any(), any()) } answers {
            if (local == arg<String?>(1)) local = arg(2)
        }
        val drive = LiveTvDriveSync(app, mockk(), mockk(), keys, mockk(), profiles, mockk(), mockk(), mockk())
        var saved: JSONObject? = null
        var rejectWrite = false
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            var status = 200
            val body = when {
                req.url.encodedPath.endsWith("/v3/files") && req.method == "GET" -> {
                    assertTrue(req.url.queryParameter("q").orEmpty().contains("TVDB credentials"))
                    if (saved == null) """{"files":[]}""" else """{"files":[{"id":"key-file"}]}"""
                }
                req.method == "POST" -> {
                    val multipart = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
                    saved = JSONObject(multipart.lineSequence().first { it.startsWith("{") && it.contains("\"profiles\"") })
                    """{"id":"key-file"}"""
                }
                req.method == "PUT" -> {
                    assertEquals("\"v1\"", req.header("If-Match"))
                    if (rejectWrite) status = 412 else saved = JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8())
                    "{}"
                }
                req.url.queryParameter("alt") == "media" -> saved!!.toString()
                req.url.queryParameter("fields") == "id,etag" -> JSONObject().put("etag", "\"v1\"").toString()
                else -> error("Unexpected Drive operation")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body.toResponseBody()).build()
        }.build()
        // Replace the transport only in this JVM fixture. No request can leave the interceptor.
        drive.javaClass.getDeclaredField("http").apply { isAccessible = true; set(drive, transport) }
        fun remoteKey() = saved!!.getJSONObject("profiles").getJSONObject("1").getString("key")
        drive.syncTvdbKeys("test", upload = true, restore = false, force = true)
        assertEquals("fixture-original", remoteKey())
        assertFalse(prefs.all.values.any { it.toString().contains("fixture-original") })

        // Fresh install, no baseline and no locally configured key.
        prefs.edit().clear().commit()
        local = null
        drive.syncTvdbKeys("test", upload = true, restore = false, force = true)
        assertEquals("fixture-original", local)
        local = null // Restored preferences with an unreadable local keystore must recover the key.
        drive.syncTvdbKeys("test", true, false, false)
        assertEquals("fixture-original", local)
        val baseline = prefs.getString("tvdb_fingerprints", null)
        local = "fixture-rotated"
        rejectWrite = true
        assertTrue(runCatching { drive.syncTvdbKeys("test", true, false, false) }.isFailure)
        assertEquals(baseline, prefs.getString("tvdb_fingerprints", null))
        rejectWrite = false
        drive.syncTvdbKeys("test", true, false, false)
        assertEquals(local, remoteKey())

        explicitRemoval = true
        local = null
        drive.syncTvdbKeys("test", true, false, false)
        assertEquals("", remoteKey())
        local = "fixture-other"
        drive.syncTvdbKeys("test", false, true, true)
        assertNull(local)
    }
}
