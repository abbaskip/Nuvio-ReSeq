package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.ondemand.importCatalogBatches
import org.junit.Assert.*
import org.junit.Test
import java.io.Reader

class XtreamCatalogReaderTest {
    @Test fun `array and keyed catalogs deliver the same entries`() {
        val item = """{"stream_id":12,"name":"Film","nested":{"unused":true},"enabled":true}"""
        for (text in listOf("[$item,null,$item]", "{\"0\":$item,\"2\":null,\"7\":$item}")) {
            val entries = mutableListOf<Map<String, String>>()
            readXtreamCatalog(text.reader(), "stream_id", onEntry = entries::add)
            assertEquals(2, entries.size)
            assertEquals("12", entries[0]["stream_id"])
            assertEquals("true", entries[0]["enabled"])
            assertFalse(entries[0].containsKey("nested"))
        }
    }

    @Test fun `invalid records can be reported without losing later valid titles`() {
        var invalid = 0
        val ids = mutableListOf<String>()
        readXtreamCatalog("[{\"stream_id\":1},{},{\"stream_id\":2}]".reader(), "stream_id",
            onInvalidEntry = { invalid++ }) { ids.add(it.getValue("stream_id")) }
        assertEquals(1, invalid)
        assertEquals(listOf("1", "2"), ids)
    }

    @Test fun `truncated or error responses are never accepted as complete empty catalogs`() {
        for (text in listOf("null", "", "<html>error</html>", "{\"error\":\"private-response\"}",
            "{\"user_info\":{\"auth\":0}}", "[{\"stream_id\":1},", "{\"0\":{\"stream_id\":1}", "[] []")) {
            try {
                readXtreamCatalog(text.reader(), "stream_id") {}
                fail("Expected an incomplete catalog")
            } catch (e: XtreamCatalogException) {
                assertFalse(e.message.orEmpty().contains("private-response"))
                assertNull(e.cause)
            }
        }
        readXtreamCatalog("[]".reader(), "stream_id") { fail("Empty catalog") }
        readXtreamCatalog("{}".reader(), "series_id") { fail("Empty catalog") }
    }

    @Test fun `database failure stops parsing and closes the response`() {
        var closed = false
        var delivered = 0
        val input = object : java.io.StringReader("[{\"series_id\":1},{\"series_id\":2}]") {
            override fun close() { closed = true; super.close() }
        }
        val failure = IllegalStateException("database failure")
        try {
            readXtreamCatalog(input, "series_id") { delivered++; throw failure }
            fail("Expected write failure")
        } catch (e: IllegalStateException) { assertSame(failure, e) }
        assertTrue(closed)
        assertEquals(1, delivered)
    }

    @Test fun `224486 entries stream through batches of at most 500`() {
        var received = 0
        var largest = 0
        var commits = 0
        // Generate the response on demand, without allocating a giant JSON string in the test.
        val chunks = sequence {
            yield("[")
            repeat(224486) { i -> yield("${if (i == 0) "" else ","}{\"stream_id\":$i,\"name\":\"Film $i\"}") }
            yield("]")
        }.iterator()
        val input = object : Reader() {
            var chunk = ""
            var at = 0
            override fun read(buffer: CharArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (at == chunk.length) {
                    if (!chunks.hasNext()) return -1
                    chunk = chunks.next(); at = 0
                }
                val n = minOf(len, chunk.length - at)
                chunk.toCharArray(buffer, off, at, at + n); at += n
                return n
            }
            override fun close() {}
        }
        val complete = importCatalogBatches<Map<String, String>>(produce = { emit ->
            readXtreamCatalog(input, "stream_id", onEntry = emit)
            true
        }) { batch ->
            received += batch.size; largest = maxOf(largest, batch.size); commits++
        }
        assertTrue(complete)
        assertEquals(224486, received)
        assertEquals(500, largest)
        assertEquals(449, commits)
    }
}
