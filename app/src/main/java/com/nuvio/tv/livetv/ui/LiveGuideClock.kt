package com.nuvio.tv.livetv.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

internal fun liveGuideClock(wallTime: () -> Long = System::currentTimeMillis) = flow {
    while (true) {
        val time = wallTime()
        emit(time)
        delay(30_000L - Math.floorMod(time, 30_000L))
    }
}
