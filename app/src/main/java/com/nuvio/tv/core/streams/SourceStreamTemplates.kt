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
        val DEFAULT = SourceStreamTemplates(
            name = """
                {stream.library::istrue["☁️ "||""]}{service.shortName::exists["[{service.shortName}]"||""]}{stream.type::=Debrid::and::service.cached::istrue["⚡"||""]}{stream.type::=Debrid::and::service.cached::isfalse["❌"||""]}{stream.type::=Usenet::and::service.cached::istrue["⚡📰"||""]}{stream.type::=Usenet::and::service.cached::isfalse["❌📰"||""]}{stream.type::=p2p["⚠️P2P"||""]}{stream.type::=http["💻WEB"||""]}{stream.type::=youtube["▶️YT"||""]}{stream.type::=live["📺LIVE"||""]}
                {stream.resolution::exists["{stream.resolution::replace('2160p','4K')::replace('1440p','2K')::replace('1080p','1080P')::replace('720p','720P')}"||"Unknown"]}{stream.quality::exists[" | {stream.quality::title::replace('Bluray Remux','Remux')::replace('BluRay Remux','Remux')::replace('Bluray','BluRay')::replace('WEB-DL','WEB-DL')::replace('WEBRip','WEBRip')}"||""]}
                {stream.audioChannels::exists["{stream.audioChannels::join(' / ')}"||""]}{stream.audioTags::exists[" | {stream.audioTags::join(' / ')}"||""]}
            """.trimIndent(),
            description = """
                {stream.title::exists["🎬 {stream.title::title} "||"🎬 "]}{stream.year::exists["({stream.year})"||""]}
                {metadata.runtime::>0["⏱️ {metadata.runtime} min"||""]}{metadata.episodeRuntime::>0["⏱️ {metadata.episodeRuntime} min"||""]}
                {stream.filename::~extended["⏩ Extended Edition "||""]}{stream.filename::~theatrical["🎭 Theatrical Cut "||""]}{stream.filename::~director["🎬 Director's Cut "||""]}{stream.filename::~ultimate["🛡️ Ultimate Edition "||""]}{stream.filename::~anniversary["🎉 Anniversary Edition "||""]}{stream.filename::~imax["🏟️ IMAX "||""]}{stream.filename::~remastered["✨ Remastered "||""]}{stream.filename::~collectors["🎖️ Collector's Edition "||""]}{stream.filename::~uncut["✂️ Uncut "||""]}{stream.title::~color::or::stream.title::~colour::or::stream.filename::~color::or::stream.filename::~colour["🌈 Colourised "||""]}{stream.filename::~diamond["💎 Diamond Edition "||""]}
                {stream.filename::exists["📄 {stream.filename}"||""]}
                {stream.encode::exists["🎞️ {stream.encode}"||""]}{stream.size::>0[" | 📦 {stream.size::bytes}"||""]}{stream.folderSize::>0[" / 📁 {stream.folderSize::bytes}"||""]}{stream.bitrate::>0[" | 📡 {stream.bitrate::bitrate}"||""]}{stream.type::exists[" | {stream.type::replace('usenet','📰 Usenet')::replace('debrid','⚡ Debrid')::replace('p2p','🧲 Torrent')::replace('http','🌐 Direct')::replace('live','📺 Live')::replace('youtube','▶️ YouTube')}"||""]}
                {stream.quality::exists["💿 {stream.quality::title::replace('Bluray Remux','Remux')::replace('BluRay Remux','Remux')::replace('Bluray','BluRay')::replace('WEB-DL','WEB-DL')::replace('WEBRip','WEBRip')}"||""]}{stream.visualTags::exists[" | 🔆 {stream.visualTags::join(' / ')}"||""]}{stream.audioTags::exists[" | 🎧 {stream.audioTags::join(' / ')}"||""]}{stream.audioChannels::exists[" 🔊 {stream.audioChannels::join(' / ')}"||""]}
                {stream.languageEmojis::exists["🗣️ {stream.languageEmojis::join(' / ')::replace('Multi','🔢')::replace('Dual Audio','2️⃣')::replace('Dubbed','🎤')}"||""]}{stream.languages::exists[" ({stream.languages})"||""]}
                {service.shortName::exists["[{service.shortName}] | "||""]}{addon.name::exists["{addon.name}"||""]}{stream.releaseGroup::exists[" | 🏷️ {stream.releaseGroup}"||""]}
            """.trimIndent()
        )

        private val engine = DebridStreamTemplateEngine()
    }
}
