package com.nuvio.tv.livetv.thumbs

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seek thumbnail settings (Settings > Playback > Seek Thumbnails). Off by default; every limit is
 * a choice the user can change. Google Drive can back up the preferences and the personal key.
 */
object SeekThumbSettings {
    /** When thumbnails are made. */
    enum class When(val label: String, val detail: String) {
        SEEKING("Only When Paused or Seeking", "The lightest choice: nothing is made while the video just plays"),
        WATCHING("While Watching", "Also made in the background while the video plays, when it has loaded well ahead"),
        RIGHT_AWAY("Right Away", "The whole video, from the start, as soon as it opens. Uses the most data"),
    }

    data class Values(
        val enabled: Boolean = false,
        val seekrOnly: Boolean = false,
        val apiKey: String = "",
        val include4k: Boolean = false,
        val whenToMake: When = When.SEEKING,
        val storageMb: Int = 50,
        /** When thumbnails were last paused to keep playback smooth (0: never). */
        val lastLowMemoryAt: Long = 0L,
    ) {
        override fun toString() = "Seek thumbnail settings(enabled=$enabled, seekrOnly=$seekrOnly, keyConfigured=${apiKey.isNotEmpty()})"
    }

    val STORAGE_CHOICES = listOf(50, 100, 250, 500)

    // Seek settings are device-wide. A dedicated Keystore-backed preferences file holds the device key.
    private const val DEVICE_SLOT = 0
    private fun credentials(context: Context) = com.nuvio.tv.core.profile.ProtectedProfilePreferences(
        context.applicationContext, "seekr_credentials", "com.nuvio.tv.seekr.credentials.v1"
    )

    private const val FILE = "seek_thumbnails"
    private const val ENABLED = "enabled"
    private const val INCLUDE_4K = "include_4k"
    private const val WHEN = "when"
    private const val STORAGE_MB = "storage_mb"
    private const val LOW_MEMORY_AT = "low_memory_at"

    private val _values = MutableStateFlow(Values())
    val values: StateFlow<Values> = _values.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context): Values {
        if (loaded) return _values.value
        synchronized(this) {
            if (loaded) return _values.value
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _values.value = Values(
                apiKey = credentials(context).read(DEVICE_SLOT).orEmpty(),
                seekrOnly = p.getBoolean("seekr_only", false),
                enabled = p.getBoolean(ENABLED, false),
                // Not chosen yet: on for devices with plenty of memory, off for lower-end ones (anyone can change it).
                include4k = if (p.contains(INCLUDE_4K)) p.getBoolean(INCLUDE_4K, false) else !isLowMemoryDevice(context.applicationContext),
                whenToMake = runCatching { When.valueOf(p.getString(WHEN, null) ?: When.SEEKING.name) }.getOrDefault(When.SEEKING),
                storageMb = p.getInt(STORAGE_MB, 50).takeIf { it in STORAGE_CHOICES } ?: 50,
                lastLowMemoryAt = p.getLong(LOW_MEMORY_AT, 0L),
            )
            loaded = true
        }
        return _values.value
    }

    @Synchronized
    fun update(context: Context, change: (Values) -> Values) {
        load(context)
        val next = change(_values.value)
        val old = _values.value
        val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (next.apiKey != old.apiKey) {
            credentials(context).write(DEVICE_SLOT, next.apiKey.ifBlank { null })
            editor.putBoolean(KEY_REMOVED, next.apiKey.isBlank())
        }
        if (old.enabled != next.enabled) editor.putBoolean(ENABLED, next.enabled)
        if (old.seekrOnly != next.seekrOnly) editor.putBoolean("seekr_only", next.seekrOnly)
        if (old.include4k != next.include4k) editor.putBoolean(INCLUDE_4K, next.include4k)
        if (old.whenToMake != next.whenToMake) editor.putString(WHEN, next.whenToMake.name)
        if (old.storageMb != next.storageMb) editor.putInt(STORAGE_MB, next.storageMb)
        if (old.lastLowMemoryAt != next.lastLowMemoryAt) editor.putLong(LOW_MEMORY_AT, next.lastLowMemoryAt)
        editor.apply()
        _values.value = next
    }

    private const val KEY_REMOVED = "seekr_key_removed"
    private const val API_KEY = "api_key"

    @Synchronized
    internal fun backupFields(context: Context): Map<String, String> {
        val current = load(context)
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return buildMap {
            // Unset options retain this device's defaults, including its memory-based 4K policy.
            mapOf(ENABLED to current.enabled.toString(), "seekr_only" to current.seekrOnly.toString(),
                INCLUDE_4K to current.include4k.toString(), WHEN to current.whenToMake.name,
                STORAGE_MB to current.storageMb.toString()).forEach { (key, value) -> if (prefs.contains(key)) put(key, value) }
            if (current.apiKey.isNotBlank() || prefs.getBoolean(KEY_REMOVED, false)) put(API_KEY, current.apiKey)
        }
    }

    internal fun validateBackup(fields: Map<String, String>) {
        fields.forEach { (key, value) ->
            require(when (key) {
                ENABLED, "seekr_only", INCLUDE_4K -> value == "true" || value == "false"
                WHEN -> When.entries.any { it.name == value }
                STORAGE_MB -> value.toIntOrNull() in STORAGE_CHOICES
                API_KEY -> true
                else -> false
            }) { "Invalid seek preview backup field" }
        }
    }

    @Synchronized
    internal fun restoreFields(context: Context, expected: Map<String, String>, restored: Map<String, String>) {
        validateBackup(restored)
        val current = backupFields(context)
        val fields = restored.filterKeys { current[it] == expected[it] }
        if (fields.isEmpty()) return
        val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        fields.forEach { (key, value) ->
            when (key) {
                API_KEY -> {
                    credentials(context).write(DEVICE_SLOT, value.ifBlank { null })
                    editor.putBoolean(KEY_REMOVED, value.isEmpty())
                }
                WHEN -> editor.putString(key, value)
                STORAGE_MB -> editor.putInt(key, value.toInt())
                else -> editor.putBoolean(key, value.toBooleanStrict())
            }
        }
        editor.apply()
        loaded = false
        load(context)
    }

    /** Android counts this box as low on memory (most cheap sticks and older boxes). */
    fun isLowMemoryDevice(context: Context): Boolean =
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.isLowRamDevice || am.memoryClass <= 192
        }.getOrDefault(false)
}
