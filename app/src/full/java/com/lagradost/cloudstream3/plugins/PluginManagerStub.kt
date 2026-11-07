@file:Suppress("unused")

package com.lagradost.cloudstream3.plugins

/** Binary interface used by CS3 extensions to look up installed companion packages. */
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int
)

object PluginManager {
    @Volatile private var online: Array<PluginData> = emptyArray()

    internal fun setOnline(plugins: List<PluginData>) {
        online = plugins.toTypedArray()
    }

    fun getPluginsOnline(): Array<PluginData> = online.copyOf()
    fun unloadPlugin(filePath: String) {}
}
