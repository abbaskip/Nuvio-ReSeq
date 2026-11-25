package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.IptvStorageTestEntryPoint
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.M3uParser
import com.nuvio.tv.livetv.data.StalkerPortalClient
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.storage.StorageTestContext
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StalkerDeviceIdsTvTest {
    @get:Rule val compose = createAndroidComposeRule<TvHiltTestActivity>()

    private val portal = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val params = session.parameters
            val body = when (params["action"]?.singleOrNull()) {
                "handshake" -> """{"token":"fixture-token"}"""
                "get_profile" -> if (params["device_id"] == listOf("AbC123") && params["device_id2"] == listOf("xYz789"))
                    """{"status":0}""" else """{"status":1}"""
                "get_genres" -> "[]"
                "get_all_channels" -> """{"data":[{"id":"42","name":"Test channel","cmd":"ffmpeg http://localhost/ch/42"}]}"""
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"js":$body}""")
        }
    }

    @Before fun startPortal() = portal.start()
    @After fun stopPortal() = portal.stop()

    @Test fun addReopenAndEditBothDeviceIdsWithTheRemote() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = EntryPointAccessors.fromApplication(app, IptvStorageTestEntryPoint::class.java).profileManager()
        val context = StorageTestContext(app)
        val prefs = LiveTvPreferences(context, profiles)
        var editSource by mutableStateOf<PlaylistSource?>(null)
        var saved: PlaylistSource? = null
        compose.setContent { NuvioTheme {
            key(editSource) {
                StalkerSourceDialog(editSource, onDismiss = {}) { saved = it }
            }
        } }
        enter("Portal URL", "http://127.0.0.1:${portal.listeningPort}/c/")
        enter("MAC address", "00:1a:79:00:00:01")
        enter("Device ID (if required)", " AbC123 ")
        val first = field("Device ID (if required)")
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.performKeyInput { pressKey(Key.DirectionDown) }
        field("Device ID 2 (if required)").assertIsFocused()
        enter("Device ID 2 (if required)", " xYz789 ")
        save()
        val added = requireNotNull(saved)
        assertEquals("AbC123", added.stalkerDeviceId)
        assertEquals("xYz789", added.stalkerDeviceId2)
        val stored = runBlocking {
            prefs.updatePlaylists { it + added }
            prefs.currentPlaylists().single()
        }
        val playlist = context.cacheDir.resolve("portal.m3u")
        runBlocking { StalkerPortalClient().writePlaylist(stored, playlist) }
        assertEquals("Test channel", playlist.bufferedReader().use { M3uParser.parse(it) }.entries.single().name)
        compose.runOnIdle { saved = null; editSource = stored }
        compose.onNodeWithText("Edit Stalker / Ministra portal").assertExists()
        field("Device ID (if required)").assert(hasAnyDescendant(hasText("AbC123")))
        field("Device ID 2 (if required)").assert(hasAnyDescendant(hasText("xYz789")))
        enter("Device ID (if required)", "")
        enter("Device ID 2 (if required)", "updated")
        save()
        val edited = requireNotNull(saved)
        assertEquals(added.copy(stalkerDeviceId = "", stalkerDeviceId2 = "updated"), edited)
        runBlocking {
            val synced = LiveTvPreferences(StorageTestContext(app), profiles)
            synced.importFromSync(prefs.exportForSync())
            prefs.updatePlaylists { listOf(edited) }
            assertEquals(setOf(added.id), synced.importFromSync(prefs.exportForSync()))
            assertEquals(edited, synced.currentPlaylists().single())
        }
    }

    private fun field(label: String): SemanticsNodeInteraction {
        val caption = compose.onNodeWithText(label)
        val captionId = caption.fetchSemanticsNode().id
        val rows = caption.onParent().onChildren()
        val index = rows.fetchSemanticsNodes().indexOfFirst { it.id == captionId }
        return rows[index + 1].assert(hasClickAction())
    }

    private fun enter(label: String, value: String) {
        val control = field(label).performScrollTo()
        control.performSemanticsAction(SemanticsActions.RequestFocus)
        control.performKeyInput { pressKey(Key.DirectionCenter) }
        val editor = compose.onNode(hasSetTextAction() and isFocused(), useUnmergedTree = true)
        editor.performTextReplacement(value)
        editor.performImeAction()
    }

    private fun save() {
        val button = compose.onNodeWithText("Save")
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }
}
