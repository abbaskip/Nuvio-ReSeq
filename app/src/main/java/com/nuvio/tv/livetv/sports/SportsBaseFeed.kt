package com.nuvio.tv.livetv.sports

import com.squareup.moshi.JsonReader
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared public catalogue only. No playlist, credentials, user preferences or Gemini output. */
internal class SportsBaseFeed(
    private val dir: File,
    private val url: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    allowLocalhost: Boolean = false
) {
    val enabled = url.isNotBlank()
    private val gate = Mutex()
    private val file = File(dir, "base-feed.json")
    private val stateFile = File(dir, "base-feed-state.json")
    private val backup = File(dir, "base-feed.backup")
    // Keep only the validated header in memory. Provider payloads stay on disk.
    private var snapshot = sequenceOf(file, backup).mapNotNull { saved ->
        try {
            require(saved.length() <= MAX_BYTES)
            saved.source().buffer().use { parse(it, clock()).copy(storage = saved) }
        } catch (_: Exception) { null }
    }.firstOrNull()
    private var etag: String? = null
    private var lastCheck = 0L
    private var nextAttempt = 0L

    init {
        if (enabled) {
            val endpoint = url.toHttpUrl()
            require(endpoint.isHttps || (allowLocalhost && endpoint.host in setOf("127.0.0.1", "localhost")))
            require(endpoint.username.isEmpty() && endpoint.password.isEmpty())
        }
        runCatching {
            val state = Json.parseToJsonElement(stateFile.readText()).jsonObject
            // A changed endpoint must never send another endpoint's validator or reuse its polling state.
            if (state["url"]?.jsonPrimitive?.content == url) {
                etag = state["etag"]?.jsonPrimitive?.contentOrNull
                lastCheck = state["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0
            }
        }
    }

    fun cached(): Snapshot? = snapshot?.takeIf { clock() - it.generatedAt < MAX_AGE && it.storage?.isFile == true }

    suspend fun refresh(): Snapshot? = gate.withLock {
        if (!enabled) return@withLock null
        val now = clock()
        if (now < nextAttempt || (cached() != null && now >= lastCheck && now - lastCheck < REFRESH)) return@withLock cached()
        // Failed/invalid feeds back off instead of triggering a per-device provider fan-out.
        nextAttempt = now + 15 * 60_000L
        val context = currentCoroutineContext()
        dir.mkdirs()
        val temp = File.createTempFile("base-feed-", ".tmp", dir)
        try {
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .apply { if (cached() != null) etag?.let { header("If-None-Match", it) } }.build()
            val result = http.newCall(request).consumeSportsResponse { response ->
                try {
                    if (response.code != 304) {
                        require(response.isSuccessful)
                        val body = response.body ?: error("Empty sports catalogue")
                        require(body.contentLength() <= MAX_BYTES)
                        body.byteStream().use { input ->
                            temp.outputStream().use { output ->
                                val bytes = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    context.ensureActive()
                                    val count = input.read(bytes)
                                    if (count < 0) break
                                    total += count
                                    require(total <= MAX_BYTES)
                                    output.write(bytes, 0, count)
                                }
                            }
                        }
                    }
                    context.ensureActive()
                    response.code to response.header("ETag")
                } finally {
                    // Cancellation can resume the caller before the socket worker exits.
                    if (!context[kotlinx.coroutines.Job]!!.isActive) temp.delete()
                }
            }
            context.ensureActive()
            if (result.first == 304) {
                require(cached() != null)
            } else {
                // Validation and conversion run on the caller's worker, with cancellation
                // checkpoints, never as a full JSON tree on an OkHttp callback thread.
                val incoming = temp.source().buffer().use { parse(it, now, context::ensureActive) }
                require(incoming.generatedAt >= (snapshot?.generatedAt ?: 0))
                context.ensureActive()
                backup.delete()
                if (file.exists()) require(file.renameTo(backup))
                if (!temp.renameTo(file)) {
                    backup.renameTo(file)
                    error("Unable to save sports catalogue")
                }
                backup.delete()
                snapshot = incoming.copy(storage = file)
                etag = result.second
            }
            nextAttempt = 0L
            lastCheck = now
            stateFile.writeText(buildJsonObject {
                put("url", url); put("checkedAt", now); etag?.let { put("etag", it) }
            }.toString())
        } catch (cancelled: CancellationException) {
            nextAttempt = 0L
            throw cancelled
        } catch (_: Exception) {
            // Keep the last validated catalogue and the failure cooldown.
        } finally {
            temp.delete()
        }
        currentCoroutineContext().ensureActive()
        cached()
    }

    /** Only one provider payload is materialized at a time, and never retained here. */
    suspend fun readEntries(expected: Snapshot, consume: (Entry) -> Unit): Boolean = gate.withLock {
        if (snapshot !== expected) return@withLock false
        val context = currentCoroutineContext()
        val saved = expected.storage ?: return@withLock false
        saved.source().buffer().use { parse(it, clock(), context::ensureActive, consume) }
        true
    }

    data class Entry(val provider: String, val leagueId: String, val fetchedAt: Long, val payload: String)
    data class Snapshot(val generatedAt: Long, val entryCount: Int, val metadata: JsonObject,
                        internal val storage: File? = null)

    companion object {
        const val REFRESH = 6 * 60 * 60_000L
        const val MAX_AGE = 7 * 24 * 60 * 60_000L
        const val MAX_BYTES = 12L * 1024 * 1024
        private val LEAGUE_ID = Regex("[a-z0-9/_.-]{1,100}")

        fun parse(raw: String, now: Long): Snapshot = Buffer().writeUtf8(raw).use { parse(it, now) }

        internal fun parse(source: BufferedSource, now: Long, checkCancelled: () -> Unit = {},
                           consume: ((Entry) -> Unit)? = null): Snapshot {
            val reader = JsonReader.of(source)
            var version = 0
            var generated = 0L
            var metadata = buildJsonObject { put("leagues", JsonArray(emptyList())) }
            val ids = HashSet<String>()
            val fetched = ArrayList<Long>()
            reader.beginObject()
            while (reader.hasNext()) {
                checkCancelled()
                when (reader.nextName()) {
                    "schemaVersion" -> version = reader.nextInt()
                    "generatedAt" -> generated = reader.nextLong()
                    "metadata" -> metadata = reader.nextSource().use { Json.parseToJsonElement(it.readUtf8()).jsonObject }
                    "entries" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            checkCancelled()
                            var provider: String? = null
                            var id: String? = null
                            var at: Long? = null
                            var payload: String? = null
                            var hasPayload = false
                            reader.beginObject()
                            while (reader.hasNext()) {
                                checkCancelled()
                                when (reader.nextName()) {
                                    "provider" -> provider = reader.nextString()
                                    "leagueId" -> id = reader.nextString()
                                    "fetchedAt" -> at = reader.nextLong()
                                    "payload" -> {
                                        if (consume == null) validatePayload(reader, checkCancelled)
                                        else {
                                            payload = reader.nextSource().use { it.readUtf8() }
                                            Buffer().writeUtf8(payload).use { validatePayload(JsonReader.of(it), checkCancelled) }
                                        }
                                        hasPayload = true
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            require(provider in setOf("espn", "tsdb", "calendar"))
                            require(id != null && LEAGUE_ID.matches(id) && ids.add("$provider:$id"))
                            require(at != null && at >= now - MAX_AGE && hasPayload)
                            fetched += at
                            if (consume != null) consume(Entry(requireNotNull(provider), id, at, requireNotNull(payload)))
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonReader.Token.END_DOCUMENT)
            require(version == 1 && generated in (now - MAX_AGE)..(now + 10 * 60_000L))
            require(fetched.isNotEmpty() && fetched.all { it <= generated + 10 * 60_000L })
            return Snapshot(generated, fetched.size, metadata)
        }

        private fun validatePayload(reader: JsonReader, checkCancelled: () -> Unit) {
            var events = false
            reader.beginObject()
            while (reader.hasNext()) {
                checkCancelled()
                if (reader.nextName() == "events") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        checkCancelled()
                        reader.skipValue()
                    }
                    reader.endArray()
                    events = true
                } else reader.skipValue()
            }
            reader.endObject()
            require(events)
        }

        /** A six-hour catalogue snapshot cannot act as a live-score feed. */
        fun scheduled(event: SportsEvent, fetchedAt: Long): SportsEvent = if (event.state == GameState.LIVE)
            event.copy(state = GameState.PRE, status = "Live score pending", away = event.away?.copy(score = null),
                home = event.home?.copy(score = null), progress = null, situation = null, standings = emptyList(),
                updatedAt = fetchedAt)
            else event.copy(updatedAt = fetchedAt)
    }
}

/** At most two relevant league/date scoreboards per round; catalogue dates never fan out on the TV. */
internal object SportsRefreshPolicy {
    fun scoreboardDay(league: League, anchor: Long): String = java.time.format.DateTimeFormatter
        .ofPattern("yyyyMMdd").withZone(java.time.ZoneId.of(
            if (league.sport == Sport.CRICKET) "UTC" else "America/New_York"))
        .format(java.time.Instant.ofEpochMilli(anchor))

    private fun relevant(event: SportsEvent, league: League, viewing: Boolean, now: Long): Boolean {
        if (league.espnPath == null || (!event.hasTime && event.endMs == null) ||
            event.state == GameState.POST || event.state == GameState.OFF) return false
        val ongoing = now >= event.startMs - 10 * 60_000L &&
            now <= (event.endMs?.let { it + if (event.hasTime) 0L else 24 * 60 * 60_000L }
                ?: (event.startMs + league.minutes * 60_000L)) + 30 * 60_000L
        // A saved schedule can still say PRE after a game has finished. Refresh its result
        // while browsing instead of dropping it forever once its expected duration passes.
        val pendingResult = viewing && event.hasTime && event.state == GameState.PRE &&
            event.startMs > now - 24 * 60 * 60_000L && event.startMs <= now
        return ongoing || pendingResult
    }

    /** ESPN's date belongs to the game, which may still be yesterday in US Eastern. */
    fun scoreboardAnchor(events: List<SportsEvent>, league: League, focused: String?, playing: Set<String>,
                         viewing: Boolean, now: Long): Long = events.asSequence()
        .filter { it.leagueId == league.id && relevant(it, league, viewing, now) }
        .sortedWith(compareBy<SportsEvent> { if (it.id == focused || it.id in playing) 0 else 1 }
            .thenBy { kotlin.math.abs(it.startMs - now) })
        .firstOrNull()?.let { event ->
            if (league.sport == Sport.CRICKET && event.endMs?.let { it > now } == true && now >= event.startMs)
                now else event.startMs
        } ?: now

    fun choose(events: List<SportsEvent>, leagues: List<League>, focused: String?, playing: Set<String>,
               followed: Set<String>, viewing: Boolean, now: Long, attempts: Map<String, Long>): List<League> {
        val byId = leagues.associateBy { it.id }
        val current = events.filter { e ->
            val league = byId[e.leagueId]
            league != null && relevant(e, league, viewing, now)
        }.groupBy { it.leagueId }
        fun priority(id: String): Int = when {
            current[id].orEmpty().any { it.id == focused || it.id in playing } -> 0
            id in followed -> 1
            else -> 2
        }
        // Newly added cricket feeds must work before the next shared catalogue generation.
        // Discovery remains within the same two-request budget and backs off when empty.
        val discover = if (viewing) leagues.filter { it.sport == Sport.CRICKET && it.espnPath != null &&
            events.none { e -> e.leagueId == it.id } }.map { it.id } else emptyList()
        return (current.keys + discover).distinct().filter { id -> (viewing || (priority(id) == 0 && byId[id]?.sport != Sport.CRICKET)) &&
            now - (attempts[id] ?: 0) >= if (id in discover || byId[id]?.sport == Sport.CRICKET) 5 * 60_000L else 60_000L }
            .sortedWith(compareBy<String> { if (priority(it) == 0) 0 else 1 }
                .thenBy { attempts[it] ?: 0L }.thenBy { priority(it) })
            .take(2).mapNotNull(byId::get)
    }
}
