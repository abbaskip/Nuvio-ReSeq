package com.nuvio.tv.livetv.sports

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsTextNormalizationTest {
    private fun legacy(text: String): String {
        val plain = Normalizer.normalize(SportsCatalog.plainSmall(text), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return " " + plain.lowercase(Locale.ROOT).replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex(" +"), " ") + " "
    }
    @Test fun `punctuation accents raised guide marks and ampersands retain existing word boundaries`() {
        listOf("", "   ", "&&", "A&B", "a & & b", "FOXSPORTS501 HD", "Café–Rugby", "İstanbul", "Straße",
            "LAL @ LAC", "Live: NRL ᴸᴵⱽᴱ", "A\u0301B", "a\uD834\uDD65b", "Ⓐ ½ ﬀ ⚽").forEach { text ->
            assertEquals(text, legacy(text), SportsCatalog.norm(text))
        }
    }
    @Test fun `optimized guide normalization agrees with prior behaviour across mixed programme text`() {
        val random = Random(42)
        val pieces = listOf("Sports", "&", " ", ":", "Café", "\n", "NRL", "501", "ᴺᴱᵂ", "ᶫᶦᵛᵉ", "ß", "İ", "★", "\u0301", "vs", "Clippers")
        repeat(500) {
            val text = (0 until random.nextInt(1, 30)).joinToString("") { pieces.random(random) }
            assertEquals(legacy(text), SportsCatalog.norm(text))
        }
    }

    @Test fun `ASCII text and raised labels retain expansion and removal`() {
        val ascii = (0..127).map(Int::toChar).joinToString("")
        assertEquals(ascii, SportsCatalog.plainSmall(ascii))
        assertEquals(ascii, SportsCatalog.withoutSmallMarks(ascii))
        assertEquals("Café live ", SportsCatalog.plainSmall("Caféᴸᴵⱽᴱ"))
        assertEquals("Café", SportsCatalog.withoutSmallMarks("Caféᴸᴵⱽᴱ"))
        assertEquals("NBAᴸᴬᴸ", SportsCatalog.withoutSmallMarks("NBAᴸᴬᴸ"))
        assertEquals("NBA lal ", SportsCatalog.plainSmall("NBAᴸᴬᴸ"))
    }

    @Test fun `taxonomy compatibility normalization preserves its distinct punctuation and unicode rules`() {
        fun previous(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
        val random = Random(7)
        val pieces = listOf("Café", "A&B", "NRL", " ", "Ⓐ", "½", "ﬀ", "ᴸᴵⱽᴱ", "ʟɪᴠᴇ", "İ", "\u0301", "★", "\n")
        repeat(500) {
            val text = (0 until random.nextInt(1, 20)).joinToString("") { pieces.random(random) }
            assertEquals(previous(text), SportsTaxonomy.normalize(text))
        }
    }
}
