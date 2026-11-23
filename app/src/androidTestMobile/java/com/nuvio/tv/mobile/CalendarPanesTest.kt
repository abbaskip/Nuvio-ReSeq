package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CalendarPanesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedDateStaysVisibleWhenScrollingThroughEvents() {
        compose.setContent {
            CalendarPanes(controls = { Text("Selected date", Modifier.height(90.dp)) }, agenda = {
                LazyColumn(Modifier.fillMaxSize().testTag("agenda")) {
                    items(40) { Text("Event $it", Modifier.fillMaxWidth().height(64.dp)) }
                }
            })
        }
        val date = compose.onNodeWithText("Selected date")
        val before = date.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("agenda").performScrollToIndex(25)
        compose.onNodeWithText("Event 25").assertIsDisplayed()
        date.assertIsDisplayed()
        assertEquals(before, date.fetchSemanticsNode().boundsInRoot)
    }
}
