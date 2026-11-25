package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

internal enum class GuideMatchDecision { MATCH, DIFFERENT, UNCERTAIN }

/** A content-keyed decision cache and a persistent device-wide request budget, not another guide. */
internal class GeminiGuideCache(private val dir: File, usageDir: File = dir,
                                private val clock: () -> Long = System::currentTimeMillis) {
    init { dir.mkdirs(); usageDir.mkdirs() }
    private val usageFile = File(usageDir, "gemini-guide-usage.json")
    private fun read(file: File) = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
    private fun file(profile: Int) = File(dir, "gemini-guide-$profile.json")

    @Synchronized fun get(profile: Int, key: String): GuideMatchDecision? {
        val entry = (read(file(profile))?.get(key) as? JsonObject) ?: return null
        val at = entry["at"]?.jsonPrimitive?.longOrNull ?: return null
        if (clock() - at !in 0 until 3 * 24 * 60 * 60_000L) return null
        return entry["decision"]?.jsonPrimitive?.contentOrNull?.let { runCatching { GuideMatchDecision.valueOf(it) }.getOrNull() }
    }
    @Synchronized fun put(profile: Int, key: String, decision: GuideMatchDecision) {
        save(profile, key, buildJsonObject { put("decision", decision.name) })
    }

    @Synchronized fun observation(profile: Int, key: String): SportsGuideFixtures.Observation? {
        val data = read(file(profile))?.get(key) as? JsonObject ?: return null
        val at = data["at"]?.jsonPrimitive?.longOrNull ?: return null
        if (clock() - at !in 0 until SportsGuideFixtures.VALID_MS) return null
        val url = data["url"]?.jsonPrimitive?.contentOrNull ?: return SportsGuideFixtures.Observation(null, at)
        if (!SportsFixtureDiscovery.isSourceUrl(url)) return null
        val start = data["start"]?.jsonPrimitive?.longOrNull ?: return null
        val name = data["competition"]?.jsonPrimitive?.contentOrNull ?: return null
        val evidence = data["evidence"]?.jsonPrimitive?.contentOrNull ?: return null
        return SportsGuideFixtures.Observation(SportsFixtureDiscovery.Proof(url, name, start, evidence), at)
    }

    @Synchronized fun remember(profile: Int, key: String, proof: SportsFixtureDiscovery.Proof?) {
        save(profile, key, buildJsonObject {
            if (proof != null) {
                put("url", proof.url); put("competition", proof.competition)
                put("start", proof.startMs); put("evidence", proof.evidence)
            }
        })
    }

    private fun save(profile: Int, key: String, fields: JsonObject) {
        dir.mkdirs()
        val now = clock()
        val entries = read(file(profile)).orEmpty().filterValues {
            val at = it.jsonObject["at"]?.jsonPrimitive?.longOrNull ?: 0
            now - at in 0 until 3 * 24 * 60 * 60_000L
        }.toMutableMap()
        entries[key] = JsonObject(fields + ("at" to JsonPrimitive(now)))
        // No raw EPG descriptions, channel identifiers or API keys are retained.
        val bounded = entries.entries.sortedByDescending { it.value.jsonObject["at"]!!.jsonPrimitive.long }.take(500)
        file(profile).writeText(JsonObject(bounded.associate { it.key to it.value }).toString())
    }
    // All callers, including manual lookups and different profiles, share this on-device ledger.
    companion object {
        private val budgetLock = Any()

        fun quotaPause(retryAfter: String?, now: Long, errorBody: String = ""): Long {
            val header = retryAfter?.trim().orEmpty()
            val seconds = header.toLongOrNull()?.takeIf { it >= 0 }
            val delay = seconds?.coerceAtMost((Long.MAX_VALUE - now) / 1000)?.times(1000) ?: runCatching {
                java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - now
            }.getOrNull()
            val details = runCatching { Json.parseToJsonElement(errorBody).jsonObject["error"]
                ?.jsonObject?.get("details") as? JsonArray }.getOrNull().orEmpty().filterIsInstance<JsonObject>()
            val retryMs = details.asSequence().mapNotNull { (it["retryDelay"] as? JsonPrimitive)?.content
                ?.removeSuffix("s")?.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n >= 0 } }
                .maxOrNull()?.let { kotlin.math.ceil(it * 1000).toLong().coerceAtMost(Long.MAX_VALUE - now) }
            val daily = details.flatMap { (it["violations"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
                .any { (it["quotaId"] as? JsonPrimitive)?.content?.replace("_", "")?.contains("perday", ignoreCase = true) == true }
            val untilReset = if (daily) {
                val pacific = java.time.ZoneId.of("America/Los_Angeles")
                java.time.Instant.ofEpochMilli(now).atZone(pacific).toLocalDate().plusDays(1)
                    .atStartOfDay(pacific).toInstant().toEpochMilli() - now
            } else 0L
            return maxOf(15 * 60_000L, maxOf(delay ?: 0L, retryMs ?: 0L, untilReset)
                .takeIf { it > 0 } ?: 3_600_000L)
        }
    }

    fun reserve(discovery: Boolean = false): Boolean = synchronized(budgetLock) {
        val now = clock()
        val usage = read(usageFile)
        if (usage == null && usageFile.exists() && now - usageFile.lastModified() < 24 * 3_600_000L) return@synchronized false
        if (now < (usage?.get("pausedUntil")?.jsonPrimitive?.longOrNull ?: 0)) return@synchronized false
        val recorded = (usage?.get("attempts") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        if (recorded.any { it > now }) return@synchronized false
        val attempts = recorded.filter { now - it in 0 until 24 * 60 * 60_000L }
        if (attempts.size >= 40 || attempts.count { now - it < 60 * 60_000L } >= 12 ||
            attempts.any { now - it < 15_000 }) return@synchronized false
        val searches = (usage?.get("searches") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.longOrNull }
            .filter { now - it in 0 until 24 * 60 * 60_000L }
        if (discovery && (searches.size >= 8 || searches.count { now - it < 3_600_000 } >= 3)) return@synchronized false
        usageFile.writeText(buildJsonObject {
            put("attempts", JsonArray((attempts + now).map(::JsonPrimitive)))
            put("searches", JsonArray((if (discovery) searches + now else searches).map(::JsonPrimitive)))
        }.toString())
        true
    }
    fun pause(minimumMs: Long = 15 * 60_000L) = synchronized(budgetLock) {
        val data = read(usageFile)
        if (data == null && usageFile.exists()) return@synchronized
        val usage = data.orEmpty().toMutableMap()
        val previous = usage["pausedUntil"]?.jsonPrimitive?.longOrNull ?: 0L
        usage["pausedUntil"] = JsonPrimitive(maxOf(previous, clock() + minimumMs))
        usageFile.writeText(JsonObject(usage).toString())
    }
}

internal object GeminiGuideInput {
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val opaque = Regex("[A-Za-z0-9_=-]{48,}")
    private fun clean(value: String?, limit: Int) = opaque.replace(url.replace(value.orEmpty(), "[link omitted]"), "[identifier omitted]").take(limit)
    fun listing(competition: String?, programme: EpgProgram): JsonObject = buildJsonObject {
        put("title", clean(programme.title, 200))
        programme.description?.takeIf(String::isNotBlank)?.let { put("description", clean(it, 500)) }
        competition?.let { put("guideCategory", clean(it, 100)) }
        programme.episode?.let { put("episode", clean(it, 80)) }
        put("airtime", java.time.Instant.ofEpochMilli(programme.startMs).toString())
    }
    fun data(event: SportsEvent, programme: EpgProgram): JsonObject = buildJsonObject {
        put("event", clean(event.title, 200))
        put("competition", clean(SportsCatalog.league(event.leagueId)?.name, 100))
        put("participants", JsonArray(event.sides.map { JsonPrimitive(clean(it.team.fullName, 100)) }))
        put("eventStartUtcMs", event.startMs)
        put("programme", clean(programme.title, 200))
        put("description", clean(programme.description, 700))
        put("programmeStartUtcMs", programme.startMs); put("programmeEndUtcMs", programme.stopMs)
    }
    fun key(data: JsonObject) = MessageDigest.getInstance("SHA-256")
        .digest(("gemini-2.5-flash-lite-guide-v1:" + data).toByteArray()).joinToString("") { "%02x".format(it) }
    fun decision(raw: String): GuideMatchDecision {
        val root = Json.parseToJsonElement(raw).jsonObject
        val candidate = root["candidates"]!!.jsonArray.first().jsonObject
        val text = candidate["content"]!!.jsonObject["parts"]!!.jsonArray
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
        return when (Json.parseToJsonElement(text).jsonObject["decision"]?.jsonPrimitive?.content) {
            "match" -> GuideMatchDecision.MATCH
            "different" -> GuideMatchDecision.DIFFERENT
            "uncertain" -> GuideMatchDecision.UNCERTAIN
            else -> error("Invalid guide decision")
        }
    }
}
