package com.nuvio.tv.livetv.sync

import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
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
class DriveSeekPreviewRoundTripTest {
    @Test fun backupRestoreMergeRotateAndClear() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE)
        val drive = LiveTvDriveSync(app, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
        var saved: JSONObject? = null
        var rejectWrite = false
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            var status = 200
            val body = when {
                req.url.encodedPath.endsWith("/v3/files") && req.method == "GET" -> {
                    assertTrue(req.url.queryParameter("q").orEmpty().contains("seek previews"))
                    if (saved == null) """{"files":[]}""" else """{"files":[{"id":"preview-file"}]}"""
                }
                req.method == "POST" -> {
                    val multipart = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
                    saved = JSONObject(multipart.lineSequence().first { it.startsWith("{") && it.contains("\"settings\"") })
                    """{"id":"preview-file"}"""
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

        fun remote() = saved!!.getJSONObject("settings")
        val original = mapOf("enabled" to "true", "seekr_only" to "false", "api_key" to "fixture-original",
            "include_4k" to "true", "when" to "SEEKING", "storage_mb" to "100")
        var local = original
        mockkObject(SeekThumbSettings)
        try {
            every { SeekThumbSettings.backupFields(app) } answers { local }
            every { SeekThumbSettings.restoreFields(app, any(), any()) } answers { local = local + thirdArg<Map<String, String>>() }
            assertTrue(drive.syncSeekPreviews("fixture", true, false))
            original.forEach { (key, value) -> assertEquals(value, remote().getString(key)) }
            assertFalse(prefs.all.toString().contains("fixture-original"))
            local = emptyMap()
            prefs.edit().clear().commit()
            assertTrue(drive.syncSeekPreviews("fixture", false, true))
            assertEquals(original, local)
            remote().put("seekr_only", "true")
            local = local + ("api_key" to "fixture-rotated")
            val baseline = prefs.getString("seek_preview_fingerprints", null)
            rejectWrite = true
            assertTrue(runCatching { drive.syncSeekPreviews("fixture", true, false) }.isFailure)
            assertEquals(baseline, prefs.getString("seek_preview_fingerprints", null))
            rejectWrite = false
            drive.syncSeekPreviews("fixture", true, false)
            assertEquals("true", local["seekr_only"])
            assertEquals("fixture-rotated", remote().getString("api_key"))
            local = local + ("api_key" to "")
            drive.syncSeekPreviews("fixture", true, false)
            assertEquals("", remote().getString("api_key"))
            remote().put("storage_mb", "invalid")
            assertTrue(runCatching { drive.syncSeekPreviews("fixture", false, true) }.isFailure)
        } finally { unmockkObject(SeekThumbSettings) }
    }
}
