package com.nuvio.tv.ui.screens.player

import android.util.Log
import `is`.xyz.mpv.MPV

/** The view owns one client per native handle. Commands, surface changes and close share ordering. */
internal class MpvClient(private val native: MPV, formatChanged: () -> Unit) {
    val properties = MpvObservedProperties(formatChanged)
    private val queue = MpvCommandQueue()

    init {
        // Install the cache before the controller's observer sees the same property notification.
        native.addObserver(properties)
        submit { properties.observe(this) }
    }

    fun submit(command: MPV.() -> Unit) {
        queue.execute {
            try {
                command(native)
            } catch (failure: Exception) {
                Log.w("NuvioMpv", "MPV command failed", failure)
            }
        }
    }

    suspend fun <T> result(command: MPV.() -> T): T? = queue.result { command(native) }
    fun close() {
        native.removeObserver(properties)
        queue.close {
            try {
                native.close()
            } catch (failure: Exception) {
                Log.w("NuvioMpv", "MPV teardown failed", failure)
            }
        }
    }

    fun addObserver(observer: MPV.EventObserver) = native.addObserver(observer)
    fun removeObserver(observer: MPV.EventObserver) = native.removeObserver(observer)
    fun addLogObserver(observer: MPV.LogObserver) = native.addLogObserver(observer)
    fun removeLogObserver(observer: MPV.LogObserver) = native.removeLogObserver(observer)

    fun command(vararg args: String) = submit { command(*args) }
    fun setOptionString(name: String, value: String) = submit { setOptionString(name, value) }
    fun setPropertyString(name: String, value: String) = submit { setPropertyString(name, value) }
    fun setPropertyInt(name: String, value: Int) = submit { setPropertyInt(name, value) }
    fun setPropertyDouble(name: String, value: Double) = submit { setPropertyDouble(name, value) }
    fun setPropertyBoolean(name: String, value: Boolean) = submit { setPropertyBoolean(name, value) }
}
