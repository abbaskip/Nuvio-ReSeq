package com.nuvio.tv.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.nuvio.tv.LocalRootContentInset
import com.nuvio.tv.LocalSidebarRoutes
import org.junit.Rule
import org.junit.Test

class LiveFullscreenInsetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sportsReclaimsMenuSpaceDuringPlayback() = checkFullscreenTransition(Screen.Sports.route)

    @Test fun guideReclaimsMenuSpaceDuringPlayback() = checkFullscreenTransition(Screen.LiveTv.route)

    private fun checkFullscreenTransition(route: String) {
        val fullscreen = mutableStateOf(false)
        val menuWidth = 72.dp
        compose.setContent {
            CompositionLocalProvider(
                LocalRootContentInset provides menuWidth,
                LocalSidebarRoutes provides rootSidebarRoutes(true, fullscreen.value)
            ) {
                NavHost(rememberNavController(), startDestination = route) {
                    rootComposable(route) { Box(Modifier.fillMaxSize().testTag("content")) }
                }
            }
        }
        val content = compose.onNodeWithTag("content")
        content.assertLeftPositionInRootIsEqualTo(menuWidth)
        compose.runOnIdle { fullscreen.value = true }
        content.assertLeftPositionInRootIsEqualTo(0.dp)
        compose.runOnIdle { fullscreen.value = false }
        content.assertLeftPositionInRootIsEqualTo(menuWidth)
    }
}
