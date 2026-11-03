package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.domain.model.TvMix
import com.nuvio.tv.domain.model.TvMixShow
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMixHomeTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeCardsExposeMixNamesPlayAndLongPressManagementWithMissingArtwork() {
        var played: String? = null
        var managed: String? = null
        val first = TvMix("one", "Food & travel", listOf(TvMixShow("tt123", "Example"))).preview()
        val second = TvMix("two", "Sitcoms").preview()
        compose.setContent {
            NuvioTheme {
                val focus = remember { FocusRequester() }
                Row(Modifier.padding(40.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ContentCard(first, Modifier.testTag("classic-mix"), focusRequester = focus,
                        onLongPress = { managed = first.id }, onClick = { played = first.id })
                    GridContentCard(second, onClick = { played = second.id }, modifier = Modifier.testTag("grid-mix"),
                        onLongPress = { managed = second.id })
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        // The tags belong to the card containers; focus and remote actions belong to
        // the actual TV Card inside each container.
        val classic = compose.onNode(hasAnyAncestor(hasTestTag("classic-mix")) and hasClickAction())
        val grid = compose.onNode(hasAnyAncestor(hasTestTag("grid-mix")) and hasClickAction())
        classic.assertIsFocused()
        classic.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals("tvmix:one", played) }
        classic.performKeyInput { keyDown(androidx.compose.ui.input.key.Key.DirectionCenter); advanceEventTime(700); keyUp(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("tvmix:one", managed) }
        classic.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        grid.assertIsFocused()
        grid.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals("tvmix:two", played) }
        grid.performKeyInput { keyDown(androidx.compose.ui.input.key.Key.DirectionCenter); advanceEventTime(700); keyUp(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("tvmix:two", managed) }
    }
}
