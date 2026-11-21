package com.nuvio.tv.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test

class MediaServersSettingsTvTest {
    @get:Rule val compose = createAndroidComposeRule<TvHiltTestActivity>()

    @Test fun allFourProvidersAreAvailableAndSiloOpensItsAddressForm() {
        compose.setContent {
            NuvioTheme { MediaServersSettingsContent(null, {}) }
        }
        for (provider in listOf("Jellyfin", "Emby", "Plex", "Silo")) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(provider))
            compose.onNodeWithText(provider).assertIsDisplayed()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        compose.onNodeWithText("Silo").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        val address = compose.onNodeWithText(context.getString(R.string.silo_address_hint))
        compose.waitUntil(5_000) { address.isDisplayed() }
        compose.onNodeWithText(context.getString(R.string.silo_address_subtitle)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.servers_connect)).assertIsNotEnabled()
    }
}
