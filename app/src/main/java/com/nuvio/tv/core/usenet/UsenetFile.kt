package com.nuvio.tv.core.usenet

import kotlinx.coroutines.asExecutor

/** read() returns bytes from a single contiguous extent. Callers can span extents by reading again. */
internal interface UsenetFile {
    val name: String
    val size: Long
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    fun prefetch(position: Long) {}
}

internal class ArticleCache(
    private val fetchAhead: ((String) -> Article?)? = null,
    private val allowSequentialAhead: () -> Boolean = { true },
    private val profile: UsenetMemoryProfile = UsenetMemoryProfile.LOW,
    private val trace: UsenetStartupTrace? = null,
    private val fetch: (String, List<String>) -> Article
) {
    private val entries = LinkedHashMap<String, Article>(16, 0.75f, true)
    private val missing = LinkedHashSet<String>()
    private val loading = mutableMapOf<String, java.util.concurrent.CompletableFuture<Article?>>()
    private var used = 0
    private var prefetchBusy = 0
    private val heads = LinkedHashMap<String, HeadSlice>(16, 0.75f, true)
    private val evicted = LinkedHashSet<String>()
    internal data class HeadSlice(val article: Article, val length: Int)
    @Synchronized fun headSlice(id: String): HeadSlice? = heads[id]
    fun head(id: String): Article? = headSlice(id)?.article
    @Synchronized fun retainedBytes(): Int = used

    fun probeHeads(ids: List<String>, checkActive: () -> Unit) {
        for (batch in ids.chunked(8)) {
            checkActive()
            val futures = batch.map { id ->
                java.util.concurrent.CompletableFuture.runAsync({
                    if (head(id) == null) try { get(id) } catch (_: java.io.IOException) { }
                }, kotlinx.coroutines.Dispatchers.IO.asExecutor())
            }
            futures.forEach { it.join() }
            checkActive()
        }
    }

    fun window(ids: List<String>, index: Int, stride: Int) {
        val count = minOf(profile.window, profile.aheadBytes / maxOf(1, stride))
        for (i in index + 1..minOf(ids.lastIndex, index + count)) prefetch(ids[i])
    }

    fun archive(kind: String, volumes: Int) { trace?.archive(kind, volumes) }

    val parallelAhead get() = fetchAhead != null

    operator fun get(id: String): Article = load(id, emptyList())

    fun load(id: String, next: List<String>): Article {
        while (true) {
            val mine = java.util.concurrent.CompletableFuture<Article?>()
            val pending: java.util.concurrent.CompletableFuture<Article?>?
            val ahead: List<String>
            synchronized(this) {
                entries[id]?.let { trace?.add("hits"); return it }
                if (id in missing) throw MissingUsenetArticle()
                pending = loading[id]
                if (pending == null) loading[id] = mine
                ahead = next.filterNot { it == id || it in entries || it in missing || it in loading }
            }
            if (pending != null) {
                trace?.add("joins")
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
                trace?.add("demand")
                trace?.add("bytes", article.bytes.size.toLong())
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

    /** Bounded speculation; the provider pool reserves capacity for demand. */
    fun prefetch(id: String?, archive: Boolean = false) {
        val reader = fetchAhead ?: return
        if (id == null || (!archive && !allowSequentialAhead())) return
        val future = synchronized(this) {
            if (prefetchBusy >= profile.window || (loading.size + 1L) * 4 * 1024 * 1024 > profile.cacheBytes * 3L / 4 ||
                id in entries || id in missing || id in loading) return
            prefetchBusy++
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
                    article?.let { store(id, it); trace?.add("speculative"); trace?.add("bytes", it.bytes.size.toLong()) }
                    loading.remove(id)
                    prefetchBusy--
                }
                future.complete(article)
            }
        }
    }

    private fun store(id: String, article: Article) {
        if (evicted.remove(id)) trace?.add("refetches")
        if (article.start == 0L) {
            heads[id] = HeadSlice(article.copy(bytes = article.bytes.copyOf(minOf(16384, article.bytes.size))), article.bytes.size)
            if (heads.size > 512) heads.remove(heads.keys.first())
        }
        used -= entries.put(id, article)?.bytes?.size ?: 0
        used += article.bytes.size
        val iterator = entries.entries.iterator()
        while ((used > profile.cacheBytes || entries.size > 2048) && iterator.hasNext()) {
            val entry = iterator.next()
            used -= entry.value.bytes.size
            evicted.add(entry.key)
            if (evicted.size > 4096) evicted.remove(evicted.first())
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
        val retained = cache.headSlice(ids.first())
        val first = retained?.article ?: cache.load(ids.first(), lookAhead)
        stride = retained?.length ?: first.bytes.size
        size = first.total
        ensureUsenet(first.start == 0L && stride > 0 && stride <= size, "NZB is missing the start of a file.")
        offsets[0] = KnownPart(0, stride.toLong())
        if (ids.size == 1) {
            ensureUsenet(stride.toLong() == size, "NZB is missing the end of a file.")
        } else if (verifyTail) {
            val last = cache[ids.last()]
            ensureUsenet(last.total == size && last.end == size, "NZB is missing the end of a file.")
            offsets[last.start] = KnownPart(ids.lastIndex, last.end)
        }
    }

    override fun prefetch(position: Long) {
        val index = (position / stride).coerceIn(0, ids.lastIndex.toLong()).toInt()
        cache.prefetch(ids[index], archive = true)
        cache.window(ids, index, stride)
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        cache.head(ids.first())?.let { head ->
            if (position < head.bytes.size) {
                val count = minOf(length, head.bytes.size - position.toInt())
                head.bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }
        }
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
            // Parallel windows own these IDs; avoid also queuing the same bodies on the demand socket.
            if (next.isNotEmpty()) cache.window(ids, index, stride)
            val article = cache.load(ids[index], if (cache.parallelAhead) emptyList() else next)
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
        if (within + length >= extent.length) extents.getOrNull(index + 1)?.let { it.volume.prefetch(it.offset) }
        return extent.volume.read(extent.offset + within, buffer, offset, minOf(length.toLong(), extent.length - within).toInt())
    }
}

/** Split-file sizes are predictions until their decoded yEnc header is opened. */
internal class LazyPostedFile(private val file: NzbFile, private val cache: ArticleCache,
                              override val size: Long) : UsenetFile {
    override val name = file.name
    private val opened by lazy {
        PostedFile(file, cache, verifyTail = false).also {
            ensureUsenet(it.size == size, "Inconsistent split volume size.")
        }
    }
    override fun prefetch(position: Long) { cache.prefetch(file.articles.firstOrNull(), archive = true) }
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = opened.read(position, buffer, offset, length)
}
