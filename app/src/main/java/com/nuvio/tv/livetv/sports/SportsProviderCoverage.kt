package com.nuvio.tv.livetv.sports

import kotlinx.serialization.json.*

/** Shared with the catalogue producer so provider discovery cannot silently omit a sport. */
internal object SportsProviderCoverage {
    private val data by lazy {
        requireNotNull(javaClass.getResourceAsStream("/sports-provider-coverage.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    val discoverySports: Map<Sport, List<String>> by lazy {
        data.getValue("sports").jsonArray.map { it.jsonObject }.groupBy(
            { Sport.valueOf(it.getValue("category").jsonPrimitive.content) },
            { it.getValue("name").jsonPrimitive.content })
    }
    val leagues: List<League> by lazy {
        data.getValue("leagues").jsonArray.map { item ->
            val o = item.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            League("tsdb/" + o.getValue("id").jsonPrimitive.content, Sport.RACING, name, name,
                kind = LeagueKind.EVENT,
                guideWords = listOf(name) + o.getValue("aliases").jsonArray.map { it.jsonPrimitive.content })
        }
    }
}
