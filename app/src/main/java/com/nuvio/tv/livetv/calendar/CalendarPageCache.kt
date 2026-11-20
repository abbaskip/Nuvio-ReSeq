package com.nuvio.tv.livetv.calendar

import com.squareup.moshi.Moshi
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException

/** Keep the six most recently visited weeks per profile. Only the visible Calendar opens these files. */
internal class CalendarPageCache(private val directory: File) {
    private val adapter = Moshi.Builder().build().adapter(CalendarPage::class.java)

    fun read(profile: Int, region: String, zone: String, weekStart: Long = 0): CalendarPage? {
        val generation = generation(profile)
        val file = File(directory, "profile-$profile-week-$weekStart.json")
        if (file.length() !in 1..MAX_BYTES) return null
        return try {
            file.source().buffer().use { input ->
                adapter.fromJson(input)?.takeIf { it.version == 2 && it.weekStart == weekStart && it.region == region && it.zone == zone &&
                    it.items.size <= CalendarPolicy.MAX_ITEMS && it.checked.size <= CalendarPolicy.MAX_CHECKS }?.copy(generation = generation)
            }
        } catch (_: IOException) { null }
        catch (_: com.squareup.moshi.JsonDataException) { null }
    }

    fun write(profile: Int, page: CalendarPage) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Calendar cache unavailable")
        val prefix = "profile-$profile-week-"
        val target = File(directory, prefix + page.weekStart + ".json")
        val pending = File(directory, prefix + page.weekStart + ".tmp")
        try {
            pending.outputStream().bufferedWriter(Charsets.UTF_8).use { it.write(adapter.toJson(page)) }
            if (pending.length() > MAX_BYTES) throw IOException("Calendar snapshot too large")
            synchronized(Companion) {
                if (generation(profile) != page.generation) throw kotlinx.coroutines.CancellationException("Calendar profile removed")
                FileSystem.SYSTEM.atomicMove(pending.toOkioPath(), target.toOkioPath())
                directory.listFiles { file -> file.name.startsWith(prefix) && file.extension == "json" && file != target }
                    .orEmpty().sortedByDescending { it.lastModified() }.drop(5).forEach { it.delete() }
                File(directory, "profile-$profile.json").delete()
            }
        } finally {
            pending.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        private val profiles = mutableMapOf<Int, Long>()
        private var account = 0L
        private var sequence = 0L

        @Synchronized fun generation(profile: Int): Long = profiles[profile] ?: account

        /** Invalidate pending exit writes as well as the on-disk snapshot when a profile ID is reused. */
        @Synchronized fun clear(cacheDir: File, profile: Int? = null) {
            val directory = File(cacheDir, "calendar")
            sequence++
            if (profile == null) {
                account = sequence
                profiles.clear()
                directory.listFiles()?.forEach { it.delete() }
                directory.delete()
            } else {
                profiles[profile] = sequence
                File(directory, "profile-$profile.json").delete()
                File(directory, "profile-$profile.tmp").delete()
                directory.listFiles { file -> file.name.startsWith("profile-$profile-week-") }?.forEach { it.delete() }
            }
        }
    }
}
