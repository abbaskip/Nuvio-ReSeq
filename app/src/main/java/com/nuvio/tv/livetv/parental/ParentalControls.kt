package com.nuvio.tv.livetv.parental

import com.nuvio.tv.livetv.model.LiveTvSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Parental controls: groups (and On Demand categories) that need the PIN. Adult groups can be
 * locked automatically by name. Once unlocked with the PIN, a group stays open until the app is
 * closed.
 */
object ParentalControls {
    private val ADULT = Regex(
        """(?i)(\bxxx\b|\badults?\b|\bporn|\b18\s*\+|\+\s*18\b|\berotic|\bplayboy\b|\bhustler\b|\bbrazzers\b|\bredlight\b|\bsexy?\b|\bnsfw\b)"""
    )

    private val _unlocked = MutableStateFlow<Set<String>>(emptySet())
    /** Group ids unlocked with the PIN since the app started. */
    val unlocked: StateFlow<Set<String>> = _unlocked.asStateFlow()

    fun isAdult(name: String): Boolean = ADULT.containsMatchIn(name)

    fun enabled(s: LiveTvSettings): Boolean = s.parentalPin.length >= 4

    /** Whether [id] (named [name]) needs the PIN right now. */
    fun isLocked(id: String, name: String, s: LiveTvSettings, lockedIds: Set<String>): Boolean {
        if (!enabled(s) || id in _unlocked.value) return false
        return id in lockedIds || (s.lockAdultContent && isAdult(name))
    }

    /** True (and the group stays open) if [pin] is right. */
    fun unlock(id: String, pin: String, s: LiveTvSettings): Boolean {
        if (pin.trim() != s.parentalPin) return false
        _unlocked.value = _unlocked.value + id
        return true
    }

    fun checkPin(pin: String, s: LiveTvSettings): Boolean = pin.trim() == s.parentalPin
}
