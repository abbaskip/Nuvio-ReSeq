package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.buffer
import okio.source
import java.io.File

/** A bounded first paint, independent of bulk-import workers. Never a replacement for fresh data. */
internal object SportsPageSnapshot {
    const val MAX_EVENTS = 512
    const val MAX_MATCHES = 8
    const val MAX_BYTES = 2L * 1024 * 1024
    const val SCORE_AGE = 15 * 60_000L
    const val MAX_AGE = 7 * 86_400_000L
    val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val adapter by lazy { Moshi.Builder().build().adapter(SportsSnapshotItem::class.java) }

    data class Page(val savedAt: Long, val items: List<SportsSnapshotItem>) {
        val events get() = items.map { it.event.event() }
        fun matches(channels: Map<String, LiveChannel>): Map<String, List<ChannelMatch>> = items.mapNotNull { item ->
            item.matches.mapNotNull { saved -> channels[saved.key]?.let { saved.match(it) } }
                .takeIf { it.isNotEmpty() }?.let { item.event.id to it }
        }.toMap()
    }

    /** Each fixture has its own observation time; a recent file cannot freshen an old score. */
    fun display(event: SportsEvent, observedAt: Long, now: Long): SportsEvent? {
        if (event.state == GameState.POST) return event.takeIf { it.startMs >= now - MAX_AGE }
        val crossedKickoff = event.state == GameState.PRE && event.startMs <= now &&
            (observedAt < event.startMs || event.status == "Live score pending")
        val stalePlay = (event.state == GameState.LIVE || (event.state == GameState.OFF && event.hasCricketPauseStatus())) &&
            (observedAt > now || now - observedAt > SCORE_AGE)
        return if (crossedKickoff || stalePlay || event.awaitingUpdate) event.copy(
            state = GameState.PRE, status = "Updating…", awaitingUpdate = true,
            home = event.home?.copy(score = null, periods = emptyList(), winner = false),
            away = event.away?.copy(score = null, periods = emptyList(), winner = false),
            progress = null, situation = null, standings = emptyList(), note = null, updatedAt = observedAt
        ) else event.copy(updatedAt = event.updatedAt ?: observedAt)
    }

    suspend fun read(file: File, now: Long): Page? {
        val saved = file.takeIf { it.isFile } ?: File(file.parentFile, file.name + ".bak")
        if (!saved.isFile || saved.length() > MAX_BYTES) return null
        val context = currentCoroutineContext()
        return try {
            saved.source().buffer().use { source ->
                val reader = JsonReader.of(source)
                var version = 0
                var at = 0L
                var count = 0
                val items = ArrayList<SportsSnapshotItem>()
                reader.beginObject()
                while (reader.hasNext()) {
                    context.ensureActive()
                    when (reader.nextName()) {
                        "version" -> version = reader.nextInt()
                        "savedAt" -> at = reader.nextLong()
                        "items" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                context.ensureActive()
                                require(count++ < MAX_EVENTS)
                                val item = requireNotNull(adapter.fromJson(reader))
                                require(item.matches.size <= MAX_MATCHES && item.event.hits.size <= MAX_MATCHES)
                                display(item.event.event(), item.observedAt, now)?.let { event ->
                                    items += item.copy(event = SportsSnapshotEvent.from(event))
                                }
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                require(version == 1 && at in (now - MAX_AGE)..now && source.exhausted())
                Page(at, items)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun write(file: File, events: List<SportsEvent>, matches: Map<String, List<ChannelMatch>>,
        savedAt: Long, from: Long, to: Long, followed: (SportsEvent) -> Boolean) {
        val context = currentCoroutineContext()
        val selected = events.asSequence().filter { it.startMs < to && (it.endMs ?: it.startMs) >= from }
            .sortedWith(compareBy<SportsEvent> { !followed(it) }.thenBy { it.state == GameState.POST }.thenBy { it.startMs })
            .take(MAX_EVENTS).toList()
        if (selected.isEmpty()) return
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.bufferedWriter().use { writer ->
                writer.write("{\"version\":1,\"savedAt\":$savedAt,\"items\":[")
                selected.forEachIndexed { index, event ->
                    context.ensureActive()
                    if (index > 0) writer.write(",")
                    val item = SportsSnapshotItem(SportsSnapshotEvent.from(event), event.updatedAt ?: savedAt,
                        matches[event.id].orEmpty().take(MAX_MATCHES).map(SportsSnapshotMatch::from))
                    writer.write(adapter.toJson(item))
                    writer.flush()
                    require(temp.length() <= MAX_BYTES)
                }
                writer.write("]}")
            }
            require(temp.length() <= MAX_BYTES)
            context.ensureActive()
            val backup = File(file.parentFile, file.name + ".bak")
            backup.delete()
            if (file.exists()) require(file.renameTo(backup))
            if (!temp.renameTo(file)) {
                backup.renameTo(file)
                error("Cannot commit Sports snapshot")
            }
            backup.delete()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve the previous complete snapshot. */ }
        finally { temp.delete() }
    }
}

@JsonClass(generateAdapter = true)
internal data class SportsSnapshotItem(val event: SportsSnapshotEvent, val observedAt: Long, val matches: List<SportsSnapshotMatch>)

@JsonClass(generateAdapter = true)
internal data class SportsSnapshotTeam(val leagueId: String, val id: String, val name: String, val fullName: String,
    val abbr: String, val location: String?, val color: Long?, val altColor: Long?, val logo: String?) {
    fun team() = TeamInfo(leagueId, id, name, fullName, abbr, location, color, altColor, logo)
    companion object { fun from(t: TeamInfo) = SportsSnapshotTeam(t.leagueId, t.id, t.name, t.fullName, t.abbr, t.location, t.color, t.altColor, t.logo) }
}

@JsonClass(generateAdapter = true)
internal data class SportsSnapshotSide(val team: SportsSnapshotTeam, val score: String?, val home: Boolean,
    val winner: Boolean, val periods: List<String>) {
    fun side() = Side(team.team(), score, null, winner, home, periods)
    companion object { fun from(s: Side) = SportsSnapshotSide(SportsSnapshotTeam.from(s.team), s.score, s.home, s.winner, s.periods.take(8)) }
}

@JsonClass(generateAdapter = true)
internal data class SportsSnapshotProgram(val start: Long, val stop: Long, val title: String, val description: String?) {
    fun program() = EpgProgram(startMs = start, stopMs = stop, title = title, description = description)
    companion object { fun from(p: EpgProgram) = SportsSnapshotProgram(p.startMs, p.stopMs, p.title.take(400), p.description?.take(800)) }
}

@JsonClass(generateAdapter = true)
internal data class SportsSnapshotMatch(val key: String, val score: Int, val how: String, val quality: String?,
    val catchup: Boolean, val program: SportsSnapshotProgram?) {
    fun match(channel: LiveChannel) = ChannelMatch(channel, score, how, program?.program(), quality, catchup)
    companion object { fun from(m: ChannelMatch) = SportsSnapshotMatch(m.channel.key, m.score, m.how.take(400), m.quality, m.catchup, m.program?.let(SportsSnapshotProgram::from)) }
}

@JsonClass(generateAdapter = true)
internal data class SportsSnapshotHit(val key: String, val program: SportsSnapshotProgram)

@JsonClass(generateAdapter = true)
internal data class SportsSnapshotLeague(val id: String, val sport: String, val name: String, val short: String,
    val kind: String, val color: Long, val minutes: Int) {
    fun league() = League(id, Sport.valueOf(sport), name, short, kind = LeagueKind.valueOf(kind), color = color, minutes = minutes)
    companion object { fun from(l: League) = SportsSnapshotLeague(l.id, l.sport.name, l.name, l.short, l.kind.name, l.color, l.minutes) }
}

@JsonClass(generateAdapter = true)
internal data class SportsSnapshotEvent(val id: String, val leagueId: String, val start: Long, val end: Long?,
    val hasTime: Boolean, val state: String, val title: String, val subtitle: String?, val status: String?,
    val away: SportsSnapshotSide?, val home: SportsSnapshotSide?, val source: String, val espnId: String?,
    val broadcasts: List<String>, val note: String?, val word: String?, val updatedAt: Long?, val awaitingUpdate: Boolean,
    val hits: List<SportsSnapshotHit>, val league: SportsSnapshotLeague? = null) {
    fun event() = SportsEvent(id, leagueId, start, hasTime, GameState.valueOf(state), title, subtitle, status,
        away?.side(), home?.side(), broadcasts, source = EventSource.valueOf(source), endMs = end,
        espnId = espnId, note = note, word = word, updatedAt = updatedAt, awaitingUpdate = awaitingUpdate,
        guideHits = hits.map { it.key to it.program.program() })
    companion object {
        fun from(e: SportsEvent) = SportsSnapshotEvent(e.id, e.leagueId, e.startMs, e.endMs, e.hasTime, e.state.name,
            e.title.take(400), e.subtitle?.take(400), e.status?.take(200), e.away?.let(SportsSnapshotSide::from),
            e.home?.let(SportsSnapshotSide::from), e.source.name, e.espnId, e.broadcasts.take(16), e.note?.take(200),
            e.word, e.updatedAt, e.awaitingUpdate, e.guideHits.take(SportsPageSnapshot.MAX_MATCHES).map { (key, p) -> SportsSnapshotHit(key, SportsSnapshotProgram.from(p)) },
            SportsCatalog.league(e.leagueId)?.let(SportsSnapshotLeague::from))
    }
}
