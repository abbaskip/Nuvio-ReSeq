package com.nuvio.tv.livetv.ondemand

/** Download outside write transactions; retain at most [batchSize] entries at a time. */
internal fun <T> importCatalogBatches(
    batchSize: Int = 500,
    produce: ((T) -> Unit) -> Boolean,
    write: (List<T>) -> Unit
): Boolean {
    require(batchSize > 0)
    val batch = ArrayList<T>(batchSize)
    fun flush() {
        if (batch.isEmpty()) return
        write(batch)
        batch.clear()
    }
    val complete = produce { item ->
        batch.add(item)
        if (batch.size == batchSize) flush()
    }
    flush()
    return complete
}
