package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/** Verify a search-grounded claim for an existing airing. The model chooses the source. */
internal object SportsFixtureDiscovery {
    data class Proof(val url: String, val competition: String, val startMs: Long, val evidence: String)

    private val versus = Regex("""\s+(?:v\.?|vs\.?|versus|at|@)\s+""", RegexOption.IGNORE_CASE)
    private val year = Regex("""\b(?:19|20)\d{2}\b""")
    private val instant = Regex("""\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})\b""")
    private val qualifier = Regex("""\b(?:day|round|session|stage)\s+\d+\b|\b(?:final|semifinal|quarterfinal|qualifying|practice|boys|girls|varsity)\b""")
    private val nonFixture = Regex("""\b(?:recorded|archive|documentary|preview|studio|postponed|cancelled|canceled|rescheduled|abandoned|rerun|delayed)\b""")
    private val uncertain = Regex("""\b(?:unconfirmed|uncertain|possibly|might|unverified|not confirmed|could not verify)\b""")
    private val generic = ("live the from coverage sport sports high school boys girls varsity basketball football " +
        "snooker darts tennis golf racing cricket swimming cycling skiing alpine curling day round session " +
        "final finals semifinal quarterfinal championship championships tournament world open tour match stage at versus").split(' ').toSet()
    private fun norm(text: String) = SportsCatalog.norm(text).trim()
    private fun names(text: String) = norm(text).split(' ').filter { it.length >= 3 && it !in generic && !it.all(Char::isDigit) }.toSet()
    private fun editorial(text: String): Boolean {
        val n = SportsCatalog.norm(text)
        return nonFixture.containsMatchIn(n) || SportsCatalog.NOT_LIVE_WORDS.any { SportsCatalog.hasWord(n, it) }
    }

    fun pairing(title: String) = versus.split(title.substringAfterLast(':')).takeIf { sides ->
        sides.size == 2 && sides.all { names(it).isNotEmpty() }
    }?.map { names(it).sorted().joinToString(" ") }?.sorted()?.joinToString("|")

    fun eligible(p: EpgProgram): Boolean {
        val listingYear = Instant.ofEpochMilli(p.startMs).atZone(ZoneOffset.UTC).year
        val text = p.title + " " + p.description.orEmpty() + " " + p.category.orEmpty()
        return p.stopMs > p.startMs && p.stopMs - p.startMs <= 12 * 3_600_000L &&
            (p.year == null || p.year == listingYear) && year.findAll(text).all { it.value.toInt() == listingYear } &&
            !editorial(text) && (names(p.title).isNotEmpty() || pairing(p.description.orEmpty()) != null)
    }

    fun isSourceUrl(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.host.contains('.') && url.host.last().isLetter() && !url.host.endsWith(".localhost") &&
            !url.host.endsWith(".local") && !url.host.endsWith(".internal")
    }

    fun verify(p: EpgProgram, sport: Sport, raw: String): Proof? {
        if (!eligible(p)) return null
        val candidate = Json.parseToJsonElement(raw).jsonObject["candidates"]?.jsonArray?.singleOrNull()?.jsonObject ?: return null
        if (candidate["finishReason"]?.jsonPrimitive?.content != "STOP") return null
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return null
        val part = parts.withIndex().singleOrNull { (_, value) -> value.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true &&
            value.jsonObject["text"] is JsonPrimitive } ?: return null
        val answer = part.value.jsonObject.getValue("text").jsonPrimitive.content
        val report = runCatching { Json.parseToJsonElement(answer.trim().removePrefix("```json")
            .removePrefix("```").removeSuffix("```").trim()) as? JsonObject }.getOrNull() ?: return null
        fun field(key: String) = (report[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (field("fixtureMatch") != "exact" || field("airingType") != "live_at_fixture_time" ||
            field("sport") != sport.name) return null
        val name = field("eventName") ?: return null
        val finding = field("evidence") ?: return null
        if (name.length !in 3..160 || finding.length !in 12..700 || editorial(finding) ||
            uncertain.containsMatchIn(norm(finding)) || !SportsCatalog.hasWord(SportsCatalog.norm(finding), norm(name))) return null

        val stamp = instant.findAll(finding).singleOrNull()?.value ?: return null
        if (stamp != field("eventStart")) return null
        val start = runCatching { OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }.getOrNull() ?: return null
        if (abs(start - p.startMs) > 15 * 60_000L || start >= p.stopMs || !matchesListing(p, sport, finding)) return null

        val grounding = candidate["groundingMetadata"]?.jsonObject ?: return null
        val chunks = grounding["groundingChunks"] as? JsonArray ?: return null
        val supports = grounding["groundingSupports"] as? JsonArray ?: return null
        // generateContent reports UTF-8 byte offsets relative to the indicated part, including with non-ASCII team names.
        val bytes = answer.toByteArray(Charsets.UTF_8)
        val fact = finding.trimEnd('.', '!', ' ')
        for (entry in supports) {
            val support = entry as? JsonObject ?: continue
            val segment = support["segment"] as? JsonObject ?: continue
            if ((segment["partIndex"]?.jsonPrimitive?.intOrNull ?: 0) != part.index) continue
            val from = segment["startIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val to = segment["endIndex"]?.jsonPrimitive?.intOrNull ?: continue
            if (from !in 0 until bytes.size || to !in (from + 1)..bytes.size) continue
            val cited = String(bytes, from, to - from, Charsets.UTF_8)
            val encodedFact = JsonPrimitive(fact).toString().removeSurrounding("\"")
            if (!cited.contains(encodedFact) || segment["text"]?.jsonPrimitive?.contentOrNull?.let { it != cited } == true) continue
            for (index in (support["groundingChunkIndices"] as? JsonArray).orEmpty()) {
                val chunk = index.jsonPrimitive.intOrNull?.let(chunks::getOrNull) as? JsonObject ?: continue
                val url = (chunk["web"] as? JsonObject)?.get("uri")?.jsonPrimitive?.contentOrNull ?: continue
                if (isSourceUrl(url)) return Proof(url, name, start, finding)
            }
        }
        return null
    }

    private fun matchesListing(p: EpgProgram, sport: Sport, finding: String): Boolean {
        val fact = SportsCatalog.norm(finding)
        val factNode = SportsTaxonomy.classifyProgramme(finding, null)
        val factSport = factNode?.let { SportsTaxonomy.sport(it.sportId) }
        val guideNode = SportsTaxonomy.classifyProgramme(p.title, p.description)
        if (sport == Sport.OTHER && guideNode != null && guideNode.sportId != factNode?.sportId) return false
        if (factSport != sport && (factSport != null || !SportsCatalog.hasWord(fact, norm(sport.label)))) return false
        val guide = norm(p.title + " " + p.description.orEmpty())
        if (qualifier.findAll(guide).any { !SportsCatalog.hasWord(fact, it.value) }) return false
        if ((SportsCatalog.hasWord(" $guide ", "high school") || guideNode?.id == "basketball.high-school-basketball") &&
            !SportsCatalog.hasWord(fact, "high school")) return false

        val opponents = pairing(p.title) ?: p.description.orEmpty().split('.', '!', '?').firstNotNullOfOrNull(::pairing)
        if (opponents != null) return opponents.split('|', ' ').all { SportsCatalog.hasWord(fact, it) }
        val identifiers = names(p.title)
        return identifiers.isNotEmpty() && identifiers.all { SportsCatalog.hasWord(fact, it) }
    }
}
