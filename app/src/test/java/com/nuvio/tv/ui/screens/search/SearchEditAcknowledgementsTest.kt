package com.nuvio.tv.ui.screens.search

import org.junit.Assert.*
import org.junit.Test

class SearchEditAcknowledgementsTest {
    @Test fun delayedEchoIsRecognizedAfterANewerEditWasSent() {
        val echoes = SearchEditAcknowledgements()
        echoes.sent("Fixtu")
        echoes.sent("Fixtur")
        assertTrue(echoes.acknowledge("Fixtu"))
        assertTrue(echoes.acknowledge("Fixtur"))
        assertFalse(echoes.acknowledge("restored query"))
    }

    @Test fun coalescedLatestEchoRetiresSkippedIntermediateEdits() {
        val echoes = SearchEditAcknowledgements()
        echoes.sent("Fi")
        echoes.sent("Fix")
        echoes.sent("Fixture")
        assertTrue(echoes.acknowledge("Fixture"))
        // A later external selection of an older query must be applied normally.
        assertFalse(echoes.acknowledge("Fi"))
    }

    @Test fun realBackspaceAndClearAreAcknowledgedNormally() {
        val echoes = SearchEditAcknowledgements()
        echoes.sent("Fixture")
        echoes.sent("Fixtur")
        echoes.sent("")
        assertTrue(echoes.acknowledge("Fixtur"))
        assertTrue(echoes.acknowledge(""))
        assertFalse(echoes.acknowledge("restored"))
    }

    @Test fun restoringAnUnsubmittedQueryIsNeverSuppressed() {
        val echoes = SearchEditAcknowledgements()
        echoes.sent("Fixture")
        assertFalse(echoes.acknowledge("Recent search"))
        assertTrue(echoes.acknowledge("Fixture"))
    }
}
