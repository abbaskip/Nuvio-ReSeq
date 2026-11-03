package com.nuvio.tv.livetv.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Provider-supplied defaults also work before a league's fixtures have been loaded. */
internal object SportsLeagueLogos {
    private val defaults: Map<String, String> by lazy {
        val raw = requireNotNull(javaClass.getResourceAsStream("/sports-league-logos.json"))
            .bufferedReader().use { it.readText() }
        Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    fun logo(league: League): String? {
        val sources = SportsCompetitions.sources(league.id)
        return SportsFeeds.leagueLogos[league.id] ?: sources.firstNotNullOfOrNull { SportsFeeds.leagueLogos[it.id] }
            ?: defaults[league.id] ?: sources.firstNotNullOfOrNull { defaults[it.id] }
    }
}
