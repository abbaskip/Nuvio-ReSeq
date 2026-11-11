package com.nuvio.tv.livetv.sports

internal data class CricketScoreText(val runs: String, val overs: String?)

/** Keep the innings total and overs on separate lines instead of squeezing both into a score cell. */
internal fun cricketScoreText(value: String?): CricketScoreText {
    val score = value?.trim().orEmpty()
    if (score.isEmpty()) return CricketScoreText("Yet to bat", null)
    val overs = Regex("\\s*\\(([^)]*\\bov(?:ers)?[^)]*)\\)\\s*$", RegexOption.IGNORE_CASE).find(score)
    return CricketScoreText(score.substring(0, overs?.range?.first ?: score.length).trim(),
        overs?.groupValues?.get(1)?.trim())
}

internal fun cricketInningsScores(side: Side): List<CricketScoreText> {
    // Older cached feeds stored ESPN's numeric innings ordinal here, rather than the runs.
    val innings = side.periods.filter { Regex("\\d+\\s*/\\s*\\d+").containsMatchIn(it) }
        .ifEmpty { side.score?.takeIf { it.isNotBlank() }?.split(Regex("\\s*&\\s*")).orEmpty() }
    return innings.map(::cricketScoreText)
}
