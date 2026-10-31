package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

internal enum class GuideMatchDecision { MATCH, DIFFERENT, UNCERTAIN }

/** A content-keyed decision cache and a persistent device-wide request budget, not another guide. */
internal class GeminiGuideCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { dir.mkdirs() }
    private val usageFile = File(dir, "gemini-guide-usage.json")
    private fun read(file: File) = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
    private fun file(profile: Int) = File(dir, "gemini-guide-$profile.json")

    @Synchronized fun get(profile: Int, key: String): GuideMatchDecision? {
        val entry = (read(file(profile))?.get(key) as? JsonObject) ?: return null
        val at = entry["at"]?.jsonPrimitive?.longOrNull ?: return null
        if (clock() - at !in 0 until 3 * 24 * 60 * 60_000L) return null
        return entry["decision"]?.jsonPrimitive?.contentOrNull?.let { runCatching { GuideMatchDecision.valueOf(it) }.getOrNull() }
    }
    @Synchronized fun put(profile: Int, key: String, decision: GuideMatchDecision) {
        val now = clock()
        val entries = read(file(profile)).orEmpty().filterValues {
            val at = it.jsonObject["at"]?.jsonPrimitive?.longOrNull ?: 0
            now - at in 0 until 3 * 24 * 60 * 60_000L
        }.toMutableMap()
        entries[key] = buildJsonObject { put("at", now); put("decision", decision.name) }
        // Only hashes, timestamps and decisions are retained; no raw EPG descriptions or API keys.
        val bounded = entries.entries.sortedByDescending { it.value.jsonObject["at"]!!.jsonPrimitive.long }.take(500)
        file(profile).writeText(JsonObject(bounded.associate { it.key to it.value }).toString())
    }
    @Synchronized fun reserve(): Boolean {
        val now = clock()
        val usage = read(usageFile)
        if (now < (usage?.get("pausedUntil")?.jsonPrimitive?.longOrNull ?: 0)) return false
        val recorded = (usage?.get("attempts") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        if (recorded.any { it > now }) return false
        val attempts = recorded.filter { now - it in 0 until 24 * 60 * 60_000L }
        if (attempts.size >= 40 || attempts.count { now - it < 60 * 60_000L } >= 12 ||
            attempts.any { now - it < 15_000 }) return false
        usageFile.writeText(buildJsonObject { put("attempts", JsonArray((attempts + now).map(::JsonPrimitive))) }.toString())
        return true
    }
    @Synchronized fun pause() {
        val usage = read(usageFile).orEmpty().toMutableMap()
        usage["pausedUntil"] = JsonPrimitive(clock() + 15 * 60_000L)
        usageFile.writeText(JsonObject(usage).toString())
    }
}

internal object GeminiGuideInput {
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val opaque = Regex("[A-Za-z0-9_=-]{48,}")
    private fun clean(value: String?, limit: Int) = opaque.replace(url.replace(value.orEmpty(), "[link omitted]"), "[identifier omitted]").take(limit)
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
