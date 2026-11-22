package com.nuvio.tv.domain.model

import com.nuvio.tv.data.remote.api.TmdbCertification
import java.util.Locale

internal data class MovieRatingSystem(val country: String, val ranks: Map<String, Int>) {
    companion object {
        fun forRegion(region: String, certifications: Map<String, List<TmdbCertification>>): MovieRatingSystem {
            val country = region.uppercase(Locale.ROOT)
            val levels = certifications[country].orEmpty()
                .filter { it.order > 0 && it.certification.isNotBlank() && it.certification.uppercase(Locale.ROOT) !in
                    setOf("NR", "UNRATED", "NOT RATED", "E", "EXEMPT", "RC") }
                .sortedBy { it.order }.associate { it.certification to it.order }
            if (levels.isNotEmpty()) return MovieRatingSystem(country, levels)
            val fallback = if (country in DiscoveryQuery.CERTIFICATIONS) country else "US"
            return MovieRatingSystem(fallback, DiscoveryQuery.CERTIFICATIONS.getValue(fallback)
                .mapIndexed { i, rating -> rating to i + 1 }.toMap())
        }
    }
}

internal data class MovieAgeLimit(val system: MovieRatingSystem, val maximum: String) {
    fun accepts(certifications: List<String>): Boolean {
        val ranks = system.ranks
        val cap = ranks[maximum] ?: return false
        val published = certifications.map { it.trim() }.filter { it.isNotEmpty() }
        return published.isNotEmpty() && published.all { (ranks[it] ?: Int.MAX_VALUE) <= cap }
    }
}
