package com.nuvio.tv.livetv.sports

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Preserve observed scores independently of which 512 fixtures fit in the first-paint snapshot. */
internal class SportsScoreCache(private val file: File) {
    @Serializable
    private data class TeamScore(val id: String, val score: String?, val winner: Boolean, val periods: List<String>) {
        fun apply(side: Side) = side.copy(score = score, winner = winner, periods = periods)
    }

    @Serializable
    private data class Result(
        val league: String, val start: Long, val observed: Long, val final: Boolean, val status: String?,
        val home: TeamScore, val away: TeamScore, val progress: Float?, val situation: String?
    ) {
        fun sameGame(event: SportsEvent) = event.leagueId == league && event.startMs == start &&
            event.home?.team?.id == home.id && event.away?.team?.id == away.id
    }

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var scores: Map<String, Result> = emptyMap()
    private var needsWrite = false

    @Synchronized fun read(now: Long) {
        if (!file.isFile || file.length() !in 1..SportsPageSnapshot.MAX_BYTES) return
        scores = try {
            json.decodeFromString<Map<String, Result>>(file.readText()).filterValues { it.validAt(now) }
        } catch (_: IOException) {
            emptyMap()
        } catch (_: SerializationException) {
            emptyMap()
        }
    }

    private fun Result.validAt(now: Long): Boolean = start >= now - SportsPageSnapshot.MAX_AGE &&
        observed <= now && (final || now - observed <= SportsPageSnapshot.SCORE_AGE)

    /** Keep ids, scheduled times, teams, broadcasts and channel associations from the current fixture. */
    fun apply(event: SportsEvent, now: Long): SportsEvent {
        val result = scores[event.id] ?: return event
        if (event.source != EventSource.ESPN ||
            !result.sameGame(event) || !result.validAt(now)) return event
        val at = event.updatedAt ?: 0L
        val useSaved = when (event.state) {
            GameState.OFF -> false
            GameState.POST -> result.final && (!event.hasScore || result.observed > at)
            GameState.LIVE -> result.observed > at
            GameState.PRE -> result.final || event.awaitingUpdate || result.observed >= at
        }
        if (!useSaved) return event
        return event.copy(state = if (result.final) GameState.POST else GameState.LIVE, status = result.status,
            home = result.home.apply(requireNotNull(event.home)), away = result.away.apply(requireNotNull(event.away)),
            progress = result.progress, situation = result.situation, awaitingUpdate = false, updatedAt = result.observed)
    }

    /** Called on the scores I/O path before publication, including an immediately following force-stop. */
    @Synchronized fun remember(events: List<SportsEvent>, now: Long) {
        val next = scores.filterValues { it.validAt(now) }.toMutableMap()
        for (event in events) {
            val at = event.updatedAt ?: continue
            if (event.source != EventSource.ESPN || at > now) continue
            val old = next[event.id]
            if (old != null && at < old.observed) continue
            if (old != null && (event.state == GameState.OFF || event.startMs != old.start ||
                    event.home != null && event.away != null && !old.sameGame(event))) next.remove(event.id)
            if (event.state == GameState.OFF || event.awaitingUpdate || !event.hasTime || event.startMs < now - SportsPageSnapshot.MAX_AGE ||
                !event.hasScore) continue
            fun side(s: Side?) = s?.let { TeamScore(it.team.id, it.score, it.winner, it.periods) }
            val result = Result(event.leagueId, event.startMs, at, event.state == GameState.POST, event.status,
                side(event.home) ?: continue, side(event.away) ?: continue, event.progress, event.situation)
            if (!result.validAt(now)) continue
            // A repeated final is durable already. An unchanged live clock can reuse half its freshness window.
            if (old != null && result.copy(observed = old.observed) == old &&
                (result.final || at - old.observed < SportsPageSnapshot.SCORE_AGE / 2)) continue
            next[event.id] = result
        }
        // Stay comfortably within the bounded restore file on busy multi-sport weekends.
        if (next.size > 1024) {
            val expired = next.entries.sortedBy { it.value.observed }.take(next.size - 1024)
            expired.forEach { next.remove(it.key) }
        }
        if (next == scores && !needsWrite) return
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(json.encodeToString<Map<String, Result>>(next))
            FileSystem.SYSTEM.atomicMove(temp.toOkioPath(), file.toOkioPath())
            needsWrite = false
        } catch (_: IOException) {
            // Disk pressure must not prevent the current scoreboard from being displayed.
            needsWrite = true
        } finally {
            temp.delete()
        }
        scores = next
    }
}
