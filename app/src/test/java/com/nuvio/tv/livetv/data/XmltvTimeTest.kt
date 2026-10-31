package com.nuvio.tv.livetv.data

import org.junit.Assert.*
import org.junit.Test

class XmltvTimeTest {
    @Test fun `guide offsets resolve to the same UTC instant`() {
        assertEquals(XmltvParser.parseTime("20261029080000 +0000"), XmltvParser.parseTime("20261029190000 +1100"))
        assertEquals(XmltvParser.parseTime("20261029080000 +0000"), XmltvParser.parseTime("20261029030000 -0500"))
    }
    @Test fun `missing or invalid guide timestamps are rejected`() {
        assertNull(XmltvParser.parseTime(null))
        assertNull(XmltvParser.parseTime("not-a-time"))
        assertNull(XmltvParser.parseTime("20261329190000 +0000"))
    }
}
