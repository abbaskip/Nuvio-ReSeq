package com.nuvio.tv.ui.screens.tvmix

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AppOnboardingDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launch the real Hilt activity and its session-owned NavHost, beyond the sign-in screen. */
@RunWith(AndroidJUnit4::class)
class TvMixStartupTvTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mainNavigationCanCreateTheSharedMixCoordinator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { AppOnboardingDataStore(context).setHasSeenAuthQrOnFirstLaunch(true) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val mainContent = hasText(context.getString(R.string.experience_mode_choose_title)) or
                hasContentDescription(context.getString(R.string.nav_home))
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodes(mainContent).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
