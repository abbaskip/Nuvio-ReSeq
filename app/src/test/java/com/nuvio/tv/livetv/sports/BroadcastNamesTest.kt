package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class BroadcastNamesTest {
    @Test fun `quality prefixes and compact names resolve to the same broadcaster`() {
        assertTrue(BroadcastNames.matches("Fox Sports 501", "AU: FOXSPORTS501 FHD"))
        assertTrue(BroadcastNames.matches("Sky Sports Main Event", "UK | SkySportsMainEvent UHD"))
        assertTrue(BroadcastNames.matches("TNT Sports 1", "UK: BT Sport 1 HD"))
        assertTrue(BroadcastNames.matches("ESPN2", "US: ESPN 2 FHD"))
    }
    @Test fun `word and number aliases retain separate streaming services`() {
        assertTrue(BroadcastNames.matches("BBC One", "UK: BBC1 HD"))
        assertTrue(BroadcastNames.matches("Seven Network", "AU: 7HD"))
        assertTrue(BroadcastNames.matches("Nine Network", "AU: Channel 9 HD"))
        assertFalse(BroadcastNames.matches("Seven", "AU: 7plus"))
        assertFalse(BroadcastNames.matches("Nine", "AU: 9Now"))
    }
    @Test fun `numbers and plus services remain distinct`() {
        assertFalse(BroadcastNames.matches("Fox Sports 501", "AU: FOXSPORTS502 HD"))
        assertFalse(BroadcastNames.matches("Sky Sports 1", "NZ: Sky Sports 10 HD"))
        assertFalse(BroadcastNames.matches("ESPN", "US: ESPN+ HD"))
        assertFalse(BroadcastNames.matches("Fox", "US: Fox News HD"))
    }
    @Test fun `Australian and NZ broadcasters cannot be conflated by the shared UI region`() {
        val au = SportsBroadcast("Fox Sports 1", "Australia")
        assertTrue(BroadcastNames.compatible(au, "AU: Fox Sports 1"))
        assertFalse(BroadcastNames.compatible(au, "NZ: Fox Sports 1"))
        assertFalse(BroadcastNames.compatible(au, "US: Fox Sports 1"))
    }

    @Test fun `station call signs and event participant suffixes preserve broadcaster identity`() {
        assertTrue(BroadcastNames.matches("KTLA", "CA | Los Angeles | CW KTLA"))
        assertTrue(BroadcastNames.matches("Spectrum Sports Net", "US: Spectrum SportsNet Alternate"))
        assertTrue(BroadcastNames.matches("Spectrum Sports Net", "US: Spectrum SportsNet Lakers", listOf("lakers", "clippers")))
        assertFalse(BroadcastNames.matches("Spectrum Sports Net", "US: Spectrum SportsNet LA Dodgers", listOf("lakers", "clippers")))
        assertFalse(BroadcastNames.matches("ESPN", "US: ESPN 2 West"))
    }
}
