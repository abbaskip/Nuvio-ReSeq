package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class SportsNationalFlagTest {
    private fun team(name: String) = TeamInfo("league", "id", name, name, "ABC", null, null, null, null)
    @Test fun `national teams use country flags including home nations and womens teams`() {
        assertEquals("gb-eng", nationalFlagCode(team("England Rugby League")))
        assertEquals("pg", nationalFlagCode(team("Papua New Guinea Rugby League")))
        assertEquals("gb-wls", nationalFlagCode(team("Wales Women")))
        assertEquals("ws", nationalFlagCode(team("Samoa Women's Rugby League")))
        assertEquals("au", nationalFlagCode(team("Australia")))
    }
    @Test fun `club names are never matched by country substring`() {
        assertNull(nationalFlagCode(team("New England Patriots")))
        assertNull(nationalFlagCode(team("Papua New Guinea Hunters")))
        assertNull(nationalFlagCode(team("Georgia Bulldogs")))
        assertNull(nationalFlagCode(null))
    }
}
