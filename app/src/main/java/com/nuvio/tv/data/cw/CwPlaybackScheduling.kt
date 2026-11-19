package com.nuvio.tv.data.cw

import com.nuvio.tv.core.player.PlayerSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Cancel bulk CW work when playback starts. Each idle period reads fresh state from its sources. */
internal fun CoroutineScope.launchWhenCwPlaybackIdle(
    context: CoroutineContext = EmptyCoroutineContext,
    work: suspend () -> Unit
): Job = launch(context) {
    PlayerSessionTracker.bulkPlaybackActive.collectLatest { active ->
        if (!active) work()
    }
}
