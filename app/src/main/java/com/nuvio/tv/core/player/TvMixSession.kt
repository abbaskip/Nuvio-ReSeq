package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.TvMixSelection
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Ephemeral, explicitly scoped to a profile and the exact selected episode. */
@Singleton
class TvMixSession @Inject constructor() {
    data class Playing(val profileId: Int, val mixId: String, val selection: TvMixSelection,
        val token: String = UUID.randomUUID().toString(), val continuation: Boolean = false,
        val preferredBingeGroup: String? = null)
    data class ExternalReturn(val playing: Playing, val completed: Boolean, val autoplay: Boolean)
    val externalReturn = MutableStateFlow<ExternalReturn?>(null)
    val sourceFailures = MutableStateFlow<Playing?>(null)
    fun reportSourceUnavailable(expected: Playing): Boolean {
        if (playing.value !== expected) return false
        sourceFailures.value = expected
        return true
    }
    fun returnedExternally(completed: Boolean, autoplay: Boolean, expected: Playing? = playing.value) {
        expected?.takeIf { playing.value === it }?.let { externalReturn.value = ExternalReturn(it, completed, autoplay) }
    }
    val playing = MutableStateFlow<Playing?>(null)
    fun matches(profileId: Int, contentId: String?, videoId: String?) = playing.value?.let {
        it.profileId == profileId && it.selection.meta.id == contentId && it.selection.video.id == videoId
    } == true
    fun continuationFor(profileId: Int, contentId: String?, videoId: String?) = playing.value?.takeIf {
        it.continuation && it.profileId == profileId && it.selection.meta.id == contentId && it.selection.video.id == videoId
    }
    fun end() { playing.value = null; externalReturn.value = null; sourceFailures.value = null }
}
