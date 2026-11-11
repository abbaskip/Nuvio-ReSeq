package com.nuvio.tv.livetv.sports

/** A break in an unfinished match, bounded even when a provider omits its end date. */
internal fun SportsEvent.isPausedOngoingCricket(now: Long): Boolean =
    source != EventSource.GUIDE && state == GameState.OFF &&
        SportsCatalog.league(leagueId)?.sport == Sport.CRICKET &&
        hasCricketPauseStatus() &&
        now >= startMs && now < cricketEndMs()

internal fun SportsEvent.hasCricketPauseStatus(): Boolean =
    SportsCatalog.league(leagueId)?.sport == Sport.CRICKET &&
        status in setOf("Stumps", "Awaiting update", "In progress", "Rain Delay", "RainDelay", "Delayed", "Suspended", "BadLight", "Bad Light")

internal fun SportsEvent.cricketEndMs(): Long = endMs ?: (startMs + 5 * 86_400_000L)
