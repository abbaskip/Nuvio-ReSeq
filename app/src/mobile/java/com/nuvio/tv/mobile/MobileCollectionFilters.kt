package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.TmdbCollectionFilters

@Composable
internal fun MobileCollectionFilters(initial: TmdbCollectionFilters, apply: (TmdbCollectionFilters) -> Unit, close: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var floor by remember { mutableStateOf(initial.voteAverageGte?.toString().orEmpty()) }
    var ceiling by remember { mutableStateOf(initial.voteAverageLte?.toString().orEmpty()) }
    var votes by remember { mutableStateOf(initial.voteCountGte?.toString().orEmpty()) }
    var year by remember { mutableStateOf(initial.year?.toString().orEmpty()) }
    var issue by remember { mutableStateOf<String?>(null) }
    TouchSheet(close) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { BrowseHeading("Catalogue filters", close) }
            item { MobileTextInput("With genres (IDs)", draft.withGenres.orEmpty()) { draft = draft.copy(withGenres = it.ifBlank { null }) } }
            item { MobileTextInput("Without genres (IDs)", draft.withoutGenres.orEmpty()) { draft = draft.copy(withoutGenres = it.ifBlank { null }) } }
            item { MobileTextInput("Released after (YYYY-MM-DD)", draft.releaseDateGte.orEmpty()) { draft = draft.copy(releaseDateGte = it.ifBlank { null }) } }
            item { MobileTextInput("Released before (YYYY-MM-DD)", draft.releaseDateLte.orEmpty()) { draft = draft.copy(releaseDateLte = it.ifBlank { null }) } }
            item { MobileTextInput("Original language", draft.withOriginalLanguage.orEmpty()) { draft = draft.copy(withOriginalLanguage = it.ifBlank { null }) } }
            item { MobileTextInput("Country of origin", draft.withOriginCountry.orEmpty()) { draft = draft.copy(withOriginCountry = it.ifBlank { null }) } }
            item { MobileTextInput("Include keyword IDs", draft.withKeywords.orEmpty()) { draft = draft.copy(withKeywords = it.ifBlank { null }) } }
            item { MobileTextInput("Exclude keyword IDs", draft.withoutKeywords.orEmpty()) { draft = draft.copy(withoutKeywords = it.ifBlank { null }) } }
            item { MobileTextInput("Include company IDs", draft.withCompanies.orEmpty()) { draft = draft.copy(withCompanies = it.ifBlank { null }) } }
            item { MobileTextInput("Exclude company IDs", draft.withoutCompanies.orEmpty()) { draft = draft.copy(withoutCompanies = it.ifBlank { null }) } }
            item { MobileTextInput("Network IDs", draft.withNetworks.orEmpty()) { draft = draft.copy(withNetworks = it.ifBlank { null }) } }
            item { MobileTextInput("Provider region", draft.watchRegion.orEmpty()) { draft = draft.copy(watchRegion = it.ifBlank { null }) } }
            item { MobileTextInput("Include watch provider IDs", draft.withWatchProviders.orEmpty()) { draft = draft.copy(withWatchProviders = it.ifBlank { null }) } }
            item { MobileTextInput("Exclude watch provider IDs", draft.withoutWatchProviders.orEmpty()) { draft = draft.copy(withoutWatchProviders = it.ifBlank { null }) } }
            item { MobileTextInput("Minimum rating (0–10)", floor) { floor = it } }
            item { MobileTextInput("Maximum rating (0–10)", ceiling) { ceiling = it } }
            item { MobileTextInput("Minimum votes", votes) { votes = it } }
            item { MobileTextInput("Release year", year) { year = it } }
            item {
                issue?.let { Label(it) }
                Action("Apply filters") {
                    val min = floor.toDoubleOrNull()
                    val max = ceiling.toDoubleOrNull()
                    val count = votes.toIntOrNull()
                    val y = year.toIntOrNull()
                    issue = when {
                        floor.isNotBlank() && (min == null || min !in 0.0..10.0) -> "Minimum rating must be between 0 and 10."
                        ceiling.isNotBlank() && (max == null || max !in 0.0..10.0) -> "Maximum rating must be between 0 and 10."
                        min != null && max != null && min > max -> "Minimum rating exceeds maximum."
                        votes.isNotBlank() && (count == null || count < 0) -> "Enter a whole number of votes."
                        year.isNotBlank() && (year.length != 4 || y == null) -> "Enter a four-digit year."
                        listOfNotNull(draft.releaseDateGte, draft.releaseDateLte).any { runCatching { java.time.LocalDate.parse(it) }.isFailure } -> "Use YYYY-MM-DD for release dates."
                        else -> null
                    }
                    if (issue == null) { apply(draft.copy(voteAverageGte = min, voteAverageLte = max, voteCountGte = count, year = y)); close() }
                }
            }
        }
    }
}
