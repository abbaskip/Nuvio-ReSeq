package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MdbListScrobbleService @Inject constructor(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository
) {
    suspend fun scrobble(scope: MdbListAuthScope, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!event.progressPercent.isFinite() || event.media.ids.toMdbListIds() == null) return
        sync.write(scope, setOf(MdbListSyncBucket.WATCHED, MdbListSyncBucket.PLAYBACK)) { snapshot ->
            val projection = sync.currentProjection().takeIf { it !== MdbListProgressProjection.Empty }
            val target = snapshot.mutationTarget(event.media, projection) ?: return@write snapshot to Unit
            if (target.type == MdbListItemType.SHOW || !target.scrobbleCoordinatesResolved) return@write snapshot to Unit
            val response = api.post("/scrobble/${action.wireValue}", target.scrobbleBody(event.progressPercent.coerceIn(0.0, 100.0)).toString(), scope)
            val receipt = decodeMdbListScrobbleReceipt(response, target, action, System.currentTimeMillis())
            snapshot.applyScrobble(receipt, projection?.mediaIndex) to Unit
        }
    }

    suspend fun clearEpisodePause(scope: MdbListAuthScope, contentId: String, season: Int, episode: Int, tmdbId: Long?) {
        val rows = decodeMdbListPlayback(api.get("/sync/playback", scope = scope).body)
        val matches = rows.filter { it.type == MdbListItemType.EPISODE && contentId in it.media.ids.aliases() &&
            (if (tmdbId != null && it.episodeTmdbId != null) it.episodeTmdbId == tmdbId else it.season == season && it.episode == episode) }
        if (matches.isEmpty()) return
        // MDBList clears by episode, so a duplicate address cannot target an individual session.
        val row = matches.single()
        val target = MdbListMutationTarget(row.type, row.media, row.season, row.episode)
        check(rows.count(target::matches) == 1)
        sync.write(scope, setOf(MdbListSyncBucket.PLAYBACK)) { snapshot ->
            val body = mdbListResponseElement(api.post("/scrobble/clear", target.scrobbleBody().toString(), scope).body).objectValue()
            check(body.text("action") == "clear" && body.flag("deleted") != null)
            snapshot.copy(playback = snapshot.playback.filterNot(target::matches)) to Unit
        }
    }

    suspend fun clear(scope: MdbListAuthScope, contentId: String, season: Int?, episode: Int?) {
        sync.ensureLoaded()
        val sessions = sync.currentSnapshot()?.playback.orEmpty().filter { session ->
            contentId in session.media.ids.aliases() &&
                (season == null || episode == null || session.season == season && session.episode == episode)
        }
        for (session in sessions) {
            sync.write(scope, setOf(MdbListSyncBucket.PLAYBACK)) { snapshot ->
                val target = MdbListMutationTarget(session.type, session.media, session.season, session.episode)
                try {
                    val response = api.post("/scrobble/clear", target.scrobbleBody().toString(), scope)
                    val body = mdbListResponseElement(response.body).objectValue()
                    if (body.text("action") != "clear" || body.flag("deleted") == null) throw MdbListDecodingException()
                } catch (error: MdbListApiException) {
                    if (error.status != 404) throw error
                }
                snapshot.copy(playback = snapshot.playback.filterNot(target::matches)) to Unit
            }
        }
    }
}
