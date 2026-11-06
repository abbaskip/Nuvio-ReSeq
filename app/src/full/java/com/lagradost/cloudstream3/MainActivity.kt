package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/** CloudStream binary entry points used by extensions after saving their settings. */
class MainActivity {
    companion object {
        @JvmStatic val reloadHomeEvent = Event<Boolean>()
        @JvmStatic val afterPluginsLoadedEvent = Event<Boolean>()
    }
}
