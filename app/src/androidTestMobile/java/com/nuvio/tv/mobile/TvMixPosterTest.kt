package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.TvMix
import com.nuvio.tv.domain.model.TvMixShow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TvMixPosterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mixNameIsOnlyBelowTheCoverAndIsAccessibleWithCaptionsHidden() {
        val showCaptions = mutableStateOf(true)
        var opened = false
        val shows = listOf("ic_menu_camera", "ic_menu_gallery", "ic_menu_compass", "ic_menu_mapmode").map {
            TvMixShow(it, it, poster = "android.resource://android/drawable/$it")
        }
        val mix = TvMix("houses", "Houses and Homes", shows).preview()
        compose.setContent {
            Poster(mix, Modifier.width(120.dp).testTag("mix"), open = { opened = true },
                more = {}, showCaption = showCaptions.value)
        }
        compose.onAllNodesWithText(mix.name, useUnmergedTree = true).assertCountEquals(1)
        val caption = compose.onNodeWithText(mix.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("mix").getUnclippedBoundsInRoot()
        assertTrue(caption.top >= card.top + 180.dp)

        compose.runOnIdle { showCaptions.value = false }
        compose.onAllNodesWithText(mix.name, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription(mix.name).performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}

