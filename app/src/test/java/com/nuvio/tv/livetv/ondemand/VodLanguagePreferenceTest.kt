package com.nuvio.tv.livetv.ondemand

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VodLanguagePreferenceTest {
    @Test fun `language is inherited from folder when episode and show have no tags`() {
        listOf("EN | Cooking", "English Series", "UK - Lifestyle", "en", "[en] TV SERIES SD").forEach {
            assertEquals(it, setOf("en"), VodLanguagePreference.resolve(null, "Episode 1", "Jamie's 30 Minute Meals", it))
        }
        assertEquals(setOf("de"), VodLanguagePreference.folder("German Series"))
        assertEquals(setOf("fr"), VodLanguagePreference.folder("FR | Cuisine"))
    }

    @Test fun `audio then episode then series overrides folder`() {
        assertEquals(setOf("de"), VodLanguagePreference.resolve("ger", "Episode [EN]", "Show [FR]", "English Series"))
        assertEquals(setOf("en"), VodLanguagePreference.resolve("und", "Episode [en-AU]", "Show [FR]", "German Series"))
        assertEquals(setOf("fr"), VodLanguagePreference.resolve(null, "Episode 1", "FR - Show", "English Series"))
        assertEquals(setOf("en", "fr"), VodLanguagePreference.title("Show [en/fr]"))
        assertEquals(setOf("uk"), VodLanguagePreference.audio("uk"))
    }

    @Test fun `subtitle labels and ordinary title words do not claim audio languages`() {
        listOf("It", "Us", "Made in Heaven", "Bake It Til You Make It").forEach {
            assertTrue(it, VodLanguagePreference.title(it).isEmpty())
            assertTrue(it, VodLanguagePreference.folder(it).isEmpty())
        }
        assertTrue(VodLanguagePreference.title("Show [English subtitles]").isEmpty())
        assertTrue(VodLanguagePreference.folder("FR SUB").isEmpty())
        assertTrue(VodLanguagePreference.title("Show VOSTFR").isEmpty())
        assertTrue(VodLanguagePreference.folder("TV SERIES SD").isEmpty())
    }

    @Test fun `system language precedes English then unknown then other languages with stable ties`() {
        val sources = listOf(setOf("es"), emptySet(), setOf("en"), setOf("fr"), setOf("de"), setOf("en", "fr"))
        assertEquals(listOf(sources[3], sources[5], sources[2], sources[1], sources[0], sources[4]),
            sources.sortedBy { VodLanguagePreference.rank(it, Locale.CANADA_FRENCH) })
        assertEquals(listOf(sources[2], sources[5], sources[1], sources[0], sources[3], sources[4]),
            sources.sortedBy { VodLanguagePreference.rank(it, Locale.forLanguageTag("en-AU")) })
    }
}
