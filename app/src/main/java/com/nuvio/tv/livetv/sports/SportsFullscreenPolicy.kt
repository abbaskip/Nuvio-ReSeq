package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.player.LivePlaybackState

/** Maximizing the selected live channel changes presentation, without tuning it again. */
internal fun canExpandSportsPreview(playback: LivePlaybackState, selectedChannelKey: String): Boolean =
    playback.channelKey == selectedChannelKey && playback.catchupTitle == null && playback.error == null
