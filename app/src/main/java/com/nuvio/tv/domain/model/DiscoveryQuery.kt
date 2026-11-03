package com.nuvio.tv.domain.model

import androidx.annotation.Keep
import java.time.LocalDate

/** Provider-independent editing state. Dates refer to the first release / first air date. */
@Keep
data class DiscoveryQuery(
    val television: Boolean = false,
    val genres: Set<Int> = emptySet(),
    val matchAllGenres: Boolean = false,
    val excludedGenres: Set<Int> = emptySet(),
    val dateFrom: String = "",
    val dateTo: String = "",
    val originalLanguage: String = "",
    val minimumRating: String = "",
    val minimumVotes: String = "",
    val runtimeFrom: String = "",
    val runtimeTo: String = "",
    val seriesStatus: String = "",
    val hideWatched: Boolean = false,
    val sort: String = "popularity.desc"
) {
    val mediaType: String get() = if (television) "tv" else "movie"

    fun validationError(): DiscoveryValidationError? {
        fun validDate(value: String): Boolean = value.isBlank() ||
            (value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && runCatching { LocalDate.parse(value) }.isSuccess)
        if (!validDate(dateFrom) || !validDate(dateTo)) return DiscoveryValidationError.DATE
        if (dateFrom.isNotBlank() && dateTo.isNotBlank() && dateFrom > dateTo) return DiscoveryValidationError.DATE_ORDER
        if (minimumRating.isNotBlank() && minimumRating.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10.0 } != true) {
            return DiscoveryValidationError.RATING
        }
        if (minimumVotes.isNotBlank() && minimumVotes.toIntOrNull()?.let { it >= 0 } != true) return DiscoveryValidationError.VOTES
        if (listOf(runtimeFrom, runtimeTo).any { it.isNotBlank() && it.toIntOrNull()?.let { n -> n >= 0 } != true }) {
            return DiscoveryValidationError.RUNTIME
        }
        if (runtimeFrom.isNotBlank() && runtimeTo.isNotBlank() && runtimeFrom.toInt() > runtimeTo.toInt()) {
            return DiscoveryValidationError.RUNTIME_ORDER
        }
        if (genres.any { it <= 0 } || excludedGenres.any { it <= 0 } || genres.intersect(excludedGenres).isNotEmpty()) {
            return DiscoveryValidationError.GENRES
        }
        if (sort !in SORTS || (seriesStatus.isNotBlank() && seriesStatus !in setOf("0", "3", "4")) ||
            (originalLanguage.isNotBlank() && !originalLanguage.matches(Regex("[a-z]{2}")))) return DiscoveryValidationError.OPTIONS
        return null
    }

    fun tmdbParameters(): Map<String, String> {
        require(validationError() == null) { "Invalid discovery filters" }
        val dateKey = if (television) "first_air_date" else "primary_release_date"
        return buildMap {
            put("include_adult", "false")
            put("sort_by", when {
                sort.startsWith("release_date.") -> "$dateKey.${sort.substringAfterLast('.')}"
                else -> sort
            })
            if (genres.isNotEmpty()) put("with_genres", genres.sorted().joinToString(if (matchAllGenres) "," else "|"))
            if (excludedGenres.isNotEmpty()) put("without_genres", excludedGenres.sorted().joinToString(","))
            fun optional(key: String, value: String) { if (value.isNotBlank()) put(key, value) }
            optional("$dateKey.gte", dateFrom)
            optional("$dateKey.lte", dateTo)
            optional("with_original_language", originalLanguage)
            optional("vote_average.gte", minimumRating)
            optional("vote_count.gte", minimumVotes)
            optional("with_runtime.gte", runtimeFrom)
            optional("with_runtime.lte", runtimeTo)
            if (television) optional("with_status", seriesStatus)
        }
    }

    companion object {
        val SORTS = listOf("popularity.desc", "popularity.asc", "release_date.desc", "release_date.asc",
            "vote_average.desc", "vote_average.asc", "vote_count.desc", "vote_count.asc")
    }
}

enum class DiscoveryValidationError { DATE, DATE_ORDER, RATING, VOTES, RUNTIME, RUNTIME_ORDER, GENRES, OPTIONS }

@Keep
data class SavedDiscovery(val id: String, val name: String, val query: DiscoveryQuery)
