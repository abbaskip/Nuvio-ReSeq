package com.nuvio.tv.core.usenet

/** read() returns bytes from a single contiguous extent. Callers can span extents by reading again. */
internal interface UsenetFile {
    val name: String
    val size: Long
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

internal class ArticleCache(
    private val fetchAhead: ((String) -> Article?)? = null,
    private val allowSequentialAhead: () -> Boolean = { true },
    private val fetch: (String, List<String>) -> Article
) {
    private val entries = LinkedHashMap<String, Article>(16, 0.75f, true)
    private val missing = LinkedHashSet<String>()
    private val loading = mutableMapOf<String, java.util.concurrent.CompletableFuture<Article?>>()
    private var used = 0
    private var prefetchBusy = false

    operator fun get(id: String): Article = load(id, emptyList())

    fun load(id: String, next: List<String>): Article {
        while (true) {
            val mine = java.util.concurrent.CompletableFuture<Article?>()
            val pending: java.util.concurrent.CompletableFuture<Article?>?
            val ahead: List<String>
            synchronized(this) {
                entries[id]?.let { return it }
                if (id in missing) throw MissingUsenetArticle()
                pending = loading[id]
                if (pending == null) loading[id] = mine
                ahead = next.filterNot { it == id || it in entries || it in missing || it in loading }
            }
            if (pending != null) {
                try {
                    pending.get()?.let { return it }
                } catch (error: java.util.concurrent.ExecutionException) {
                    throw error.cause ?: error
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("Interrupted waiting for the Usenet article.")
                }
                continue
            }
            try {
                val article = fetch(id, ahead)
                synchronized(this) { store(id, article) }
                mine.complete(article)
                return article
            } catch (error: Throwable) {
                if (error is MissingUsenetArticle) synchronized(this) {
                    if (missing.size >= 4096) missing.remove(missing.first())
                    missing.add(id)
                }
                mine.completeExceptionally(error)
                throw error
            } finally {
                synchronized(this) { loading.remove(id, mine) }
            }
        }
    }

    /** One speculative BODY per session. A spare socket may decline without poisoning demand. */
    fun prefetch(id: String?, archive: Boolean = false) {
        val reader = fetchAhead ?: return
        if (id == null || (!archive && !allowSequentialAhead())) return
        val future = synchronized(this) {
            if (prefetchBusy || id in entries || id in missing || id in loading) return
            prefetchBusy = true
            java.util.concurrent.CompletableFuture<Article?>().also { loading[id] = it }
        }
        kotlinx.coroutines.Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
            var article: Article? = null
            try {
                article = reader(id)
            } catch (_: java.io.IOException) {
                // Demand retries through the normal provider fallback path.
            } finally {
                synchronized(this) {
                    article?.let { store(id, it) }
                    loading.remove(id)
                    prefetchBusy = false
                }
                future.complete(article)
            }
        }
    }

    private fun store(id: String, article: Article) {
        entries[id] = article
        used += article.bytes.size
        val iterator = entries.values.iterator()
        while ((used > 8 * 1024 * 1024 || entries.size > 2048) && iterator.hasNext()) {
            used -= iterator.next().bytes.size
            iterator.remove()
        }
    }
}

internal class PostedFile(file: NzbFile, private val cache: ArticleCache, verifyTail: Boolean = true, nextHeads: List<String> = emptyList()) : UsenetFile {
    override val name = file.name
    private val ids = file.articles
    private data class KnownPart(val index: Int, val end: Long)
    private val offsets = java.util.TreeMap<Long, KnownPart>()
    private val stride: Int
    override val size: Long
    init {
        ensureUsenet(ids.isNotEmpty(), "NZB is missing one or more file segments.")
        val lookAhead = if (verifyTail && ids.size > 1) listOf(ids.last()) else nextHeads
        val first = cache.load(ids.first(), lookAhead)
        stride = first.bytes.size
        size = first.total
        ensureUsenet(first.start == 0L && stride > 0 && first.end <= size, "NZB is missing the start of a file.")
        offsets[0] = KnownPart(0, first.end)
        if (verifyTail || ids.size == 1) {
            val last = cache[ids.last()]
            ensureUsenet(last.total == size && last.end == size, "NZB is missing the end of a file.")
            offsets[last.start] = KnownPart(ids.lastIndex, last.end)
        }
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        val (floor, nextIndex) = synchronized(offsets) {
            (offsets.floorEntry(position) ?: throw UsenetFailure("Invalid Usenet video offset.")) to
                (offsets.higherEntry(position)?.value?.index?.minus(1) ?: ids.lastIndex)
        }
        val known = floor.value
        var low = if (position < known.end) known.index else known.index + 1
        var high = nextIndex
        if (position < known.end) high = low
        ensureUsenet(low <= high, "The requested part of the video is missing.")
        var index = if (position == known.end) low else
            (known.index + (position - floor.key) / stride).coerceIn(low.toLong(), high.toLong()).toInt()
        while (low <= high) {
            val next = if (position == known.end && index == known.index + 1)
                ids.subList(index + 1, minOf(ids.size, index + 3)) else emptyList()
            if (next.size == 2) cache.prefetch(ids.getOrNull(index + 3))
            val article = cache.load(ids[index], next)
            ensureUsenet(article.total == size && (index != ids.lastIndex || article.end == size), "Inconsistent yEnc file size.")
            synchronized(offsets) {
                val existing = offsets[article.start]
                val previous = offsets.lowerEntry(article.start)?.value
                val following = offsets.higherEntry(article.start)
                ensureUsenet((existing == null || (existing.index == index && existing.end == article.end)) &&
                    (previous == null || (previous.index < index && previous.end <= article.start &&
                        (previous.index + 1 != index || previous.end == article.start))) &&
                    (following == null || (following.value.index > index && article.end <= following.key &&
                        (following.value.index != index + 1 || article.end == following.key))),
                    "Inconsistent or missing yEnc file segments.")
                if (existing == null) offsets[article.start] = KnownPart(index, article.end)
            }
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
    ensureUsenet(position >= 0 && length >= 0 && position <= size - length, "Truncated posted file.")
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
            ensureUsenet(extent.length > 0 && extent.length <= Long.MAX_VALUE - sum, "Invalid archive extent size.")
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
