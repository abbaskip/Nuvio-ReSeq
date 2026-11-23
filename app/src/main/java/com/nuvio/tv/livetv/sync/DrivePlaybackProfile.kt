package com.nuvio.tv.livetv.sync

import com.nuvio.tv.data.local.EpisodeShuffleStore
import com.nuvio.tv.data.local.TvMixStore
import com.nuvio.tv.data.local.decodeShuffleSelections
import com.nuvio.tv.data.local.decodeTvMix
import com.nuvio.tv.data.local.validateTvMixBackup
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.TvMix
import org.json.JSONObject

internal data class DrivePlaybackProfile(
    val id: Int,
    val name: String,
    val mixes: List<TvMix>,
    val shuffle: Map<String, EpisodeShuffleSettings>
) {
    suspend fun copyTo(profile: Int, mixStore: TvMixStore, shuffleStore: EpisodeShuffleStore) {
        mixes.forEach { mixStore.save(profile, it) }
        shuffle.forEach { (id, settings) -> shuffleStore.save(id, settings, profile) }
        if (shuffle.values.any { it.enabled }) shuffleStore.showRestoredShuffleIfUnset(profile)
    }
}

internal fun playbackProfilesFromBackup(envelope: JSONObject): List<DrivePlaybackProfile> {
    val data = driveBackupData(envelope)
    val keys = data.keys().asSequence().toList()
    fun entries(prefix: String) = keys.filter { it.startsWith(prefix) }.associate {
        it.removePrefix(prefix) to data.getJSONObject(it).getString("v")
    }
    val owners = keys.filter { it.startsWith("reseq_profile:") }.map { it.substringAfter(':').toInt() }.sorted()
    for (key in keys.filter { it.startsWith("reseq_mix:") || it.startsWith("reseq_shuffle:") }) {
        require(key.substringAfter(':').substringBefore(':').toInt() in owners) { "Backup is missing its profile information." }
    }
    return owners.map { id ->
        val mixes = entries("reseq_mix:$id:")
        validateTvMixBackup(mixes)
        DrivePlaybackProfile(id, data.getJSONObject("reseq_profile:$id").getString("v"),
            mixes.values.map(::decodeTvMix), decodeShuffleSelections(entries("reseq_shuffle:$id:")))
    }
}
