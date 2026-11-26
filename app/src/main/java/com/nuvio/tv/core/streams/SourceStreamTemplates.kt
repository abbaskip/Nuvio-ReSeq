package com.nuvio.tv.core.streams

import com.nuvio.tv.core.debrid.DebridStreamTemplateEngine
import com.nuvio.tv.domain.model.Stream
import kotlinx.serialization.Serializable

/** Optional card text for IPTV and Direct Usenet, stored with the current profile. */
@Serializable
data class SourceStreamTemplates(val name: String = "", val description: String = "") {
    internal val enabled get() = name.isNotBlank() || description.isNotBlank()

    internal fun validate() {
        require(name.length <= MAX_LENGTH && description.length <= MAX_LENGTH)
    }

    internal fun apply(stream: Stream, type: String, values: Map<String, Any?>): Stream {
        val fields = values + mapOf("stream.type" to type, "addon.name" to stream.addonName)
        fun render(template: String, fallback: String?): String? =
            if (template.isBlank()) fallback
            else engine.render(template, fields).lineSequence().map(String::trim)
                .filter(String::isNotBlank).joinToString("\n").ifBlank { fallback }

        return stream.copy(name = render(name, stream.name), description = render(description, stream.description))
    }

    companion object {
        const val MAX_LENGTH = 8192
        private val engine = DebridStreamTemplateEngine()
    }
}
