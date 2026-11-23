package com.nuvio.tv.ui.components

import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.core.view.descendants
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class WatchedDatePickerTvTest {
    @get:Rule val compose = createComposeRule()
    private var saved: Long? = null

    private fun click(label: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun open() {
        compose.setContent { NuvioTheme {
            val choose = rememberWatchedDatePicker()
            Button(onClick = { choose { saved = it } }) { Text("Mark") }
        } }
        click("Mark")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Mark watched now").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun remoteCanMarkWatchedNow() {
        val before = System.currentTimeMillis()
        open()
        compose.onNodeWithText("Mark watched now").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(requireNotNull(saved) in before..System.currentTimeMillis()) }
    }

    private inline fun <reified T : android.view.View> widget(): T =
        WindowInspector.getGlobalWindowViews().filterIsInstance<ViewGroup>().asSequence()
            .flatMap { it.descendants }.filterIsInstance<T>().single()

    @SdkSuppress(minSdkVersion = 29)
    @Test fun savesCalendarDateWithOrWithoutTime() {
        open()
        val date = LocalDate.of(2021, 7, 18)
        for (withTime in listOf(false, true)) {
            if (withTime) click("Mark")
            click(if (withTime) "Choose date and time" else "Choose date")
            compose.runOnIdle {
                widget<DatePicker>().updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
            }
            if (withTime) {
                click("Choose time")
                compose.runOnIdle {
                    widget<TimePicker>().apply { hour = 16; minute = 35 }
                }
            }
            click("Save")
            compose.runOnIdle {
                val time = if (withTime) LocalTime.of(16, 35) else null
                assertEquals(watchedInstant(date, time, ZoneId.systemDefault()), saved)
            }
        }
    }
}
