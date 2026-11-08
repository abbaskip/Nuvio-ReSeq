package com.nuvio.tv.core.usenet

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

internal class UsenetFailure(message: String) : IOException(message)
internal fun ensureUsenet(condition: Boolean, message: String) {
    if (!condition) throw UsenetFailure(message)
}

internal data class NzbFile(val name: String, val articles: List<String>, val postedBytes: Long)

internal object NzbManifest {
    const val MAX_BYTES = 12 * 1024 * 1024
    private val quoted = Regex("\"([^\"]+)\"")
    private val unquoted = Regex("""(?i)([^\s"]+\.(?:mkv|mp4|webm|avi|m4v|mov|m2ts|ts|rar|r\d\d))\b""")
    private val messageId = Regex("[!-~]{1,500}")

    fun parse(xml: String): List<NzbFile> {
        ensureUsenet(xml.length <= MAX_BYTES && "<!ENTITY" !in xml, "Unsupported NZB document.")
        val files = mutableListOf<NzbFile>()
        val segments = sortedMapOf<Int, String>()
        var subject: String? = null
        var number: Int? = null
        val text = StringBuilder()
        var postedBytes = 0L
        var count = 0
        var depth = 0
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
            override fun startElement(uri: String?, local: String?, name: String, attrs: Attributes) {
                ensureUsenet(++depth <= 32, "Invalid NZB structure.")
                when (name) {
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
                if (number != null) {
                    ensureUsenet(text.length + length <= 520, "Invalid NZB article identifier.")
                    text.append(chars, start, length)
                }
            }
            override fun endElement(uri: String?, local: String?, name: String) {
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
        return files
    }
}
