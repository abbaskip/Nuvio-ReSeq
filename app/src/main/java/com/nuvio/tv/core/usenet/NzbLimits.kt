package com.nuvio.tv.core.usenet

/** Bounds both transient parsing work and the data kept for the entire playback session. */
internal data class NzbLimits(val retainedBytes: Long, val segments: Int, val decodedBytes: Long) {
    companion object {
        const val HARD_DECODED_BYTES = 128L * 1024 * 1024
        const val HARD_SEGMENTS = 1_000_000
        const val HARD_RETAINED_BYTES = 48L * 1024 * 1024
        val LOW = NzbLimits(16L * 1024 * 1024, 250_000, 64L * 1024 * 1024)
        val NORMAL = NzbLimits(HARD_RETAINED_BYTES, HARD_SEGMENTS, HARD_DECODED_BYTES)
        fun forProfile(profile: UsenetMemoryProfile) = if (profile.cacheBytes < UsenetMemoryProfile.NORMAL.cacheBytes) LOW else NORMAL
    }
    init {
        require(retainedBytes in 1..HARD_RETAINED_BYTES && segments in 1..HARD_SEGMENTS && decodedBytes in 1..HARD_DECODED_BYTES)
    }
}

internal class NzbTooLarge : UsenetFailure("NZB is too large for this device.")

/** Charges capacity before allocation, including conservative array/list/object overhead. */
internal class NzbRetentionBudget(private val limit: Long) {
    var bytes = 0L
        private set
    fun reserve(count: Long) {
        if (count > limit - bytes) throw NzbTooLarge()
        bytes += count
    }
}

/** Immutable after publication. Only the requested ID becomes a String; lookup is O(1).
 * Fixed pages avoid doubling/copying large buffers and let the parser accept shuffled ordinals.
 */
internal class NzbArticleIds(private val budget: NzbRetentionBudget, private val maxSegments: Int) :
    AbstractList<String>(), java.util.RandomAccess {
    private val data = ArrayList<ByteArray>()
    private val index = ArrayList<LongArray?>()
    private var used = PAGE_BYTES
    private var highest = 0
    private var overflowOrdinals: HashSet<Int>? = null
    override var size = 0
        private set
    val complete get() = size > 0 && highest == size
    var retainedBytes = 256L
        private set

    init { budget.reserve(retainedBytes) }

    private fun reserve(bytes: Long) { budget.reserve(bytes); retainedBytes += bytes }

    fun add(ordinal: Int, id: String) {
        // A very large ordinal must not cause a proportional allocation. Such a file cannot
        // be complete under the segment limit, but retains its slot just like other gaps.
        highest = maxOf(highest, ordinal)
        if (ordinal > maxSegments) {
            val overflow = overflowOrdinals ?: HashSet<Int>().also { reserve(128); overflowOrdinals = it }
            reserve(64)
            ensureUsenet(overflow.add(ordinal), "Invalid NZB article identifier.")
            size++
            return
        }
        val slot = ordinal - 1
        val page = slot / INDEX_ENTRIES
        if (page >= index.size) {
            reserve((page + 1L - index.size) * 16)
            while (index.size <= page) index.add(null)
        }
        val offsets = index[page] ?: run {
            reserve(INDEX_ENTRIES * 8L + 32)
            LongArray(INDEX_ENTRIES).also { index[page] = it }
        }
        ensureUsenet(offsets[slot % INDEX_ENTRIES] == 0L, "Invalid NZB article identifier.")
        if (used + id.length > PAGE_BYTES) {
            reserve(PAGE_BYTES + 48L)
            data.add(ByteArray(PAGE_BYTES))
            used = 0
        }
        val position = (data.size - 1) * PAGE_BYTES + used
        // Validation restricts IDs to printable ASCII, also their UTF-8 representation.
        for (i in id.indices) data.last()[used + i] = id[i].code.toByte()
        used += id.length
        offsets[slot % INDEX_ENTRIES] = (position.toLong() shl 32) or id.length.toLong()
        size++
    }

    override fun get(index: Int): String {
        if (index !in 0 until size) throw IndexOutOfBoundsException()
        val entry = this.index[index / INDEX_ENTRIES]!![index % INDEX_ENTRIES]
        val position = (entry ushr 32).toInt()
        return String(data[position / PAGE_BYTES], position % PAGE_BYTES, entry.toInt(), Charsets.US_ASCII)
    }

    companion object {
        private const val PAGE_BYTES = 4096
        private const val INDEX_ENTRIES = 256
    }
}
