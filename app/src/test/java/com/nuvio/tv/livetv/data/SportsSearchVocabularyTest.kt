package com.nuvio.tv.livetv.data

import org.junit.Assert.*
import org.junit.Test

class SportsSearchVocabularyTest {
    @Test fun `whole phrases covered by a shorter phrase are redundant but substrings are not`() {
        assertEquals(listOf("rugby","league","NBA"),SportsSearchVocabulary.minimalCover(listOf(
            "rugby league","rugby","women's super league","league","NBA","NBA Basketball")))
        assertEquals(listOf("ball","football"),SportsSearchVocabulary.minimalCover(listOf("ball","football")))
    }
    @Test fun `punctuation follows FTS tokens and non ASCII spellings remain distinct`() {
        assertEquals(listOf("women's","ＮＢＡ"),SportsSearchVocabulary.minimalCover(
            listOf("women's","women s super league","ＮＢＡ")))
        assertEquals(listOf("nba"),SportsSearchVocabulary.compatibilityWords("ＮＢＡ"))
        assertEquals(listOf("off"),SportsSearchVocabulary.compatibilityWords("oﬀ"))
    }
}
