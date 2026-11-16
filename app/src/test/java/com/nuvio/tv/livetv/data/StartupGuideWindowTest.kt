package com.nuvio.tv.livetv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupGuideWindowTest {
    @Test fun `updated source files keep committed listings available during import`() {
        val old = "4|true|24|4|primary:100:1,secondary:200:3,|17|50:1234"
        val updated = old.replace("primary:100:1", "primary:110:2")
        assertTrue(sameGuideWithUpdatedFiles(old, updated))
        assertFalse(sameGuideWithUpdatedFiles(old, updated.replace("primary", "replacement")))
        assertFalse(sameGuideWithUpdatedFiles(old, updated.replace("secondary:200:3,", "")))
        assertFalse(sameGuideWithUpdatedFiles(old, updated.replace("|17|", "|18|")))
        assertFalse(sameGuideWithUpdatedFiles(old, updated.replace("50:1234", "51:1240")))
    }
}
