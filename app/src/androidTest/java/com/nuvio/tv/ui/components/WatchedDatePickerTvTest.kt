package com.nuvio.tv.ui.components

import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
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

    private fun open() {
        compose.setContent { NuvioTheme {
            val choose = rememberWatchedDatePicker()
            Button(onClick = { choose { saved = it } }) { Text("Mark") }
        } }
        compose.onNodeWithText("Mark").performClick()
    }

    @Test fun remoteCanMarkWatchedNow() {
        val before = System.currentTimeMillis()
        open()
        compose.onNodeWithText("Mark watched now").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(requireNotNull(saved) in before..System.currentTimeMillis()) }
    }

    @Test fun savesCalendarDateWithOrWithoutTime() {
        open()
        val date = LocalDate.of(2021, 7, 18)
        for (withTime in listOf(false, true)) {
            if (withTime) compose.onNodeWithText("Mark").performClick()
            compose.onNodeWithText(if (withTime) "Choose date and time" else "Choose date").performClick()
            onView(isAssignableFrom(DatePicker::class.java)).check { view, _ ->
                (view as DatePicker).updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
            }
            if (withTime) {
                compose.onNodeWithText("Choose time").performClick()
                onView(isAssignableFrom(TimePicker::class.java)).check { view, _ ->
                    (view as TimePicker).apply { hour = 16; minute = 35 }
                }
            }
            compose.onNodeWithText("Save").performClick()
            compose.runOnIdle {
                val time = if (withTime) LocalTime.of(16, 35) else null
                assertEquals(watchedInstant(date, time, ZoneId.systemDefault()), saved)
            }
        }
    }
}
