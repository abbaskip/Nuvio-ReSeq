package com.nuvio.tv.livetv.data

import org.json.JSONArray
import java.io.BufferedReader
import java.io.Writer

/** Linked maps keep the order of get_live_categories independently of get_live_streams. */
internal fun xtreamLiveCategories(response: String): Map<String, String> {
    val rows = JSONArray(response)
    return buildMap {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("category_id")
            val name = row.optString("category_name").replace('"', '\'')
                .replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim()
            if (id.isNotBlank() && name.isNotBlank()) put(id, name)
        }
    }
}

private const val CATEGORY_HEADER = "#NUVIO-XTREAM-GROUPS:"

/** Keep ranks and channel rows in the same cached playlist across refreshes and restarts. */
internal fun writeXtreamCategoryHeader(out: Writer, source: String, categories: Map<String, String>) {
    val rows = JSONArray()
    for ((id, name) in categories) rows.put(JSONArray().put(id).put(name))
    out.write("$CATEGORY_HEADER$source:$rows\n")
}

internal fun readXtreamCategoryHeader(reader: BufferedReader, source: String): Map<String, String> {
    if (reader.readLine() != "#EXTM3U") return emptyMap()
    val line = reader.readLine() ?: return emptyMap()
    val prefix = "$CATEGORY_HEADER$source:"
    if (!line.startsWith(prefix)) return emptyMap()
    val rows = JSONArray(line.substring(prefix.length))
    return buildMap {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            put(row.getString(0), row.getString(1))
        }
    }
}
