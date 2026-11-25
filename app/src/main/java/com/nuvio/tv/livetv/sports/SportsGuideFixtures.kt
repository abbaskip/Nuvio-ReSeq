package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import kotlin.math.abs

internal object SportsGuideFixtures {
    data class Listing(val league: String, val programme: EpgProgram)
    data class Observation(val proof: SportsFixtureDiscovery.Proof?, val checkedAt: Long)
    const val VALID_MS = 3 * 3_600_000L

    const val BATCH_SIZE = 4

    private data class Candidate(val airing: SportsEvent, val listing: Listing, val priority: Int)

    // Preserve the taxonomy sport for competitions which share the catch-all Sport.OTHER.
    private fun sportGroup(leagueId: String): String? {
        if (leagueId == InternationalCricket.LEAGUE_ID) return Sport.CRICKET.name
        val league = SportsCatalog.league(leagueId) ?: return null
        if (league.sport != Sport.OTHER) return league.sport.name
        return SportsTaxonomy.byId[leagueId.removePrefix("taxonomy/")]?.sportId
            ?: SportsTaxonomy.classify(league.name, null)?.sportId
    }

    fun candidates(airings: Sequence<SportsEvent>, fixtures: List<SportsEvent>, channels: Set<String>,
                   matched: Set<Pair<String, EpgProgram?>>, observed: Set<Listing>, now: Long, focused: String?,
                   follows: SportsFollows): Sequence<Listing> {
        val hour = 3_600_000L
        val followedGroups = (follows.leagues + follows.pins + follows.teams.map { it.leagueId })
            .mapNotNull(::sportGroup).toSet()
        val words = follows.words.map { SportsCatalog.norm(it).trim() }.filter { it.isNotEmpty() }
        val teamNames = follows.teams.groupBy { sportGroup(it.leagueId) }.mapValues { (_, teams) ->
            teams.map { SportsCatalog.norm(it.name).trim() }.filter { it.isNotEmpty() }
        }
        return airings.filter { it.source == EventSource.GUIDE }.flatMap { airing ->
            val group = sportGroup(airing.leagueId)
            val directlyFollowed = isMine(airing, follows)
            airing.guideHits.asSequence().filter { it.first in channels && it !in matched }.mapNotNull { (_, p) ->
                val listing = Listing(airing.leagueId, p)
                if (listing in observed || p.stopMs <= now || p.startMs !in (now - 3 * hour)..(now + 24 * hour) ||
                    !SportsFixtureDiscovery.eligible(p)) return@mapNotNull null
                val text = SportsCatalog.norm(p.title + " " + p.description.orEmpty())
                val priority = when {
                    directlyFollowed || words.any { SportsCatalog.hasWord(text, it) } ||
                        (group != null && teamNames[group].orEmpty().any { SportsCatalog.hasWord(text, it) }) -> 0
                    group in followedGroups -> 1
                    else -> 2
                }
                Candidate(airing, listing, priority)
            }
        }.sortedWith(compareBy({ it.priority }, { it.airing.id != focused }, { abs(it.listing.programme.startMs - now) },
            { it.listing.league }, { it.listing.programme.title }))
            .distinctBy { it.listing.copy(programme = it.listing.programme.copy(icon = null, people = emptyList())) }
            .filter { !covered(it.airing, fixtures, it.listing.programme.startMs) }
            .map { it.listing }
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
                    note = "Verified schedule", about = proof.evidence, scheduleUrl = proof.url
                ) else previous.copy(guideHits = (previous.guideHits + hit).distinct(), endMs = maxOf(previous.endMs!!, p.stopMs))
            }
        }
        return known + sessions.values
    }
}
