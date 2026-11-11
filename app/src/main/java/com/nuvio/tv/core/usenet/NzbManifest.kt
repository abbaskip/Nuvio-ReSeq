package com.nuvio.tv.core.usenet

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

internal open class UsenetFailure(message: String) : IOException(message)
internal fun ensureUsenet(condition: Boolean, message: String) {
    if (!condition) throw UsenetFailure(message)
}

internal data class NzbFile(val name: String, val articles: List<String>, val postedBytes: Long)

internal class NzbDocument(val files: List<NzbFile>, val password: String?)

internal object NzbManifest {
    const val MAX_BYTES = 12 * 1024 * 1024
    private val quoted = Regex("\"([^\"]+)\"")
    private val unquoted = Regex("""(?i)([^\s"]+\.(?:(?:7z|mkv|mp4|webm|avi|m4v|mov|m2ts|ts|mpg|mpeg|wmv)(?:\.\d{3,5})?|rar(?:\.\d{3,5})?|[r-z]\d{2,3}|par2|\d{3,5}))\b""")
    private val messageId = Regex("[!-~]{1,500}")

    fun parse(bytes: ByteArray): NzbDocument {
        ensureUsenet(bytes.size <= MAX_BYTES, "NZB exceeds the size limit.")
        val encoding = when {
            bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> "UTF-16LE"
            bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> "UTF-16BE"
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> "UTF-16BE"
            bytes.size >= 4 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> "UTF-16LE"
            else -> Regex("""encoding\s*=\s*["']([^"']+)["']""").find(
                String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1))?.groupValues?.get(1) ?: "UTF-8"
        }
        val text = try { String(bytes, java.nio.charset.Charset.forName(encoding)) }
            catch (_: Exception) { throw UsenetFailure("Unsupported NZB text encoding.") }
        return parse(text.removePrefix("\uFEFF"))
    }

    fun parse(xml: String): NzbDocument {
        ensureUsenet(xml.length <= MAX_BYTES && "<!ENTITY" !in xml, "Unsupported NZB document.")
        val files = mutableListOf<NzbFile>()
        val segments = sortedMapOf<Int, String>()
        var subject: String? = null
        var number: Int? = null
        val text = StringBuilder()
        var postedBytes = 0L
        var count = 0
        var depth = 0
        var head = false
        var readingPassword = false
        var password: String? = null
        val meta = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
            override fun startElement(uri: String?, local: String?, name: String, attrs: Attributes) {
                ensureUsenet(++depth <= 32, "Invalid NZB structure.")
                if (depth == 1) ensureUsenet(name == "nzb", "Invalid NZB root element.")
                when (name) {
                    "head" -> if (depth == 2) head = true
                    "meta" -> if (head && depth == 3 && attrs.getValue("type").equals("password", true)) {
                        readingPassword = true
                        meta.setLength(0)
                    }
                    "file" -> {
                        ensureUsenet(subject == null && files.size < 4096, "Invalid NZB file list.")
                        subject = attrs.getValue("subject").orEmpty().take(2048)
                        postedBytes = 0
                        segments.clear()
                    }
                    "segment" -> {
                        val ordinal = attrs.getValue("number")?.toIntOrNull() ?: 0
                        val size = attrs.getValue("bytes")?.toLongOrNull() ?: 0L
                        ensureUsenet(subject != null && number == null && ordinal > 0 &&
                            ++count <= 150000 && size in 0..Int.MAX_VALUE.toLong(), "Invalid NZB segment.")
                        number = ordinal
                        postedBytes += size
                        text.setLength(0)
                    }
                }
            }
            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (readingPassword) {
                    ensureUsenet(meta.length + length <= 512, "NZB archive password is too long.")
                    meta.append(chars, start, length)
                }
                if (number != null) {
                    ensureUsenet(text.length + length <= 520, "Invalid NZB article identifier.")
                    text.append(chars, start, length)
                }
            }
            override fun endElement(uri: String?, local: String?, name: String) {
                if (name == "meta" && depth == 3 && readingPassword) {
                    password = meta.toString().takeIf { it.isNotEmpty() }
                    readingPassword = false
                    meta.setLength(0)
                }
                if (name == "head" && depth == 2) head = false
                depth--
                if (name == "segment") {
                    val id = text.toString().trim().removeSurrounding("<", ">")
                    ensureUsenet(messageId.matches(id) && '<' !in id && '>' !in id &&
                        segments.put(number!!, id) == null, "Invalid NZB article identifier.")
                    number = null
                }
                if (name == "file") {
                    val title = subject.orEmpty()
                    val filename = quoted.find(title)?.groupValues?.get(1)
                        ?: unquoted.find(title)?.groupValues?.get(1) ?: title
                    // Retain the slot of an incomplete file so fileIdx still identifies the requested episode.
                    val complete = segments.isNotEmpty() && segments.firstKey() == 1 && segments.lastKey() == segments.size
                    files += NzbFile(filename, if (complete) segments.values.toList() else emptyList(), postedBytes)
                }
                if (name == "file") subject = null
            }
        }
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(InputSource(StringReader(xml)), handler)
        } catch (failure: UsenetFailure) {
            throw failure
        } catch (_: Exception) {
            throw UsenetFailure("Could not read the NZB document.")
        }
        ensureUsenet(files.any { it.articles.isNotEmpty() }, "The NZB contains no complete files.")
        return NzbDocument(files, password)
    }
}
