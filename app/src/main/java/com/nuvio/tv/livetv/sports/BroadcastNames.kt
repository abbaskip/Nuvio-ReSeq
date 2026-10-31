package com.nuvio.tv.livetv.sports

/** Broadcaster names are not channel IDs: retain numbers, plus services and country distinctions. */
internal object BroadcastNames {
    private val prefix = Regex("^(AU|AUS|UK|GB|US|USA|CA|NZ|IE)\\s*[:|]\\s*", RegexOption.IGNORE_CASE)
    private val quality = Regex("\\b(?:uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|backup)\\b|\\b(?:1080|720|2160)p?\\b")
    private val spacing = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")
    private val spaces = Regex(" +")
    private val sportsNet = Regex("\\bsportsnet\\b")
    private val btSport = Regex("\\bbt sport(?:s)?\\b")
    private val tntNumber = Regex("^tnt (\\d+)$")
    private val regionalSuffixes = setOf("east", "west", "north", "south", "alternate", "alt")
    private val canonicalNames = com.nuvio.tv.livetv.data.boundedCache<String, String>(8192)
    private val known = listOf("sky sports main event", "sky sports premier league", "sky sports football",
        "sky sports cricket", "sky sports action", "sky sports arena", "sky sports f1", "sky sports golf",
        "tnt sports", "fox sports", "bein sports", "cbs sports network", "nfl network", "mlb network").map { it.replace(" ", "") to it }
    fun country(channel: String): String? = prefix.find(channel.trim())?.groupValues?.get(1)?.uppercase()?.let {
        when (it) { "AUS" -> "AU"; "GB" -> "UK"; "USA" -> "US"; else -> it }
    }
    fun countryCode(country: String?): String? = when (SportsTaxonomy.normalize(country.orEmpty())) {
        "australia", "au" -> "AU"; "new zealand", "nz" -> "NZ"
        "united kingdom", "england", "scotland", "wales", "uk", "gb" -> "UK"
        "united states", "united states of america", "usa", "us" -> "US"
        "ireland", "republic of ireland", "ie" -> "IE"; "canada", "ca" -> "CA"; else -> null
    }
    fun canonical(value: String): String = canonicalNames[value] ?: canonicalUncached(value).also { canonicalNames[value] = it }

    private fun canonicalUncached(value: String): String {
        var text = SportsTaxonomy.normalize(prefix.replace(value.trim(), "").replace("+", " plus "))
        text = quality.replace(text, " ").replace(spaces, " ").trim()
        text = spacing.replace(text, " ")
        for ((compact, name) in known) text = text.replace(compact, name)
        text = text.replace(sportsNet, "sports net")
        text = text.replace(btSport, "tnt sports")
        text = text.replace(tntNumber, "tnt sports $1")
        text = quality.replace(text, " ").replace(spaces, " ").trim()
        return when (text) {
            "bbc 1", "bbc one" -> "bbc one"
            "bbc 2", "bbc two" -> "bbc two"
            "itv 1", "itv one" -> "itv 1"
            "ch 4", "channel four", "channel 4" -> "channel 4"
            "ch 5", "channel five", "channel 5" -> "channel 5"
            "7", "seven", "seven network", "7 network", "channel 7" -> "seven"
            "9", "nine", "nine network", "network 9", "channel 9" -> "nine"
            "10", "ten", "network 10", "channel 10" -> "ten"
            else -> text
        }
    }
    fun matches(network: String, channel: String, participants: List<String> = emptyList()): Boolean {
        val a = canonical(network); val b = canonical(channel)
        if (a.isEmpty()) return false
        if (a == b || (a.length >= 3 && b.endsWith(" $a"))) return true
        if (!b.startsWith("$a ")) return false
        val suffix = b.removePrefix("$a ")
        return suffix in regionalSuffixes ||
            participants.any { canonical(it) == suffix }
    }
    fun compatible(coverage: SportsBroadcast, channel: String): Boolean {
        val reported = countryCode(coverage.country); val actual = country(channel)
        return reported == null || actual == null || reported == actual
    }
}
