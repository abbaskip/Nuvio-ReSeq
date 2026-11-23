package com.nuvio.tv.livetv.sync

import android.app.Application
import android.content.Context
import com.nuvio.tv.data.local.ProfileDataStore
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
class DriveProviderKeyRoundTripTest {
    @Test fun keysRoundTripWithoutAPlaylistOrPassphrase() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE)
        val profiles = mockk<ProfileDataStore>()
        val profile = UserProfile(1, "Profile", "#ffffff")
        val profileList = MutableStateFlow(listOf(profile))
        every { profiles.profilesList } returns profileList
        val providers = mockk<DriveProviderKeys>()
        val original = listOf("tmdb", "gemini", "mdblist", "animeskip",
            com.nuvio.tv.core.debrid.DebridProviders.all().first().id).associateWith { "fixture-$it" }
        val keys = MutableStateFlow(original)
        every { providers.observe(1) } returns keys
        coEvery { providers.restore(1, any(), any(), any()) } coAnswers {
            val service = arg<String>(1)
            if (keys.value[service].orEmpty() == arg<String>(2)) keys.value += service to arg<String>(3)
        }
        val drive = LiveTvDriveSync(app, mockk(), mockk(), mockk(), mockk(), profiles, mockk(), mockk(), mockk(), providers)
        var saved: JSONObject? = null
        var rejectWrite = false
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            var status = 200
            val body = when {
                req.url.encodedPath.endsWith("/v3/files") && req.method == "GET" -> {
                    assertTrue(req.url.queryParameter("q").orEmpty().contains("provider credentials"))
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
        suspend fun backup() = drive.syncProviderKeys("test", upload = true, restore = false, force = true)
        suspend fun restore() = drive.syncProviderKeys("test", upload = false, restore = true, force = true)
        assertTrue(backup())
        assertFalse(prefs.all.values.any { it.toString().contains("fixture-") })
        keys.value = mapOf("tmdb" to "new-tmdb")
        assertTrue(backup())
        keys.value = emptyMap()
        prefs.edit().clear().commit()
        assertTrue(restore())
        assertEquals(original + ("tmdb" to "new-tmdb"), keys.value)
        val savedKeys = saved!!.getJSONObject("profiles").getJSONObject("1").getJSONObject("keys")
        savedKeys.remove("animeskip")
        keys.value += "animeskip" to "local-only"
        restore()
        assertEquals("local-only", keys.value["animeskip"])
        val baseline = prefs.getString("provider_fingerprints", null)
        keys.value += "gemini" to "changed"
        rejectWrite = true
        assertTrue(runCatching { backup() }.isFailure)
        assertEquals(baseline, prefs.getString("provider_fingerprints", null))
        profileList.value = listOf(profile.copy(name = "Another person"))
        assertTrue(runCatching { restore() }.isFailure)
        assertEquals("changed", keys.value["gemini"])
    }
}
