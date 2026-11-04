package com.nuvio.tv.domain.model

/** Keep the saved order key identical to the existing TV Mix row identity. */
internal object TvMixHomeCatalog {
    const val ADDON_ID = "reseq-tv-mix"
    const val TYPE = "tvmix"
    const val CATALOG_ID = "tv-mix"
    const val TITLE = "TV Mix"
    val key = catalogRowLegacyKey(ADDON_ID, TYPE, CATALOG_ID)

    /** Preserve this local row when an addon-only editor submits its catalog list. */
    fun retainPosition(order: List<String>, previousOrder: List<String>): List<String> {
        val index = previousOrder.indexOf(key)
        if (index < 0) return order
        return order.filterNot { it == key }.toMutableList().apply { add(index.coerceAtMost(size), key) }
    }
}

/** Rows that can move between addon blocks while following addon manifest order. */
internal fun isStandaloneHomeRowKey(key: String): Boolean =
    key.startsWith("collection_") || key == TvMixHomeCatalog.key
