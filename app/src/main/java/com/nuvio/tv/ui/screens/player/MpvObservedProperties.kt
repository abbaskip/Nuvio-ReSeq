package com.nuvio.tv.ui.screens.player

import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.util.concurrent.ConcurrentHashMap

/** The native event thread supplies values. Reading this cache never calls back into libmpv. */
internal class MpvObservedProperties(private val formatChanged: () -> Unit) : MPV.EventObserver {
    private val values = ConcurrentHashMap<String, Any>()

    fun observe(player: MPV) {
        for (name in flags) player.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_FLAG)
        for (name in numbers) player.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        for (name in strings) player.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_STRING)
        for (name in lists) player.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_NODE)
    }

    fun string(name: String) = values[name] as? String
    fun flag(name: String) = values[name] as? Boolean
    fun double(name: String) = (values[name] as? Number)?.toDouble() ?: string(name)?.toDoubleOrNull()
    fun int(name: String) = double(name)?.toInt()
    fun list(name: String) = (values[name] as? MPVNode)?.asArray().orEmpty()

    private fun update(property: String, value: Any?) {
        val previous = if (value == null) values.remove(property) else values.put(property, value)
        if ((property == "path" || property in reportProperties) && previous != value) formatChanged()
    }

    override fun eventProperty(property: String) = update(property, null)
    override fun eventProperty(property: String, value: Boolean) = update(property, value)
    override fun eventProperty(property: String, value: Double) = update(property, value)
    override fun eventProperty(property: String, value: Long) = update(property, value)
    override fun eventProperty(property: String, value: String) = update(property, value)
    override fun eventProperty(property: String, value: MPVNode) = update(property, value)
    override fun event(eventId: Int, data: MPVNode) = Unit

    companion object {
        private val flags = setOf("pause", "paused-for-cache", "core-idle", "idle-active", "eof-reached",
            "seekable", "seeking")
        private val numbers = setOf("time-pos/full", "time-pos", "audio-pts", "audio-delay", "duration/full",
            "demuxer-cache-duration", "video-out-params/aspect", "video-params/aspect", "sub-font-size",
            "sub-margin-x")
        private val reportProperties = setOf("file-format", "video-codec", "video-params/w", "video-params/h",
            "video-out-params/dw", "video-out-params/dh", "video-params/crop-x", "video-params/crop-y",
            "video-params/crop-w", "video-params/crop-h", "video-params/par", "video-params/rotate",
            "container-fps", "hwdec-current", "audio-codec-name", "audio-params/channel-count", "audio-out-params/format")
        private val strings = reportProperties + setOf("path", "vid", "aid", MPV_SID_PROPERTY, MPV_SUB_TEXT_PROPERTY,
            "sub-text/ass", "current-tracks/sub/codec", "current-tracks/audio/id", "current-tracks/sub/id",
            "current-tracks/video/codec", "current-tracks/audio/codec", "current-tracks/audio/codec-profile",
            "width", "height", "audio-params/samplerate", "current-vo", "vo", "video-format",
            "current-tracks/video/decoder-desc", "audio-codec", "hwdec", "current-tracks/video/dolby-vision-profile", "video-params/gamma", "video-params/pixelformat")
        private val lists = setOf(MPV_TRACK_LIST_PROPERTY, "chapter-list")
    }
}
