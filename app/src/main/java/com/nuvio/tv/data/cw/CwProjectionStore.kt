package com.nuvio.tv.data.cw

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One fsynced document, atomically replaced. A crash exposes either complete revision. */
internal class CwProjectionStore(private val directory: File) {
    private val gson = Gson()
    private fun file(profile: Int, source: String) = File(directory, "projection_${profile}_${source}.json")

    fun read(profile: Int, source: String): CwProjection {
        val target = file(profile, source)
        if (target.exists()) {
            val json = com.google.gson.JsonParser.parseString(target.readText()).asJsonObject
            val decoded = gson.fromJson(json, CwProjection::class.java)
            val value = if (json.has("completed")) decoded else decoded.copy(completed = emptyMap())
            require(value.schema == 1 && value.profileId == profile && value.source == source) { "Invalid CW projection" }
            return value
        }
        // Claim the legacy snapshot only once, for the first selected source. Keep the old
        // files intact: migration must not clear a user's history or enrichment cache.
        val claimed = File(directory, "legacy_${profile}.migrated")
        if (claimed.exists() || directory.listFiles()?.any { it.name.startsWith("projection_${profile}_") && it.extension == "json" } == true)
            return CwProjection(profileId = profile, source = source)
        fun <T> legacy(name: String, type: java.lang.reflect.Type): List<T> {
            val old = File(directory, "${name}_${profile}.json")
            return if (old.exists()) gson.fromJson<List<T>>(old.readText(), type) ?: emptyList() else emptyList()
        }
        val progress = legacy<CachedInProgressItem>("inprogress", object : TypeToken<List<CachedInProgressItem>>() {}.type)
        val next = legacy<CachedNextUpItem>("nextup", object : TypeToken<List<CachedNextUpItem>>() {}.type)
        val members = next.associate { it.contentId to CwMember(nextUp = it) }.toMutableMap()
        progress.forEach { members[it.contentId] = CwMember(inProgress = it) }
        val migrated = CwProjection(profileId = profile, source = source, members = members)
        write(migrated)
        claimed.writeText(source)
        return migrated
    }

    fun write(state: CwProjection) {
        directory.mkdirs()
        val target = file(state.profileId, state.source)
        val pending = File(directory, target.name + ".pending")
        FileOutputStream(pending).use { stream ->
            stream.write(gson.toJson(state).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        // Never fall back to a non-atomic copy. On failure the previous revision stays valid.
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
