package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Completed discoveries only; the guide revision and all matching inputs must still agree. */
internal object SportsGuideCache {
    fun digest(parts: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val length = java.nio.ByteBuffer.allocate(4)
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            length.clear()
            hash.update(length.putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun read(dir: File, day: Long, key: String): List<SportsEvent>? = runCatching {
        val file = File(dir, "$day.json.gz")
        if (!file.isFile) return null
        val root = GZIPInputStream(file.inputStream()).bufferedReader().use { JSONObject(it.readText()) }
        if (root.getString("key") != key) return null
        val events = root.getJSONArray("events")
        List(events.length()) { index ->
            val event = events.getJSONObject(index)
            val hits = event.getJSONArray("hits")
            SportsEvent(
                id = event.getString("id"), leagueId = event.getString("league"),
                startMs = event.getLong("start"), endMs = event.getLong("end"),
                hasTime = true, state = GameState.PRE, source = EventSource.GUIDE,
                title = event.getString("title"), subtitle = event.optional("subtitle"),
                word = event.optional("word"), status = null, away = null, home = null,
                guideHits = List(hits.length()) { j ->
                    val hit = hits.getJSONObject(j)
                    val people = hit.getJSONArray("people")
                    hit.getString("channel") to EpgProgram(
                        startMs = hit.getLong("start"), stopMs = hit.getLong("stop"),
                        title = hit.getString("title"), description = hit.optional("description"),
                        category = hit.optional("category"), episode = hit.optional("episode"),
                        icon = hit.optional("icon"), year = if (hit.isNull("year")) null else hit.getInt("year"),
                        people = List(people.length()) { people.getString(it) }
                    )
                }
            )
        }
    }.getOrNull()

    fun write(dir: File, day: Long, key: String, events: List<SportsEvent>) {
        // A failed write leaves the previous complete file intact; corruption is a cache miss.
        val target = File(dir, "$day.json.gz")
        val temp = File(dir, "$day.tmp")
        runCatching {
            dir.mkdirs()
            val rows = JSONArray()
            events.forEach { event ->
                val hits = JSONArray()
                event.guideHits.forEach { (channel, p) ->
                    hits.put(JSONObject().put("channel", channel).put("start", p.startMs).put("stop", p.stopMs)
                        .put("title", p.title).put("description", p.description).put("category", p.category)
                        .put("episode", p.episode).put("icon", p.icon).put("year", p.year)
                        .put("people", JSONArray(p.people)))
                }
                rows.put(JSONObject().put("id", event.id).put("league", event.leagueId)
                    .put("start", event.startMs).put("end", event.endMs).put("title", event.title)
                    .put("subtitle", event.subtitle).put("word", event.word).put("hits", hits))
            }
            GZIPOutputStream(temp.outputStream()).bufferedWriter().use {
                it.write(JSONObject().put("key", key).put("events", rows).toString())
            }
            check(temp.renameTo(target))
            dir.listFiles()?.filter { it.name.endsWith(".json.gz") }
                ?.filter { it.name.removeSuffix(".json.gz").toLongOrNull()?.let { saved ->
                    saved < day - 3 * 86_400_000L || saved > day + 2 * 86_400_000L
                } == true }?.forEach { it.delete() }
        }
        temp.delete()
    }

    private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
}
