package com.nuvio.tv.livetv.record

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Short messages shown anywhere in Nuvio (bottom-right, for a few seconds): a recording set or
 * started, a clash, a recording that failed. Shown by the Live TV loading host.
 */
object LiveTvNotice {
    data class Notice(val text: String, val id: Long = System.nanoTime())

    private val _current = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> = _current.asStateFlow()

    fun show(text: String) { _current.value = Notice(text) }

    fun clear(notice: Notice) { if (_current.value == notice) _current.value = null }
}
