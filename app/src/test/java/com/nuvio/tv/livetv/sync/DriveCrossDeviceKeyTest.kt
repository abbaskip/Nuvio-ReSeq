package com.nuvio.tv.livetv.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.tvdb.TvdbCredentialStore
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class DriveCrossDeviceKeyTest {
    /** In-memory Drive endpoint. All requests terminate here, including unexpected requests. */
    private class Drive(val name: String = "Nuvio ReSeq TVDB credentials.json") : Interceptor {
        var document: JSONObject? = null
        var revision = 0
        var requests = 0
        private fun RequestBody.json() = JSONObject(Buffer().also { writeTo(it) }.readUtf8())

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            requests++
            check(req.header("Authorization") == "Bearer fixture")
            val query = req.url
            val result = when {
                query.encodedPath == "/drive/v3/files" -> {
                    check(query.queryParameter("q") == "name='$name' and trashed=false")
                    check(query.queryParameter("spaces") == "drive")
                    JSONObject().put("files", JSONArray().apply { if (document != null) put(JSONObject().put("id", "fixture-file")) })
                }
                req.method == "POST" && query.encodedPath == "/upload/drive/v3/files" -> {
                    check(document == null)
                    val body = req.body as MultipartBody
                    check(body.parts[0].body.json().getString("name") == name)
                    document = body.parts[1].body.json()
                    revision++
                    JSONObject().put("id", "fixture-file")
                }
                req.method == "PUT" && query.encodedPath == "/upload/drive/v2/files/fixture-file" -> {
                    check(req.header("If-Match") == "\"v$revision\"")
                    document = requireNotNull(req.body).json()
                    revision++
                    JSONObject()
                }
                query.encodedPath == "/drive/v2/files/fixture-file" && query.queryParameter("alt") == "media" -> requireNotNull(document)
                query.encodedPath == "/drive/v2/files/fixture-file" -> JSONObject().put("id", "fixture-file").put("etag", "\"v$revision\"")
                else -> error("Unexpected Drive request")
            }
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", "\"media$revision\"").body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }
    }

    private class Device(namespace: String, server: Drive, profileName: String = "Primary") {
        var key: String? = null
        private val revision = MutableStateFlow(0L)
        private val ctx = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("$namespace.$name", mode)
        }
        private val keys = mockk<TvdbCredentialStore> {
            every { changes } returns revision
            every { read(1) } answers { key }
            every { explicitlyRemoved(1) } returns false
            every { replaceIfUnchanged(1, any(), any()) } answers {
                if (key == secondArg<String?>()) { key = thirdArg(); revision.value++ }
            }
        }
        private val profiles = mockk<ProfileDataStore> {
            every { profilesList } returns flowOf(listOf(UserProfile(1, profileName, "#ffffff")))
        }
        val sync: LiveTvDriveSync
        init {
            ctx.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE).edit().putString("mode", "file").commit()
            sync = LiveTvDriveSync(ctx, mockk(), mockk(), keys, mockk(), profiles, mockk(), mockk(), mockk(),
                mockk { every { observe(1) } returns flowOf(emptyMap()) }, mockk(), mockk(), mockk())
            val grantStore = mockk<ProtectedProfilePreferences>(relaxUnitFun = true) {
                every { read(1) } returns "fixture-grant"
            }
            LiveTvDriveSync::class.java.getDeclaredField("protected").apply { isAccessible = true }.set(sync, grantStore)
            // Keep the release URL and request builder intact while replacing transport in this test instance.
            LiveTvDriveSync::class.java.getDeclaredField("http").apply { isAccessible = true }
                .set(sync, OkHttpClient.Builder().addInterceptor(server).build())
        }
        suspend fun backup() = sync.syncTvdbKeys("fixture", upload = true, restore = false, force = true)
        suspend fun restore() = sync.syncTvdbKeys("fixture", upload = false, restore = true, force = true)
        suspend fun connect() {
            sync.finishSignIn(JSONObject().put("scope", "https://www.googleapis.com/auth/drive.file")
                .put("refresh_token", "fixture-grant").put("access_token", "fixture"), "file")
        }
        fun autoSyncOnDisk() = ctx.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE).getBoolean("auto_sync", true)
    }

    @Test fun providerKeysTravelOnlyInAnExplicitEncryptedBackup() = runBlocking {
        val server = Drive("Nuvio ReSeq provider keys - profile 1.json")
        val tv = Device("provider-tv", server)
        val phone = Device("provider-phone", server)
        val payload = JSONObject().put("tmdb", "first-fixture-key").put("gemini", "second-fixture-key")
        val password = "transfer fixture passphrase".toCharArray()
        tv.connect()
        assertEquals(0, server.requests)
        tv.sync.transferProviderKeys(1) { previous ->
            assertNull(previous)
            ProviderKeyEnvelope.seal(payload, password)
        }
        val requests = server.requests
        phone.connect()
        assertEquals(requests, server.requests)
        assertEquals(1, server.revision)
        assertFalse(server.document.toString().contains("fixture-key"))
        assertTrue(runCatching {
            phone.sync.transferProviderKeys(1) { doc ->
                ProviderKeyEnvelope.open(requireNotNull(doc), "incorrect passphrase".toCharArray())
                null
            }
        }.exceptionOrNull() is java.security.GeneralSecurityException)
        phone.sync.transferProviderKeys(1) { doc ->
            val clear = ProviderKeyEnvelope.open(requireNotNull(doc), password)
            assertEquals(payload.getString("tmdb"), clear.getString("tmdb"))
            assertEquals(payload.getString("gemini"), clear.getString("gemini"))
            null
        }
        assertEquals(1, server.revision)
    }

    @Test fun separateInstallationsDiscoverRestoreAndRotateTheSameProfileKey() = runBlocking {
        val server = Drive()
        val tv = Device("tv", server)
        val phone = Device("phone", server)
        tv.connect()
        assertTrue(tv.sync.state.value.connected)
        assertFalse(tv.autoSyncOnDisk())
        assertEquals(0, server.requests)
        tv.key = "tv-fixture-key"
        assertTrue(tv.backup())
        val afterBackup = server.requests
        phone.connect()
        assertEquals(afterBackup, server.requests)
        assertFalse(phone.sync.state.value.autoSync)
        assertFalse(Device("phone", server).sync.state.value.autoSync)
        assertEquals(1, server.revision)
        assertTrue(phone.restore())
        assertEquals(tv.key, phone.key)
        assertEquals(1, server.revision)
        phone.key = "phone-fixture-key"
        assertTrue(phone.backup())
        assertTrue(tv.restore())
        assertEquals(phone.key, tv.key)
        assertEquals(2, server.revision)
        val beforeReconnect = server.requests
        phone.connect()
        assertFalse(phone.autoSyncOnDisk())
        assertEquals(beforeReconnect, server.requests)
        val other = Device("tablet", server, "Different profile")
        assertTrue(runCatching { other.restore() }.isFailure)
        assertNull(other.key)
        assertEquals(2, server.revision)
    }
}
