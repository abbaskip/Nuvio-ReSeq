package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.LiveChannel

/** The guide only prioritises its first 400 channels, even for a very large playlist. */
internal fun guidePriorityChannelKeys(channels: List<LiveChannel>): List<String> =
    channels.take(400).map { it.key }
