package com.nuvio.tv.livetv.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.Reader

/** Does not expose response text or URLs in errors: either can contain login details. */
internal class XtreamCatalogException : IOException("The provider catalog was incomplete or invalid")

/** Reads arrays and ID-keyed objects without retaining the catalog. Consumer failures propagate. */
internal fun readXtreamCatalog(
    input: Reader,
    idField: String,
    onInvalidEntry: () -> Unit = { throw XtreamCatalogException() },
    onEntry: (Map<String, String>) -> Unit
) {
    fun <T> read(block: () -> T): T = try {
        block()
    } catch (_: IOException) {
        throw XtreamCatalogException()
    } catch (_: IllegalStateException) {
        throw XtreamCatalogException()
    }
    JsonReader(input).use { reader ->
        reader.isLenient = true
        val keyed = read {
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> { reader.beginArray(); false }
                JsonToken.BEGIN_OBJECT -> { reader.beginObject(); true }
                else -> throw XtreamCatalogException()
            }
        }
        while (read { reader.hasNext() }) {
            val fields = read {
                if (keyed) reader.nextName()
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    null
                } else {
                    reader.beginObject()
                    val fields = HashMap<String, String>()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        when (reader.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> fields[name] = reader.nextString()
                            JsonToken.BOOLEAN -> fields[name] = reader.nextBoolean().toString()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    fields
                }
            }
            // Outside the parsing catch: a failed database write must stop the download.
            if (fields != null) {
                if (fields[idField].isNullOrBlank()) onInvalidEntry() else onEntry(fields)
            }
        }
        read {
            if (keyed) reader.endObject() else reader.endArray()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw XtreamCatalogException()
        }
    }
}
