package com.nuvio.tv.livetv.sports

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Other screens can read the schedule Sports Hub already loaded without starting its workers. */
@Singleton
class SportsFixtureCache @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile internal var known: StateFlow<List<SportsEvent>>? = null
    @Volatile internal var matches: StateFlow<Map<String, List<ChannelMatch>>>? = null
    private val selected = java.util.concurrent.atomic.AtomicReference<SportsEvent?>()
    internal fun select(event: SportsEvent) { selected.set(event) }
    internal fun takeSelected(): SportsEvent? = selected.getAndSet(null)

    internal data class Fixtures(val events: List<SportsEvent>, val savedLeagues: Map<String, League> = emptyMap(),
        val channels: Map<String, List<String>> = emptyMap())

    internal suspend fun read(now: Long): Fixtures {
        known?.value?.takeIf { it.isNotEmpty() }?.let { events ->
            return Fixtures(events, channels = matches?.value.orEmpty().mapValues { (_, hits) ->
                hits.filter { it.sure && !it.catchup && (it.program == null || SportsEventListings.liveCompetition(it.program)) }.map { it.channel.key }
            })
        }
        val page = SportsPageSnapshot.read(File(context.cacheDir, "sports/page-snapshot.json"), now)
            ?: return Fixtures(emptyList())
        val leagues = page.items.mapNotNull { it.event.league }.distinctBy { it.id }.associate { it.id to it.league() }
        return Fixtures(page.events, leagues, page.items.associate { it.event.id to
            it.matches.filter { match -> match.score >= 80 && !match.catchup && (match.program == null || SportsEventListings.liveCompetition(match.program.program())) }.map { match -> match.key } })
    }
}
