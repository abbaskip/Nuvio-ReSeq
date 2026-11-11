package com.nuvio.tv.core.usenet

import java.io.FilterInputStream
import java.io.FilterReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.PushbackReader
import java.io.Reader
import java.nio.charset.Charset

internal class NzbBoundedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    var count = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) charge(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        return `in`.read(b, off, minOf(len.toLong(), limit - count + 1).toInt()).also { if (it > 0) charge(it) }
    }
    private fun charge(n: Int) { count += n; if (count > limit) throw NzbTooLarge() }
    override fun skip(n: Long): Long {
        val buffer = ByteArray(minOf(n.coerceAtLeast(0), 8192).toInt())
        var skipped = 0L
        while (skipped < n) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (count < 0) break
            skipped += count
        }
        return skipped
    }
}

internal fun nzbReader(input: InputStream): Reader {
    val stream = PushbackInputStream(input, 256)
    val prefix = ByteArray(256)
    var size = 0
    while (size < prefix.size) {
        val n = stream.read(prefix, size, prefix.size - size)
        if (n < 0) break
        size += n
    }
    stream.unread(prefix, 0, size)
    val encoding = when {
        size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> "UTF-16LE"
        size >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> "UTF-16BE"
        size >= 4 && prefix[0] == 0.toByte() && prefix[1] == '<'.code.toByte() -> "UTF-16BE"
        size >= 4 && prefix[0] == '<'.code.toByte() && prefix[1] == 0.toByte() -> "UTF-16LE"
        else -> Regex("""encoding\s*=\s*["']([^"']+)["']""")
            .find(String(prefix, 0, size, Charsets.ISO_8859_1))?.groupValues?.get(1) ?: "UTF-8"
    }
    val charset = try { Charset.forName(encoding) }
        catch (_: Exception) { throw UsenetFailure("Unsupported NZB text encoding.") }
    val reader = PushbackReader(InputStreamReader(stream, charset), 1)
    val first = reader.read()
    if (first >= 0 && first != 0xfeff) reader.unread(first)
    return NzbSafeReader(reader)
}

/** Reject even unused declarations, including across decoder/SAX buffer boundaries.
 * Bound a lexical XML token too: SAX otherwise accumulates arbitrarily large attributes,
 * names and comments internally before invoking the bounded application handlers.
 */
private class NzbSafeReader(reader: Reader) : FilterReader(reader) {
    private val forbidden = "<!ENTITY"
    private var matched = 0
    private var markup = 0
    private var quote = '\u0000'
    private val prefix = StringBuilder(9)
    private var brackets = 0
    private var previous = '\u0000'
    private var beforePrevious = '\u0000'
    private var thirdPrevious = '\u0000'
    private var dtdComment = 0
    private var dtdInstruction = false
    override fun read(): Int = super.read().also { if (it >= 0) check(it.toChar()) }
    override fun read(b: CharArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
        for (i in off until off + maxOf(0, n)) check(b[i])
    }
    private fun check(c: Char) {
        matched = if (c == forbidden[matched]) matched + 1 else if (c == '<') 1 else 0
        ensureUsenet(matched != forbidden.length, "Unsupported NZB document.")
        if (markup == 0 && c == '<') {
            markup = 1
            prefix.setLength(0)
            prefix.append(c)
            brackets = 0
            quote = '\u0000'
            dtdComment = 0
            dtdInstruction = false
        }
        else if (markup != 0) {
            if (++markup > 65536) throw NzbTooLarge()
            if (prefix.length < 9) prefix.append(c)
            when {
                prefix.startsWith("<!--") -> if (markup >= 7 && c == '>' && previous == '-' && beforePrevious == '-') markup = 0
                prefix.startsWith("<![CDATA[") -> if (c == '>' && previous == ']' && beforePrevious == ']') markup = 0
                prefix.startsWith("<?") -> if (c == '>' && previous == '?') markup = 0
                dtdComment > 0 -> if (++dtdComment >= 7 && c == '>' && previous == '-' && beforePrevious == '-') dtdComment = 0
                dtdInstruction -> if (c == '>' && previous == '?') dtdInstruction = false
                quote != '\u0000' -> if (c == quote) quote = '\u0000'
                c == '\'' || c == '"' -> quote = c
                prefix.startsWith("<!DOCTYPE") && thirdPrevious == '<' && beforePrevious == '!' && previous == '-' && c == '-' -> dtdComment = 4
                prefix.startsWith("<!DOCTYPE") && previous == '<' && c == '?' -> dtdInstruction = true
                prefix.startsWith("<!DOCTYPE") && c == '[' -> brackets++
                prefix.startsWith("<!DOCTYPE") && c == ']' -> brackets--
                c == '>' && brackets == 0 -> markup = 0
            }
        }
        thirdPrevious = beforePrevious
        beforePrevious = previous
        previous = c
    }
}
