package com.nuvio.tv.mobile

import android.widget.DatePicker
import android.widget.TimePicker
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.*
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileEpisodeSheetTest {
    @get:Rule val compose = createComposeRule()
    private val overview = "The two crews retrace their steps along the coast, following clues scattered by the storm. ".repeat(6).trim()
    private val identity = EpisodeIdentity(55, 42, "dvd", EpisodeCoordinates(2, 1), EpisodeCoordinates(1, 3),
        providerVideoId = "show:1:3", providerCoordinates = EpisodeCoordinates(1, 3), canonicalEpisodeIds = listOf(55, 56))
    private val video = Video("show:1:3", "The crossing", null, null, season = 2, episode = 1, overview = overview, episodeIdentity = identity)
    private val meta = Meta("show", ContentType.SERIES, name = "Coast", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
        runtime = null, director = emptyList(), cast = emptyList(), videos = listOf(video), country = null, awards = null, language = null, links = emptyList())
    private val pause = WatchProgress("show", "series", "Coast", null, null, null, video.id, 1, 3, video.title,
        40, 100, 1_700_000_000_000, deviceWatchEventId = "one-viewing")
    private var mutation: MetaDetailsEvent? = null
    private var removal: Pair<WatchProgress, Boolean>? = null

    private fun open() {
        val state = MetaDetailsUiState(meta = meta, selectedSeason = 2, seasons = listOf(1, 2), episodesForSeason = listOf(video),
            episodeProgressMap = mapOf((2 to 1) to pause))
        val tracking = state.trackingForEpisode(video)
        compose.setContent {
            MaterialTheme {
                EpisodeTile(video, tracking.watched, .4f, null, true, {}) { close ->
                    EpisodeSheetActions(video, tracking, state, meta, false, play = {},
                        send = { mutation = it; close() }, remove = { watch, last -> removal = watch to last; close() }, comments = {})
                }
            }
        }
        more()
    }
    private fun more() = compose.onNode(hasText("The crossing") and hasClickAction()).performTouchInput { longClick() }
    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test fun fullSynopsisAndIndependentUndoActionsForTheExactSelectedViewing() {
        open()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(overview, useUnmergedTree = true).onLast()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 2)
        assertFalse(layouts.single().hasVisualOverflow)
        tap("Remove partial watch")
        compose.runOnIdle { assertSame(pause, removal!!.first); assertFalse(removal!!.second) }
        more()
        compose.onNodeWithText("Remove last watch", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertSame(pause, removal!!.first); assertTrue(removal!!.second) }
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }

    @androidx.annotation.RequiresApi(29)
    private inline fun <reified T : View> confirmPicker(crossinline edit: (T) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var confirmed = false
        compose.waitUntil(5000) {
            instrumentation.runOnMainSync {
                for (window in WindowInspector.getGlobalWindowViews()) {
                    val picker = window.descendants().filterIsInstance<T>().firstOrNull() ?: continue
                    edit(picker)
                    confirmed = window.findViewById<View>(android.R.id.button1).performClick()
                    break
                }
            }
            confirmed
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun cancelAndDateOnlyAndDateTimeAndNowUseTheChosenEpisode() {
        open()
        tap("Mark as watched")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(mutation) }
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).minusDays(7)
        for (includeTime in listOf(false, true)) {
            tap("Mark as watched")
            compose.onNodeWithText("Choose date").performClick()
            confirmPicker<DatePicker> { it.updateDate(day.year, day.monthValue - 1, day.dayOfMonth) }
            if (includeTime) {
                compose.onNodeWithText("Add time").performClick()
                confirmPicker<TimePicker> {
                    it.hour = 14
                    it.minute = 25
                }
            }
            compose.onNodeWithText("Save watch").performClick()
            val local = day.atTime(if (includeTime) LocalTime.of(14, 25) else LocalTime.MIDNIGHT)
            compose.runOnIdle {
                assertEquals(MetaDetailsEvent.OnToggleEpisodeWatched(video, local.atZone(zone).toInstant().toEpochMilli()), mutation)
            }
            more()
        }
        tap("Mark as watched")
        val before = System.currentTimeMillis()
        compose.onNodeWithText("Watched now").performClick()
        compose.runOnIdle {
            val mark = mutation as MetaDetailsEvent.OnToggleEpisodeWatched
            assertEquals(video, mark.video)
            assertTrue(mark.watchedAt!! in before..System.currentTimeMillis())
        }
    }
}
