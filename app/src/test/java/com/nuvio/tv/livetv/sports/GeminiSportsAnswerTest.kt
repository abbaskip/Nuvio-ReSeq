package com.nuvio.tv.livetv.sports

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class GeminiSportsAnswerTest {
    private fun response(text: String, sources: String = """[{"web":{"uri":"https://example.com/source","title":"Official source"}}]""") =
        """{"candidates":[{"content":{"parts":[{"text":"$text"}]},"groundingMetadata":{"groundingChunks":$sources,"searchEntryPoint":{"renderedContent":"<div>Google search suggestions</div>"}}}]}"""
    @Test fun `sourced answer and original search suggestions are displayed together`() {
        val html = GeminiSportsAnswer.html(response("Score unavailable"))
        assertTrue(html.contains("Score unavailable"))
        assertTrue(html.contains("Official source"))
        assertTrue(html.contains("<div>Google search suggestions</div>"))
    }
    @Test fun `model text is escaped and unsupported source schemes are rejected`() {
        val html = GeminiSportsAnswer.html(response("<script>bad()</script>"))
        assertFalse(html.contains("<script>bad()"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
    @Test(expected = IOException::class) fun `unsourced answer cannot be shown as a grounded lookup`() {
        GeminiSportsAnswer.html(response("Made up score", "[]"))
    }
}
