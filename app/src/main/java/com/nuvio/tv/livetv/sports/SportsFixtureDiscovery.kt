package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Gemini locates a schedule. Only evidence found again on the publisher's own page can admit a fixture. */
internal object SportsFixtureDiscovery {
    private val authorities = mapOf(
        Sport.SNOOKER to setOf("wst.tv", "wpbsa.com", "womenssnooker.com"),
        Sport.DARTS to setOf("pdc.tv", "dartswdf.com"),
        Sport.TENNIS to setOf("atptour.com", "wtatennis.com", "itftennis.com"),
        Sport.CYCLING to setOf("uci.org", "letour.fr"),
        Sport.GOLF to setOf("pgatour.com", "lpga.com", "europeantour.com"),
        Sport.RACING to setOf("fia.com", "formula1.com", "motogp.com"),
        Sport.ATHLETICS to setOf("worldathletics.org"),
        Sport.SWIMMING to setOf("worldaquatics.com"),
        Sport.AMERICAN_FOOTBALL to setOf("nfl.com", "ncaa.com"),
        Sport.BASKETBALL to setOf("nba.com", "wnba.com", "fiba.basketball"),
        Sport.BASEBALL to setOf("mlb.com", "wbsc.org"),
        Sport.HOCKEY to setOf("nhl.com", "iihf.com"),
        Sport.FOOTBALL to setOf("fifa.com", "uefa.com"),
        Sport.RUGBY to setOf("world.rugby", "sixnationsrugby.com"),
        Sport.RUGBY_LEAGUE to setOf("nrl.com", "superleague.co.uk"),
        Sport.AUSSIE_RULES to setOf("afl.com.au"),
        Sport.NETBALL to setOf("netball.sport"),
        Sport.GAELIC to setOf("gaa.ie"),
        Sport.FIGHTING to setOf("ufc.com", "worldboxing.org"),
        Sport.WRESTLING to setOf("wwe.com", "allelitewrestling.com"),
        Sport.VOLLEYBALL to setOf("volleyballworld.com", "fivb.com"),
        Sport.LACROSSE to setOf("worldlacrosse.sport"),
        Sport.HORSE_RACING to setOf("britishhorseracing.com", "racingaustralia.horse"),
        Sport.GYMNASTICS to setOf("gymnastics.sport"),
        Sport.CRICKET to setOf("icc-cricket.com", "cricket.com.au"),
        Sport.OTHER to setOf("fis-ski.com", "worldcurling.org", "isu.org", "biathlonworld.com")
    )
    // National broadcasters also publish dated competition schedules across disciplines.
    private val broadcasters = setOf("bbc.co.uk", "bbc.com", "abc.net.au", "sbs.com.au", "nbcsports.com",
        "cbc.ca", "rte.ie", "france.tv", "olympics.com")
    fun publishers(sport: Sport) = authorities[sport].orEmpty() + broadcasters
    private val versus = Regex("""\s+(?:v\.?|vs\.?|versus|at|@)\s+""", RegexOption.IGNORE_CASE)
    fun pairing(title: String) = versus.split(title).takeIf { sides -> sides.size == 2 && sides.all { names(it).isNotEmpty() } }
        ?.map { names(it).sorted().joinToString(" ") }?.sorted()?.joinToString("|")

    private val oldYear = Regex("""\b(?:19|20)\d{2}\b""")
    private val dayNumber = Regex("""\bday\s+(\d+)\b""", RegexOption.IGNORE_CASE)
    private val nonFixture = Regex("""\b(?:recorded|archive|documentary|preview|studio|postponed|cancelled|canceled|rescheduled|rerun|delayed)\b""")
    private val generic = "live the from coverage sport sports snooker darts tennis golf racing cricket swimming cycling skiing alpine curling day round session final finals semifinal quarterfinal championship championships tournament world open tour match stage at versus".split(' ').toSet()
    private fun tokens(text: String) = SportsCatalog.norm(text).trim().split(' ')
    private fun names(text: String) = tokens(text).filter { it.length >= 3 && it !in generic && !it.all(Char::isDigit) }.toSet()
    private fun editorial(text: String): Boolean {
        val n = SportsCatalog.norm(text)
        return nonFixture.containsMatchIn(n) || SportsCatalog.NOT_LIVE_WORDS.any { SportsCatalog.hasWord(n, it) }
    }

    fun eligible(p: EpgProgram): Boolean {
        val year = Instant.ofEpochMilli(p.startMs).atZone(ZoneOffset.UTC).year
        val text = p.title + " " + p.description.orEmpty() + " " + p.category.orEmpty()
        return p.stopMs > p.startMs && p.stopMs - p.startMs <= 12 * 3_600_000L &&
            (p.year == null || p.year == year) && oldYear.findAll(text).all { it.value.toInt() == year } &&
            !editorial(text) && names(p.title).isNotEmpty()
    }

    fun authorizedUrl(raw: String, sport: Sport): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath.length > 1 && publishers(sport).any { url.host == it || url.host == "www." + it }
    }

    data class Claim(val url: String, val competition: String, val start: OffsetDateTime, val zone: String,
                     val edition: String, val slot: String, val opening: String, val zoneEvidence: String)
    data class Proof(val url: String, val competition: String, val startMs: Long)

    fun claim(raw: String, sport: Sport): Claim? {
        val candidate = Json.parseToJsonElement(raw).jsonObject["candidates"]?.jsonArray?.singleOrNull()?.jsonObject ?: return null
        if (candidate["finishReason"]?.jsonPrimitive?.content != "STOP") return null
        val chunks = candidate["groundingMetadata"]?.jsonObject?.get("groundingChunks")?.jsonArray ?: return null
        val answer = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .filterNot { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull == true }
            .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val data = Json.parseToJsonElement(answer).jsonObject
        fun field(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        if (field("decision") != "scheduled") return null
        val url = field("url")
        if (!authorizedUrl(url, sport)) return null
        val host = url.toHttpUrlOrNull()!!.host.removePrefix("www.")
        if (chunks.none { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject
            val uri = web?.get("uri")?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
            uri?.isHttps == true && (uri.host.removePrefix("www.") == host ||
                (uri.host == "vertexaisearch.cloud.google.com" &&
                    web["title"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT)?.removePrefix("www.") == host))
        }) return null
        return Claim(url, field("competition"), OffsetDateTime.parse(field("start")), field("zone"),
            field("edition"), field("slot"), field("opening"), field("zoneEvidence"))
    }

    private val spaces = Regex("""\s+""")
    private fun clean(s: String) = s.lowercase(Locale.ENGLISH).replace('\u00a0', ' ').replace(spaces, " ").trim()
    private val dateText = Regex("""\b(?:(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]{3,9})|([a-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?)\b""")
    private fun dates(s: String): List<Pair<Int, Int>> {
        // Look ahead at each token so a weekday preceding a month cannot consume the actual date.
        val words = clean(s).split(' ')
        return words.zipWithNext().mapNotNull { (a,b) ->
            val m = dateText.find("$a $b") ?: return@mapNotNull null
            val month = (m.groupValues[2] + m.groupValues[3])
            val number = java.time.Month.values().firstOrNull { month == it.name.lowercase(Locale.ENGLISH) ||
                month == it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(Locale.ENGLISH) } ?: return@mapNotNull null
            number.value to (m.groupValues[1] + m.groupValues[4]).toInt()
        }
    }

    private val clock = Regex("""(?<![\d:])([01]?\d|2[0-3])(?::([0-5]\d)\s*(am|pm)?|\s*(am|pm))\b""")
    private fun zoneSupported(claim: Claim, page: String): Boolean {
        val zone = runCatching { ZoneId.of(claim.zone) }.getOrNull() ?: return false
        val local = claim.start.toLocalDateTime()
        if (zone.rules.getValidOffsets(local).singleOrNull() != claim.start.offset) return false
        val marker = clean(claim.zone)
        val venue = clean(claim.zoneEvidence)
        return when {
            venue.length in 8..250 && venue in page && clean(claim.competition) in venue && '/' in marker &&
                SportsCatalog.hasWord(SportsCatalog.norm(venue), marker.substringAfterLast('/').replace('_', ' ')) -> true
            marker == "utc" || marker == "gmt" -> Regex("""\b(?:utc|gmt)\b""").containsMatchIn(clean(claim.slot))
            else -> marker in clean(claim.slot)
        }
    }

    fun verify(p: EpgProgram, sport: Sport, c: Claim, html: String): Proof? {
        if (!eligible(p) || !authorizedUrl(c.url, sport) || c.competition.length !in 3..100 ||
            c.edition.length !in 6..200 || c.slot.length !in 8..300 || c.opening.length > 220) return null
        val start = c.start.toInstant().toEpochMilli()
        if (abs(start - p.startMs) > 15 * 60_000L || start >= p.stopMs) return null
        val doc = Jsoup.parse(html)
        // WST sends the article body as inert Nuxt text-block data and hydrates it on the TV browser.
        // Read just that publisher field. Never evaluate the script or include unrelated page state.
        val bodyText = if (c.url.toHttpUrlOrNull()?.host?.removePrefix("www.") == "wst.tv") {
            doc.select("script").asSequence().map { it.data() }.filter { it.startsWith("window.__NUXT__=") }
                .flatMap { it.split("widgetType:\"TextBlockWidget\"").asSequence().drop(1) }
                .mapNotNull { block ->
                    val raw = block.substringBefore("},rowID:").substringAfter("content:'", "")
                    if (!raw.startsWith("<")) return@mapNotNull null
                    val value = StringBuilder()
                    var escaped = false
                    for (ch in raw) {
                        if (escaped) { value.append(if (ch == 'n' || ch == 'r') ' ' else ch); escaped = false }
                        else if (ch == '\\') escaped = true
                        else if (ch == '\'') break
                        else value.append(ch)
                    }
                    Jsoup.parseBodyFragment(value.toString()).text()
                }.joinToString(" ")
        } else ""
        doc.select("script,style,nav,footer,aside,header nav").remove()
        val article = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val page = clean(doc.title() + " " + article.text() + " " + bodyText)
        val edition = clean(c.edition)
        val slot = clean(c.slot)
        val heading = clean(doc.title() + " " + article.select("h1").text())
        if (Regex("""\b(?:postponed|cancelled|canceled|rescheduled|abandoned)\b""").containsMatchIn(page)) return null
        if (edition !in heading || slot !in page || editorial(slot) || editorial(edition) ||
            clean(c.competition) !in edition || oldYear.findAll(edition).map { it.value }.toSet() != setOf(c.start.year.toString()) ||
            !zoneSupported(c, page)) return null
        if (c.url.toHttpUrlOrNull()?.host?.removePrefix("www.") in broadcasters &&
            !SportsCatalog.hasWord(SportsCatalog.norm(slot), "live")) return null
        val date = c.start.toLocalDate()
        if (dates(slot).distinct() != listOf(date.monthValue to date.dayOfMonth) ||
            oldYear.findAll(slot).any { it.value.toInt() != date.year }) return null
        val time = clock.findAll(slot).lastOrNull() ?: return null
        // The end of a time range is not another scheduled start.
        if (Regex("""(?:\b(?:to|until|ends? at)|[-–—])\s*$""").containsMatchIn(slot.take(time.range.first).trimEnd())) return null
        val meridiem = time.groupValues[3] + time.groupValues[4]
        val h = time.groupValues[1].toInt()
        val hour = if (meridiem.isEmpty()) h else h % 12 + if (meridiem == "pm") 12 else 0
        if (hour != c.start.hour || (time.groupValues[2].toIntOrNull() ?: 0) != c.start.minute) return null
        val guideNames = names(p.title)
        val row = slot.substring(time.range.last + 1)
        val details = tokens(c.competition + " " + if (clock.findAll(slot).count() == 1) slot else row)
        if (!guideNames.all { it in details }) return null
        val matchedPair = pairing(p.title) != null && guideNames.all { it in details }
        if (names(c.competition).intersect(guideNames).isEmpty() && !matchedPair) return null
        // Named phases and participants cannot be borrowed from a different session in the same draw.
        val phases = Regex("""\b(?:quarterfinal|semifinal|final|qualifying|practice|round \d+|session \d+)\b""")
        val scheduleText = SportsCatalog.norm(slot + " " + edition)
        if (phases.findAll(SportsCatalog.norm(p.title)).any { !SportsCatalog.hasWord(scheduleText, it.value) }) return null
        // Providers often put the competitors only in the description under a generic session title.
        // A claim for another match on the same draw cannot establish that airing.
        val description = SportsCatalog.norm(p.description.orEmpty())
        val opponentMarker = Regex("""\s+(?:v|vs|versus)\s+""").find(description)
        if (opponentMarker != null) {
            val left = description.take(opponentMarker.range.first).trim().substringAfterLast(' ')
            val right = description.drop(opponentMarker.range.last + 1).trim().substringBefore(' ')
            if (left.length < 2 || right.length < 2 || left !in details || right !in details) return null
        }
        if (" evening session " in description && hour < 17 || " morning session " in description && hour >= 12 ||
            " afternoon session " in description && hour !in 12..17) return null
        for (m in dayNumber.findAll(p.title + " " + p.description.orEmpty())) {
            val day = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
            val opening = clean(c.opening)
            val expected = date.minusDays(day - 1L)
            if (opening.isEmpty() || opening !in page || !Regex("""\b(?:from|begins?|starts?|opens?)\b""").containsMatchIn(opening) ||
                dates(opening).firstOrNull() != (expected.monthValue to expected.dayOfMonth) ||
                oldYear.findAll(opening).any { it.value.toInt() != expected.year }) return null
        }
        return Proof(c.url, c.competition, start)
    }
}
