package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.extractorApis

/** Android extension ABI. The legacy Activity overload also supports older ReSeq plugins. */
open class Plugin : BasePlugin() {
    var resources: Resources? = null
    var openSettings: ((Context) -> Unit)? = null

    val registeredMainAPIs get() = APIHolder.allProviders.filter { filename != null && it.sourcePlugin == filename }
    val registeredExtractorAPIs get() = extractorApis.filter { filename != null && it.sourcePlugin == filename }

    open fun load(context: Context) = load(context as? Activity)
    open fun load(activity: Activity?) = load()
}
