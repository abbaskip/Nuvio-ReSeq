package com.nuvio.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.tv.livetv.calendar.CalendarItem
import com.nuvio.tv.livetv.calendar.CalendarKind
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.Side
import com.nuvio.tv.livetv.sports.TeamInfo
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class MobileLiveGuideTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calendarShowsBothTeamsEvenWhenNeitherHasArtwork() {
        val teams = listOf("Hull", "Brighton").map { name ->
            Side(TeamInfo("league", name, name, name, name.take(3), null, null, null, null), null, null, false, name == "Hull")
        }
        val item = CalendarItem("match", "guide", "Hull v Brighton", 0L, CalendarKind.SPORT, guideSides = teams)
        compose.setContent { CalendarEventArt(item) }
        compose.onNodeWithContentDescription("Hull", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Brighton", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun guideOverlayLetsVideoColorThroughTheLiveProgrammeAndUsesTheSelectedTheme() {
        var video by mutableStateOf(Color.Blue)
        var accent by mutableStateOf(Color.Magenta)
        val channel = LiveChannel("test", "source", "Provider", "Fixture channel", null, null, null, "sport", "Sport", 1, "local")
        val listings = mapOf(channel.key to listOf(EpgProgram(0L, 3_600_000L, "Programme")))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = accent)) {
                Box(Modifier.fillMaxWidth().background(video).testTag("timeline")) {
                    ChannelTimeline(listOf(channel), listings, now = 12_000L, start = 0L, slots = 2,
                        use24h = true, list = rememberLazyListState(), scroll = rememberScrollState(), onTune = {},
                        onProgram = { _, _ -> }, playingKey = channel.key, overlay = true)
                }
            }
        }
        compose.onNodeWithText("Programme").assertIsDisplayed()
        fun pixel() = compose.onNodeWithTag("timeline").captureToImage().asAndroidBitmap().let {
            it.getPixel(it.width * 2 / 3, it.height - 4)
        }
        val blueVideo = pixel()
        compose.runOnIdle { video = Color.Green }
        val greenVideo = pixel()
        assertNotEquals(blueVideo, greenVideo)
        compose.runOnIdle { accent = Color.Cyan }
        assertNotEquals(greenVideo, pixel())
    }
}

