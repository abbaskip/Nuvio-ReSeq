package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MdbListHistoryService @Inject constructor(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository
) {
    suspend fun add(scope: MdbListAuthScope, items: Collection<TrackingHistoryItem>): TrackingMutationResult =
        change(scope, items.toList(), remove = false)

    suspend fun remove(scope: MdbListAuthScope, items: Collection<TrackingMediaReference>): TrackingMutationResult =
        change(scope, items.map { TrackingHistoryItem(it) }, remove = true)

    /** Restore the pre-play timestamp only when the current row is this play. */
    suspend fun undo(scope: MdbListAuthScope, media: TrackingMediaReference,
        event: com.nuvio.tv.core.tracking.TrackingWatchEvent, byPlayId: Boolean = false): Boolean {
        sync.refresh(TrackingRefreshIntent.INVALIDATED)
        return sync.write(scope, setOf(MdbListSyncBucket.WATCHED)) { snapshot ->
            val target = snapshot.mutationTarget(media) ?: return@write snapshot to false
            if (byPlayId) {
                val requested = media.episode ?: return@write snapshot to false
                if (requested.tmdbId != null && requested.tmdbId != target.episodeTmdbId ||
                    requested.tvdbId != null && target.episodeTvdbId != null && requested.tvdbId != target.episodeTvdbId ||
                    !target.scrobbleCoordinatesResolved && requested.tmdbId == null) return@write snapshot to false
                return@write undoEpisodePlay(scope, snapshot, target, event)
            }
            val current = snapshot.watched.singleOrNull(target::matches) ?: return@write snapshot to false
            if (!event.contains(mdbListTimestamp(current.watchedAt))) return@write snapshot to false
            check(event.priorKnown) { "The previous MDBList watch is unknown" }
            val prior = event.priorWatchedAt?.let { Instant.ofEpochMilli(it).toString() }
            val change = MdbListHistoryChange(target, prior)
            val response = api.post(if (prior == null) "/sync/watched/remove" else "/sync/watched",
                mdbListHistoryPayload(listOf(change)), scope)
            val receipt = decodeMdbListHistoryReceipt(response.body, listOf(change), remove = prior == null)
            check(receipt.complete)
            snapshot.copy(watched = snapshot.watched.filterNot(target::matches) +
                if (prior == null) emptyList() else listOf(current.copy(watchedAt = prior))) to true
        }
    }

    private suspend fun undoEpisodePlay(scope: MdbListAuthScope, snapshot: MdbListSyncSnapshot,
        target: MdbListMutationTarget, event: com.nuvio.tv.core.tracking.TrackingWatchEvent
    ): Pair<MdbListSyncSnapshot, Boolean> {
        val id = target.episodeTmdbId?.takeIf { it > 0 } ?: return snapshot to false
        val payload = mdbListResponseElement(api.get("/sync/history/episode/tmdb/$id", scope = scope).body).objectValue()
        check(payload.text("type") == "episode" && payload.text("provider") == "tmdb" && payload.number("id") == id)
        check(payload["plays"] is kotlinx.serialization.json.JsonArray && payload.integer("play_count") == payload.arrayValue("plays").size)
        val plays = payload.arrayValue("plays").map { element ->
            val row = element.objectValue()
            requireNotNull(row.number("play_id")?.takeIf { it > 0 }) to requireNotNull(row.timestamp("watched_at"))
        }
        val candidates = plays.filter { (_, date) ->
            val timestamp = mdbListTimestamp(date)
            event.contains(timestamp) && timestamp != event.priorWatchedAt
        }
        val selected = candidates.singleOrNull() ?: return snapshot to
            (candidates.isEmpty() && payload.flag("truncated") == false)
        val receipt = mdbListResponseElement(api.post("/sync/watched/plays/remove",
            """{"play_ids":[${selected.first}]}""", scope).body).objectValue()
        check(receipt.arrayValue("removed").singleOrNull()?.toString()?.toLongOrNull() == selected.first)
        val remaining = plays.filterNot { it.first == selected.first }.maxByOrNull { mdbListTimestamp(it.second) }
        val watched = snapshot.watched.filterNot { it.episodeTmdbId == id } +
            listOfNotNull(remaining?.let { target.watched(it.second) })
        return snapshot.copy(watched = watched, invalidatedBuckets = snapshot.invalidatedBuckets + MdbListSyncBucket.WATCHED) to true
    }

    private suspend fun change(
        scope: MdbListAuthScope,
        items: List<TrackingHistoryItem>,
        remove: Boolean
    ): TrackingMutationResult {
        var failed = 0
        val resolvable = items.filter { item -> item.media.ids.toMdbListIds() != null }
        failed += items.size - resolvable.size
        for (batch in resolvable.chunked(100)) {
            val result = sync.write(scope, setOf(MdbListSyncBucket.WATCHED)) { snapshot ->
                val projection = sync.currentProjection().takeIf { it !== MdbListProgressProjection.Empty }
                val index = projection?.mediaIndex ?: MdbListMediaIndex(snapshot)
                val mapped = batch.mapNotNull { item ->
                    snapshot.mutationTarget(item.media, projection, index)?.let { target ->
                        MdbListHistoryChange(target, if (remove) null else Instant.ofEpochMilli(
                            item.watchedAtEpochMs ?: System.currentTimeMillis()
                        ).toString())
                    }
                }
                val changes = mapped.distinctBy { it.target.key }
                if (changes.isEmpty()) return@write snapshot to TrackingMutationResult(batch.size, batch.size)
                val response = api.post(
                    if (remove) "/sync/watched/remove" else "/sync/watched",
                    mdbListHistoryPayload(changes), scope,
                    query = if (remove) emptyMap() else mapOf("report_added" to "true")
                )
                val receipt = decodeMdbListHistoryReceipt(response.body, changes, remove)
                val watched = if (remove && receipt.complete) {
                    snapshot.watched.filterNot { record -> changes.any { change ->
                        change.target.matches(record) || change.target.type == MdbListItemType.SHOW &&
                            record.type != MdbListItemType.MOVIE && change.target.media.ids.matches(record.media.ids)
                    } }
                } else {
                    val confirmedTargets = receipt.confirmed.map { record ->
                        MdbListMutationTarget(record.type, record.media, record.season, record.episode,
                            record.episodeTitle, record.episodeTmdbId, record.episodeTvdbId)
                    }
                    snapshot.watched.filterNot { record -> confirmedTargets.any { it.matches(record) } } + receipt.confirmed
                }
                val next = snapshot.copy(
                    watched = watched,
                    invalidatedBuckets = snapshot.invalidatedBuckets +
                        if (receipt.needsSnapshot) setOf(MdbListSyncBucket.WATCHED) else emptySet()
                ).normalizeMedia()
                next to TrackingMutationResult(
                    attemptedCount = batch.size,
                    notFoundCount = (batch.size - mapped.size + receipt.notFoundCount).coerceAtMost(batch.size)
                )
            }
            failed += result.notFoundCount
        }
        if (sync.currentSnapshot()?.invalidatedBuckets?.isNotEmpty() == true) sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(items.size, failed)
    }
}
