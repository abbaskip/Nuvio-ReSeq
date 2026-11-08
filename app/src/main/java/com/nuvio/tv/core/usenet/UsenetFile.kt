package com.nuvio.tv.core.usenet

/** read() returns bytes from a single contiguous extent. Callers can span extents by reading again. */
internal interface UsenetFile {
    val name: String
    val size: Long
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

internal class ArticleCache(private val fetch: (String, String?) -> Article) {
    private val entries = LinkedHashMap<String, Article>(16, 0.75f, true)
    private var used = 0
    private val missing = LinkedHashSet<String>()
    operator fun get(id: String): Article = load(id, null)
    @Synchronized fun load(id: String, next: String?): Article {
        entries[id]?.let { return it }
        if (id in missing) throw MissingUsenetArticle()
        val article = try {
            fetch(id, next?.takeUnless(entries::containsKey))
        } catch (error: MissingUsenetArticle) {
            // Only definitive absence across all providers is remembered. Transport failures remain retryable.
            if (missing.size >= 4096) missing.remove(missing.first())
            missing.add(id)
            throw error
        }
        entries[id] = article
        used += article.bytes.size
        val iterator = entries.values.iterator()
        while (used > 8 * 1024 * 1024 && iterator.hasNext()) {
            used -= iterator.next().bytes.size
            iterator.remove()
        }
        return article
    }
}

internal class PostedFile(file: NzbFile, private val cache: ArticleCache) : UsenetFile {
    override val name = file.name
    private val ids = file.articles
    private data class KnownPart(val index: Int, val end: Long)
    private val offsets = java.util.TreeMap<Long, KnownPart>()
    private val stride: Int
    override val size: Long
    init {
        ensureUsenet(ids.isNotEmpty(), "NZB is missing one or more file segments.")
        val first = cache[ids.first()]
        stride = first.bytes.size
        size = first.total
        val last = cache[ids.last()]
        offsets[0] = KnownPart(0, first.end)
        offsets[last.start] = KnownPart(ids.lastIndex, last.end)
        ensureUsenet(first.start == 0L && last.total == size && last.end == size, "NZB is missing the start or end of a file.")
    }

    @Synchronized override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        val floor = offsets.floorEntry(position)
        val known = floor.value
        var low = if (position < known.end) known.index else known.index + 1
        var high = offsets.higherEntry(position)?.value?.index?.minus(1) ?: ids.lastIndex
        if (position < known.end) high = low
        ensureUsenet(low <= high, "The requested part of the video is missing.")
        var index = if (position == known.end) low else
            (known.index + (position - floor.key) / stride).coerceIn(low.toLong(), high.toLong()).toInt()
        while (low <= high) {
            val next = if (position == known.end && index == known.index + 1) ids.getOrNull(index + 1) else null
            val article = cache.load(ids[index], next)
            ensureUsenet(article.total == size, "Inconsistent yEnc file size.")
            offsets[article.start] = KnownPart(index, article.end)
            when {
                position < article.start -> high = index - 1
                position >= article.end -> low = index + 1
                else -> {
                    val start = (position - article.start).toInt()
                    val count = minOf(length, article.bytes.size - start)
                    article.bytes.copyInto(buffer, offset, start, start + count)
                    return count
                }
            }
            index = (low + high) ushr 1
        }
        throw UsenetFailure("The requested part of the video is missing.")
    }
}

internal fun UsenetFile.bytesAt(position: Long, length: Int): ByteArray {
    ensureUsenet(position >= 0 && length >= 0 && position <= size - length, "Truncated RAR volume.")
    val result = ByteArray(length)
    var filled = 0
    while (filled < length) {
        val count = read(position + filled, result, filled, length - filled)
        ensureUsenet(count > 0, "Truncated RAR volume.")
        filled += count
    }
    return result
}

internal data class FileExtent(val volume: UsenetFile, val offset: Long, val length: Long)

internal class StoredFile(override val name: String, private val extents: List<FileExtent>) : UsenetFile {
    private val ends = LongArray(extents.size)
    override val size: Long
    init {
        var sum = 0L
        extents.forEachIndexed { i, extent ->
            ensureUsenet(extent.length > 0 && extent.length <= Long.MAX_VALUE - sum, "Invalid RAR file size.")
            sum += extent.length
            ends[i] = sum
        }
        size = sum
    }
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        val index = ends.binarySearch(position + 1).let { if (it < 0) -it - 1 else it }
        val extent = extents[index]
        val within = position - (ends[index] - extent.length)
        return extent.volume.read(extent.offset + within, buffer, offset, minOf(length.toLong(), extent.length - within).toInt())
    }
}
