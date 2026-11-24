package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SportsMatchCentreTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun basketballCardsStayVisibleAcrossSectionsAndRespectHideScores() {
        val league = "basketball/nba"
        fun side(name: String, home: Boolean) = Side(
            TeamInfo(league, name, name, name, name, null, null, null, null), null, null, false, home
        )
        val game = SportsEvent("test-game", league, 1L, true, GameState.POST, "Denver at Chicago",
            null, "Final", side("DEN", false), side("CHI", true))
        val extras = GameExtras(stats = listOf(StatLine("REBOUNDS", "41", "39", 41.0, 39.0)),
            performers = listOf(Performer("PTS", "Test player", "28 points", "DEN")))
        val hidden = mutableStateOf(false)
        compose.setContent {
            NuvioTheme {
                Row(Modifier.fillMaxSize()) {
                    PinnedMatchStats(game, extras, null, hidden.value, true, Modifier.weight(1f))
                    MatchCentre(game, extras, null, emptyList(), hidden.value, true, true,
                        Modifier.weight(1f).testTag("match-centre"))
                }
            }
        }
        compose.onNodeWithText("TEAM STATS").assertIsDisplayed()
        compose.onNodeWithText("TOP PERFORMERS").assertIsDisplayed()
        compose.onNodeWithText("Team comparison", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("match-centre").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("match-centre").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Player leaders", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("TEAM STATS").assertIsDisplayed()
        compose.onNodeWithText("TOP PERFORMERS").assertIsDisplayed()
        compose.runOnIdle { hidden.value = true }
        compose.onNodeWithText("TEAM STATS").assertDoesNotExist()
        compose.onNodeWithText("TOP PERFORMERS").assertDoesNotExist()
        compose.onNodeWithText("28 points", useUnmergedTree = true).assertDoesNotExist()
    }
}
