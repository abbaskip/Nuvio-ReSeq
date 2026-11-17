package com.nuvio.tv.livetv.data

/** Each player owns its contribution; a delayed single-player detach cannot clear Multiview. */
internal class LiveChannelSignal(private val publish: (Boolean) -> Unit) {
    private var single = false
    private var multiview = false

    fun single(open: Boolean) { single = open; publish(single || multiview) }
    fun multiview(open: Boolean) { multiview = open; publish(single || multiview) }
}
