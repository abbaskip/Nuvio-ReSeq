package com.nuvio.tv.core.usenet

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.StringReader
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

internal open class UsenetFailure(message: String) : IOException(message)
internal fun ensureUsenet(condition: Boolean, message: String) {
    if (!condition) throw UsenetFailure(message)
}

internal data class NzbFile(val name: String, val articles: List<String>, val postedBytes: Long)

internal class NzbDocument(val files: List<NzbFile>, val password: String?, val decodedBytes: Long = 0) {
    val retainedBytes: Long = 128L + (password?.length ?: 0) * 2L + files.sumOf { file ->
        192L + file.name.length * 2L + ((file.articles as? NzbArticleIds)?.retainedBytes
            ?: file.articles.sumOf { 64L + it.length * 2L })
    }
}

internal object NzbManifest {
    private val quoted = Regex("\"([^\"]+)\"")
    private val unquoted = Regex("""(?i)([^\s"]+\.(?:(?:7z|mkv|mp4|webm|avi|m4v|mov|m2ts|ts|mpg|mpeg|wmv)(?:\.\d{3,5})?|rar(?:\.\d{3,5})?|[r-z]\d{2,3}|par2|\d{3,5}))\b""")

    fun parse(bytes: ByteArray): NzbDocument = parse(bytes.inputStream())

    // The String overload already has decoded characters; a BOM preserves them regardless
    // of an encoding declaration supplied by a test/caller. Downloads use InputStream only.
    fun parse(xml: String): NzbDocument = parse(xml.byteInputStream(Charsets.UTF_16))

    fun parse(input: InputStream, limits: NzbLimits = NzbLimits.forProfile(UsenetMemoryProfile.current)): NzbDocument {
        val bounded = NzbBoundedInput(input, limits.decodedBytes)
        val budget = NzbRetentionBudget(limits.retainedBytes)
        budget.reserve(128 + 1024) // document and maximum password
        val files = mutableListOf<NzbFile>()
        val names = HashSet<String>()
        fun accountName(name: String) {
            if (name !in names) {
                // SAX implementations intern names; bound that transient symbol table too.
                budget.reserve(128L + name.length * 2L)
                names.add(name)
            }
        }
        var segments: NzbArticleIds? = null
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
                accountName(name)
                for (i in 0 until attrs.length) accountName(attrs.getQName(i))
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
                        budget.reserve(192L + subject!!.length * 2L)
                        postedBytes = 0
                        segments = NzbArticleIds(budget, limits.segments)
                    }
                    "segment" -> {
                        val ordinal = attrs.getValue("number")?.toIntOrNull() ?: 0
                        val size = attrs.getValue("bytes")?.toLongOrNull() ?: 0L
                        ensureUsenet(subject != null && number == null && ordinal > 0 &&
                            size in 0..Int.MAX_VALUE.toLong(), "Invalid NZB segment.")
                        if (++count > limits.segments) throw NzbTooLarge()
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
                    ensureUsenet(id.length in 1..500 && id.all { it in '!'..'~' && it != '<' && it != '>' },
                        "Invalid NZB article identifier.")
                    segments!!.add(number!!, id)
                    number = null
                }
                if (name == "file") {
                    val title = subject.orEmpty()
                    val filename = quoted.find(title)?.groupValues?.get(1)
                        ?: unquoted.find(title)?.groupValues?.get(1) ?: title
                    // Retain the slot of an incomplete file so fileIdx still identifies the requested episode.
                    files += NzbFile(filename, segments!!.takeIf { it.complete } ?: emptyList(), postedBytes)
                    segments = null
                }
                if (name == "file") subject = null
            }
        }
        try {
            val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.entityResolver = handler
            reader.errorHandler = handler
            reader.parse(InputSource(nzbReader(bounded)))
        } catch (failure: UsenetFailure) {
            throw failure
        } catch (failure: Exception) {
            // Some SAX implementations wrap handler/Reader exceptions.
            var cause: Throwable? = failure.cause
            repeat(8) {
                if (cause is UsenetFailure) throw cause as UsenetFailure
                cause = cause?.cause
            }
            throw UsenetFailure("Could not read the NZB document.")
        }
        ensureUsenet(files.any { it.articles.isNotEmpty() }, "The NZB contains no complete files.")
        return NzbDocument(files, password, bounded.count)
    }
}
