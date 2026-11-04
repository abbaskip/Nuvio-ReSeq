package com.nuvio.tv.livetv.storage

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.IptvStorageTestEntryPoint
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.ondemand.OnDemandDatabase
import com.nuvio.tv.livetv.ondemand.OnDemandRepository
import com.nuvio.tv.livetv.ondemand.VodImportSettingsDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.settings.IptvStorageDialog
import com.nuvio.tv.livetv.data.IptvStorageSnapshot
import com.nuvio.tv.livetv.data.StorageCategory
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvStorageUiTvTest {
    @get:Rule val compose = createAndroidComposeRule<TvHiltTestActivity>()

    @Test fun storageActionsAreReachableWithTheRemoteAndSizesStayReadable() {
        var measured = 0
        var cleaned = 0
        val report = IptvStorageSnapshot(listOf(StorageCategory("On Demand catalogue and journal", 400L * 1024 * 1024),
            StorageCategory("TV guide and journal", 50L * 1024 * 1024), StorageCategory("Recordings on this device (kept)", 12345)))
        compose.setContent { NuvioTheme {
            IptvStorageDialog(report, false, "Ready", { measured++ }, { cleaned++ }, {})
        } }
        compose.waitForIdle()
        compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Refresh sizes").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionCenter); pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, measured); assertEquals(1, cleaned) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Recordings on this device (kept): 0.0 MB"))
        compose.onNodeWithText("Recordings on this device (kept): 0.0 MB").assertIsDisplayed()
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "iptv-storage-controls.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun remoteControlsImportModesAndCategorySelectionsWithoutChangingOtherSourceFields() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = StorageTestContext(app)
        val profiles = EntryPointAccessors.fromApplication(app.applicationContext, IptvStorageTestEntryPoint::class.java).profileManager()
        val preferences = LiveTvPreferences(context, profiles)
        FixtureXtreamServer().use { provider ->
            OnDemandDatabase(context).use { database ->
                val repository = OnDemandRepository(database, preferences)
                val original = PlaylistSource("fixture-ui", "Synthetic provider", "", importVod = true,
                    xtreamServer = provider.base, xtreamUsername = "fixture", xtreamPassword = "fixture")
                kotlinx.coroutines.runBlocking { preferences.updatePlaylists { listOf(original) } }
                var saved: PlaylistSource? = null
                compose.setContent { NuvioTheme {
                    VodImportSettingsDialog(original, repository, onSave = { saved = it }, onDismiss = {})
                } }
                compose.waitForIdle()
                // A direct test-hosted dialog has no opener's key-up. Prime that guard with navigation.
                compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionDown) }
                compose.onNodeWithText("Source matching only").performSemanticsAction(SemanticsActions.RequestFocus)
                compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionCenter) }
                activateWithRemote("Import movies")
                activateWithRemote("Series categories")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("English").fetchSemanticsNodes().isNotEmpty() }
                activateWithRemote("French")
                activateWithRemote("Apply categories")
                compose.onNode(hasScrollAction()).performScrollToNode(hasText("Save and update imports"))
                compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
                    File(app.filesDir, "iptv-import-controls.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                activateWithRemote("Save and update imports")
                compose.runOnIdle {
                    val result = requireNotNull(saved)
                    assertTrue(result.vodSourceOnly)
                    assertFalse(result.vodMovies)
                    assertTrue(result.vodSeries)
                    assertEquals(setOf("en"), result.vodSeriesCategories)
                    assertEquals(original.xtreamServer, result.xtreamServer)
                    assertEquals(original.importLive, result.importLive)
                }
                kotlinx.coroutines.runBlocking { preferences.updatePlaylists { emptyList() } }
            }
        }
    }

    private fun activateWithRemote(title: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionCenter) }
    }
}
