package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import kotlin.math.abs

internal object SportsGuideFixtures {
    data class Listing(val league: String, val programme: EpgProgram)
    data class Observation(val proof: SportsFixtureDiscovery.Proof?, val checkedAt: Long)
    const val VALID_MS = 3 * 3_600_000L

    fun next(airings: Sequence<SportsEvent>, fixtures: List<SportsEvent>, channels: Set<String>,
             matched: Set<Pair<String, EpgProgram?>>, observed: Set<Listing>, now: Long, focused: String?): Pair<SportsEvent, EpgProgram>? {
        val hour = 3_600_000L
        return airings.filter { it.source == EventSource.GUIDE }
            .flatMap { airing -> airing.guideHits.asSequence().filter { it.first in channels && it !in matched }
                .map { airing to it.second } }
            .filter { (airing, p) -> p.stopMs > now && p.startMs in (now - 3 * hour)..(now + 24 * hour) &&
                Listing(airing.leagueId, p) !in observed }
            .distinctBy { (airing, p) -> Listing(airing.leagueId, p) }
            .filter { (_, p) -> SportsFixtureDiscovery.eligible(p) }
            .sortedWith(compareBy({ it.first.id != focused }, { abs(it.second.startMs - now) }))
            .firstOrNull { (airing, p) -> !covered(airing, fixtures, p.startMs) }
    }

    /** With overlapping provider sessions, suppress ambiguous duplicates even if the provider uses different names. */
    fun covered(airing: SportsEvent, fixtures: List<SportsEvent>, start: Long = airing.startMs): Boolean {
        val sport = SportsCatalog.league(airing.leagueId)?.sport ?: return true
        return fixtures.any { fixture ->
            val league = SportsCatalog.league(fixture.leagueId)
            if (league != null && fixture.sides.size == 2 && SportsFixtureDiscovery.pairing(airing.title) != null) {
                return@any league.sport == sport && SportsEventIdentity.guideCoversFixture(airing, fixture, league)
            }
            fixture.source != EventSource.GUIDE && fixture.source != EventSource.VERIFIED_SCHEDULE &&
                (fixture.leagueId == airing.leagueId || sport != Sport.OTHER && SportsCatalog.league(fixture.leagueId)?.sport == sport) &&
                (abs(fixture.startMs - start) <= 45 * 60_000L ||
                    (!fixture.hasTime && abs(fixture.startMs - start) < 24 * 3_600_000L &&
                        SportsCatalog.norm(airing.title).contains(SportsCatalog.norm(fixture.title).trim())))
        }
    }

    fun add(known: List<SportsEvent>, observations: Map<Listing, Observation>, now: Long,
            airings: Sequence<SportsEvent> = known.asSequence(), visible: (String) -> Boolean = { true }): List<SportsEvent> {
        if (observations.isEmpty()) return known
        if (observations.values.none { it.proof != null }) return known
        val fixtures = known.filter { it.source != EventSource.GUIDE }
        val sessions = linkedMapOf<Triple<Sport, String, Long>, SportsEvent>()
        for (airing in airings) {
            if (airing.source != EventSource.GUIDE) continue
            val sport = SportsCatalog.league(airing.leagueId)?.sport ?: continue
            for (hit in airing.guideHits) {
                if (!visible(hit.first)) continue
                val p = hit.second
                val entry = observations[Listing(airing.leagueId, p)] ?: continue
                val proof = entry.proof ?: continue
                if (now - entry.checkedAt !in 0 until VALID_MS || p.stopMs <= now || covered(airing, fixtures, proof.startMs)) continue
                val pairing = SportsFixtureDiscovery.pairing(p.title).orEmpty()
                val identity = Triple(sport, SportsCatalog.norm(proof.competition) + "|" + pairing, proof.startMs)
                val previous = sessions[identity]
                sessions[identity] = if (previous == null) airing.copy(
                    id = "verified:" + SportsGuideCache.digest(listOf(sport.name, identity.second, proof.startMs.toString())),
                    source = EventSource.VERIFIED_SCHEDULE, startMs = proof.startMs, endMs = p.stopMs,
                    state = GameState.PRE, status = "Scheduled", title = p.title, guideHits = listOf(hit),
                    note = "Official schedule", scheduleUrl = proof.url
                ) else previous.copy(guideHits = (previous.guideHits + hit).distinct(), endMs = maxOf(previous.endMs!!, p.stopMs))
            }
        }
        return known + sessions.values
    }
}
