package com.nuvio.tv.core.plugin

import org.junit.Assert.*
import org.junit.Test

class SourceLanguagesTest {
    @Test fun regionalCodesAndCloudstreamAliasesMatchTheirBaseLanguage() {
        for ((raw, expected) in mapOf(" pt-BR " to "pt", "zh_TW" to "zh", "mx" to "es", "jp" to "ja", "in" to "id")) {
            assertEquals(expected, SourceLanguages.normalize(raw))
            assertTrue(SourceLanguages.allows(raw, setOf(expected)))
        }
    }
    @Test fun unknownAndMultilingualSourcesRemainAvailable() {
        for (language in listOf(null, "", "multi", "und", "all")) assertTrue(SourceLanguages.allows(language, setOf("en")))
        assertFalse(SourceLanguages.allows("hi", setOf("en")))
        assertTrue(SourceLanguages.allows("hi", emptySet()))
    }
}
