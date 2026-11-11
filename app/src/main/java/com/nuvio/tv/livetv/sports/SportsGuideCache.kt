package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import com.squareup.moshi.JsonReader
import okio.buffer
import okio.source
import java.util.concurrent.CancellationException
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

    fun read(dir: File, day: Long, key: String, checkCancelled: () -> Unit = {}): List<SportsEvent>? {
        try {
            val file = File(dir, "$day.json.gz")
            if (!file.isFile) return null
            return GZIPInputStream(file.inputStream()).source().buffer().use { source ->
                val reader = JsonReader.of(source)
                var savedKey: String? = null
                val result = ArrayList<SportsEvent>()
                reader.beginObject()
                while (reader.hasNext()) {
                    checkCancelled()
                    when (reader.nextName()) {
                        "key" -> {
                            savedKey = reader.nextString()
                            if (savedKey != key) return null
                        }
                        "events" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                checkCancelled()
                                val fields = JSONObject()
                                val hits = ArrayList<Pair<String, EpgProgram>>()
                                val programmes = HashMap<EpgProgram, EpgProgram>()
                                // Read one airing at a time: a day can contain thousands of channels.
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val name = reader.nextName()
                                    if (name == "hits") {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            checkCancelled()
                                            val hit = JSONObject(reader.readJsonValue() as Map<*, *>)
                                            val people = hit.getJSONArray("people")
                                            val programme = EpgProgram(
                                                startMs = hit.getLong("start"), stopMs = hit.getLong("stop"),
                                                title = hit.getString("title"), description = hit.optional("description"),
                                                category = hit.optional("category"), episode = hit.optional("episode"),
                                                icon = hit.optional("icon"), year = if (hit.isNull("year")) null else hit.getInt("year"),
                                                people = List(people.length()) { people.getString(it) })
                                            hits += hit.getString("channel") to programmes.getOrPut(programme) { programme }
                                        }
                                        reader.endArray()
                                    } else fields.put(name, reader.readJsonValue())
                                }
                                reader.endObject()
                                result += SportsEvent(
                                    id = fields.getString("id"), leagueId = fields.getString("league"),
                                    startMs = fields.getLong("start"), endMs = fields.getLong("end"),
                                    hasTime = true, state = GameState.PRE, source = EventSource.GUIDE,
                                    title = fields.getString("title"), subtitle = fields.optional("subtitle"),
                                    word = fields.optional("word"), status = null, away = null, home = null,
                                    guideHits = hits)
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                result.takeIf { savedKey == key }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }

    fun write(dir: File, day: Long, key: String, events: List<SportsEvent>, checkCancelled: () -> Unit = {}) {
        val target = File(dir, "$day.json.gz")
        val temp = File(dir, "$day.tmp")
        try {
            dir.mkdirs()
            GZIPOutputStream(temp.outputStream()).bufferedWriter().use { writer ->
                writer.write("{\"key\":" + JSONObject.quote(key) + ",\"events\":[")
                events.forEachIndexed { index, event ->
                    checkCancelled()
                    if (index > 0) writer.write(",")
                    val fields = JSONObject().put("id", event.id).put("league", event.leagueId)
                        .put("start", event.startMs).put("end", event.endMs).put("title", event.title)
                        .put("subtitle", event.subtitle).put("word", event.word)
                    writer.write(fields.toString().dropLast(1) + ",\"hits\":[")
                    event.guideHits.forEachIndexed { hitIndex, (channel, p) ->
                        checkCancelled()
                        if (hitIndex > 0) writer.write(",")
                        writer.write(JSONObject().put("channel", channel).put("start", p.startMs).put("stop", p.stopMs)
                            .put("title", p.title).put("description", p.description).put("category", p.category)
                            .put("episode", p.episode).put("icon", p.icon).put("year", p.year)
                            .put("people", JSONArray(p.people)).toString())
                    }
                    writer.write("]}")
                }
                writer.write("]}")
            }
            checkCancelled()
            check(temp.renameTo(target))
            dir.listFiles()?.filter { it.name.endsWith(".json.gz") }
                ?.filter { it.name.removeSuffix(".json.gz").toLongOrNull()?.let { saved ->
                    saved < day - 3 * 86_400_000L || saved > day + 2 * 86_400_000L
                } == true }?.forEach { it.delete() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed write leaves the previous complete file intact.
        } finally {
            temp.delete()
        }
    }

    private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
}
