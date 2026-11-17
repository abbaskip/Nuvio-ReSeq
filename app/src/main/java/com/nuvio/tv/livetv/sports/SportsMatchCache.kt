package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.buffer
import okio.sink
import okio.source
import java.io.File

/** The existing match-cache format, streamed without a second catalogue-sized JSON tree. */
internal object SportsMatchCache {
    fun write(file: File, matches: Map<String, List<ChannelMatch>>, checkpoint: () -> Unit = {}) {
        val temp = File(file.path + ".tmp")
        val backup = File(file.path + ".bak")
        try {
            file.parentFile?.mkdirs()
            JsonWriter.of(temp.sink().buffer()).use { writer ->
                writer.serializeNulls = true
                writer.beginArray()
                for ((id, items) in matches) {
                    checkpoint()
                    writer.beginObject().name("id").value(id).name("m").beginArray()
                    for (m in items) {
                        checkpoint()
                        writer.beginObject().name("k").value(m.channel.key).name("s").value(m.score)
                            .name("h").value(m.how).name("q").value(m.quality).name("c").value(m.catchup).endObject()
                    }
                    writer.endArray().endObject()
                }
                writer.endArray()
            }
            checkpoint()
            backup.delete()
            if (file.exists()) check(file.renameTo(backup))
            if (!temp.renameTo(file)) {
                backup.renameTo(file)
                error("Unable to save sports matches")
            }
            backup.delete()
        } finally { temp.delete() }
    }

    fun read(file: File, channels: Map<String, LiveChannel>, limits: SportsMatchLimits = SportsMatchLimits.UNCAPPED,
        settings: SportsSettings = SportsSettings(), checkpoint: () -> Unit = {}): Map<String, List<ChannelMatch>> {
        val backup = File(file.path + ".bak")
        if (!file.isFile && backup.isFile) check(backup.renameTo(file))
        if (!file.isFile) return emptyMap()
        return JsonReader.of(file.source().buffer()).use { reader ->
            val out = HashMap<String,RankedCandidates<String,ChannelMatch>>()
            val rank=SportsMatcher.preferenceRank(settings)
            val comparator=Comparator<ChannelMatch> { a,b -> SportsMatcher.compareMatchFields(a.channel,a.score,a.quality,
                b.channel,b.score,b.quality,settings.bestChannelFirst,rank) }
            val global=RankedCandidates<Pair<String,String>,ChannelMatch>(limits.totalMatches,comparator) { key,_ ->
                out[key.first]?.let { bucket -> bucket.remove(key.second,notify=false); if(bucket.size==0) out.remove(key.first) }
            }
            reader.beginArray()
            while (reader.hasNext()) {
                checkpoint()
                var id: String? = null
                val items = RankedCandidates<String,ChannelMatch>(limits.perFixture,comparator)
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "id" -> id = reader.nextString()
                    "m" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            checkpoint()
                            var key: String? = null
                            var score: Int? = null
                            var how: String? = null
                            var quality: String? = null
                            var catchup = false
                            reader.beginObject()
                            while (reader.hasNext()) when (reader.nextName()) {
                                "k" -> key = reader.nextString()
                                "s" -> score = reader.nextInt()
                                "h" -> how = reader.nextString()
                                "q" -> quality = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull() else reader.nextString()
                                "c" -> catchup = reader.nextBoolean()
                                else -> reader.skipValue()
                            }
                            reader.endObject()
                            val channel = channels[requireNotNull(key)]
                            val value = requireNotNull(score)
                            val reason = requireNotNull(how)
                            if (channel != null) items.offer(channel.key,ChannelMatch(channel, value, reason, null, quality, catchup))
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
                reader.endObject()
                val eventId = requireNotNull(id)
                for(item in items.encounterValues) {
                    val key=eventId to item.channel.key
                    if(global.offer(key,item)) out.getOrPut(eventId) { RankedCandidates(limits.perFixture,comparator) }
                        .offer(item.channel.key,item)
                }
            }
            reader.endArray()
            require(reader.peek() == JsonReader.Token.END_DOCUMENT)
            out.mapValues { it.value.encounterValues }
        }
    }
}
