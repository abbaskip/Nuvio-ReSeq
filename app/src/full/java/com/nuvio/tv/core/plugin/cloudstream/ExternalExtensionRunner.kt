package com.nuvio.tv.core.plugin.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.nuvio.tv.core.plugin.TestDiagnostics
import com.nuvio.tv.core.plugin.matchPluginEpisodeIndex
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LocalScraperResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ExtExtensionRunner"
private const val EXECUTION_TIMEOUT_MS = 120_000L
// Per-provider loadLinks cap. Mega-aggregators (Phisher StreamPlay, Ultima,
// TorraStream) scrape many source sites in parallel and happily burn through
// minutes — cap them at 60s and return the partial link set collected so far.
private const val LOADLINKS_TIMEOUT_MS = 60_000L
private const val MIN_TITLE_SIMILARITY = 0.5
private const val MAX_ALT_TITLES = 8

/**
 * Executes external DEX extensions by bridging between NuvioTV's TMDB ID-based system
 * and the extensions' text search-based API.
 *
 * Flow: TMDB ID → title lookup → search() → match → load() → loadLinks() → LocalScraperResult
 */
@Singleton
class ExternalExtensionRunner @Inject constructor(
    private val extensionLoader: ExternalExtensionLoader,
    private val tmdbMetadataService: TmdbMetadataService
) {
    suspend fun execute(
        scraperId: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> = withContext(Dispatchers.IO) {
        executeProviders(scraperId, tmdbId, mediaType, season, episode, diagnostics = null)
    }

    suspend fun executeWithDiagnostics(
        scraperId: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics
    ): List<LocalScraperResult> = withContext(Dispatchers.IO) {
        executeProviders(scraperId, tmdbId, mediaType, season, episode, diagnostics)
    }

    private suspend fun executeProviders(
        scraperId: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics?
    ): List<LocalScraperResult> = coroutineScope {
        val apis = extensionLoader.loadExtension(scraperId, diagnostics)
        if (apis.isEmpty()) {
            diagnostics?.addStep("No MainAPI available after load")
            return@coroutineScope emptyList()
        }
        val slots = Semaphore(3)
        val steps = apis.map { diagnostics?.let { TestDiagnostics() } }
        val results = Array<List<LocalScraperResult>>(apis.size) { emptyList() }
        suspend fun searchAll() = coroutineScope {
            apis.mapIndexed { index, api ->
                async {
                    slots.withPermit {
                        val trace = steps[index]
                        try {
                            trace?.addStep("Using MainAPI: ${api.name} (${api.javaClass.simpleName})")
                            results[index] = executeInternal(api, tmdbId, mediaType, season, episode, trace)
                            trace?.addStep("Found ${results[index].size} playable streams")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            trace?.addStep("Provider failed: ${e.javaClass.simpleName}")
                            Log.w(TAG, "Provider ${api.name} failed", e)
                        } catch (e: Error) {
                            if (e is VirtualMachineError || e is ThreadDeath) throw e
                            trace?.addStep("Runtime error: ${e.message?.take(200)}")
                            Log.w(TAG, "Provider ${api.name} failed", e)
                        }
                    }
                }
            }.awaitAll()
        }
        // The outer scraper has a 120s deadline. Return completed siblings if another API stalls.
        val budget = if (apis.size > 1) 90_000L else EXECUTION_TIMEOUT_MS
        if (withTimeoutOrNull(budget) { searchAll() } == null) diagnostics?.addStep("Search timed out")
        steps.filterNotNull().forEach { diagnostics?.steps?.addAll(it.steps) }
        results.flatMap { it }.distinct()
    }

    private fun extractMissingClass(e: Error): String? {
        val msg = e.message ?: return null
        val match = Regex("""(?:L?)([\w/.]+)(?:;)?""").find(msg)
        return match?.groupValues?.get(1)?.replace('/', '.')
    }

    private suspend fun executeInternal(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics?
    ): List<LocalScraperResult> {
        if (api is TmdbProvider) {
            return executeTmdbProvider(api, tmdbId, mediaType, season, episode, diagnostics)
        }
        return executeSearchBased(api, tmdbId, mediaType, season, episode, diagnostics)
    }

    /**
     * Execute a TmdbProvider extension using the same flow as CloudStream:
     * 1. Construct the JSON that the extension's load() expects
     * 2. Call api.load(json) → extension fetches metadata, constructs its internal LinkData
     * 3. Extract the data string from the LoadResponse
     * 4. Call api.loadLinks(data, ...) → extension resolves streams
     *
     * TmdbProvider extensions (StreamPlay, Ultima, etc.) override load() to accept
     * JSON like {"id":803796,"type":"movie"}, NOT a TMDB URL. They then construct
     * their own internal data classes (LinkData) with fields like "id", "imdbId",
     * "title" etc. that differ from TmdbLink's field names.
     */
    private suspend fun executeTmdbProvider(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics?
    ): List<LocalScraperResult> {
        val tmdbIdInt = tmdbId.toIntOrNull()
        val isMovie = mediaType.lowercase() == "movie"
        val type = if (isMovie) "movie" else "tv"

        // Construct the JSON that TmdbProvider extensions expect in load()
        // This matches what their search() returns as URLs
        val loadJson = """{"id":$tmdbIdInt,"type":"$type"}"""

        Log.d(TAG, "TmdbProvider ${api.name}: load($loadJson)")
        diagnostics?.addStep("Loading TMDB $type $tmdbId")
        val loadResponse = try {
            api.load(loadJson)
        } catch (e: Exception) {
            Log.w(TAG, "TmdbProvider ${api.name} load(json) threw: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            null
        } catch (e: Error) {
            val missing = extractMissingClass(e)
            Log.w(TAG, "TmdbProvider ${api.name} load(json) error: ${missing ?: e.message?.take(100)}")
            null
        }

        if (loadResponse != null) {
            Log.d(TAG, "TmdbProvider ${api.name}: loaded ${loadResponse.javaClass.simpleName}")
            val data = extractData(loadResponse, mediaType, season, episode)
            if (data != null) {
                Log.d(TAG, "TmdbProvider ${api.name}: loadLinks data=${data.take(200)}")
                return executeTmdbLoadLinks(api, data)
            }
            Log.w(TAG, "TmdbProvider ${api.name}: no data for S${season}E${episode}")
        }

        // Fallback: try with TMDB URL format (standard TmdbProvider.load())
        val tmdbUrl = if (isMovie) {
            "https://www.themoviedb.org/movie/$tmdbId"
        } else {
            "https://www.themoviedb.org/tv/$tmdbId"
        }
        Log.d(TAG, "TmdbProvider ${api.name}: fallback load($tmdbUrl)")
        diagnostics?.addStep("Trying provider with TMDB URL")
        val fallbackResponse = try {
            api.load(tmdbUrl)
        } catch (e: Exception) {
            Log.w(TAG, "TmdbProvider ${api.name} fallback load(url) threw: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            null
        } catch (e: Error) { null }

        if (fallbackResponse != null) {
            val data = extractData(fallbackResponse, mediaType, season, episode)
            if (data != null) {
                Log.d(TAG, "TmdbProvider ${api.name}: fallback loadLinks data=${data.take(200)}")
                return executeTmdbLoadLinks(api, data)
            }
        }

        Log.w(TAG, "TmdbProvider ${api.name}: both load() paths failed")
        return emptyList()
    }

    private suspend fun executeTmdbLoadLinks(
        api: MainAPI,
        data: String
    ): List<LocalScraperResult> {
        // Use thread-safe list so links collected during loadLinks survive timeout
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subtitles = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())

        // Wrap loadLinks in withTimeoutOrNull so the timeout cancels only this block
        // (returning null locally) rather than propagating cancellation out and
        // discarding the already-collected link set. This is the cancellation-scope
        // trick that lets us return partial results from mega-aggregators.
        val completed = withTimeoutOrNull(LOADLINKS_TIMEOUT_MS) {
            try {
                api.loadLinks(
                    data = data,
                    isCasting = false,
                    subtitleCallback = { subtitles.add(it) },
                    callback = { links.add(it) }
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "TmdbProvider ${api.name} loadLinks threw: ${e.javaClass.simpleName} (${links.size} links collected)")
                false
            } catch (e: Error) {
                val missing = extractMissingClass(e)
                Log.w(TAG, "TmdbProvider ${api.name} loadLinks error: ${missing ?: e.message} (${links.size} links collected)")
                false
            }
        }
        if (completed == null) {
            Log.w(TAG, "TmdbProvider ${api.name} loadLinks timed out at ${LOADLINKS_TIMEOUT_MS}ms (${links.size} links collected so far)")
        }

        if (links.isEmpty()) {
            Log.w(TAG, "TmdbProvider ${api.name}: 0 links collected")
            return emptyList()
        }

        Log.d(TAG, "TmdbProvider ${api.name}: ${links.size} links, ${subtitles.size} subs")
        val domainSubtitles = subtitles.toDomainSubtitles(api.name)
        return links.filterValid().map { link -> link.toLocalScraperResult(api.name, domainSubtitles) }
    }

    private suspend fun executeSearchBased(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics?
    ): List<LocalScraperResult> {
        val contentType = when (mediaType.lowercase()) {
            "movie" -> ContentType.MOVIE
            else -> ContentType.SERIES
        }
        diagnostics?.addStep("Fetching TMDB title for $tmdbId")
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId, contentType)
        if (enrichment == null) {
            Log.e(TAG, "Failed to fetch TMDB enrichment for $tmdbId")
            return emptyList()
        }

        val title = enrichment.localizedTitle ?: return emptyList()
        val year = enrichment.releaseInfo?.take(4)?.toIntOrNull()

        // Build candidate titles for multi-language matching: primary localized title,
        // TMDB original title (often non-English for foreign content), plus alt titles
        // per country. Filter alts to Latin-script and cap the list — TMDB returns
        // translations in every script (Cyrillic/CJK/Arabic/Thai/etc.), and trying
        // each one against a Spanish/Portuguese/English provider wastes requests.
        val candidateTitles = buildList {
            add(title)
            enrichment.originalTitle
                ?.takeIf { it.isNotBlank() && !equalsIgnoreCase(it, title) }
                ?.let(::add)
            enrichment.alternativeTitles
                .asSequence()
                .filter { it.isNotBlank() && isLatinScript(it) }
                .distinctBy { it.lowercase() }
                .filter { alt -> none { equalsIgnoreCase(it, alt) } }
                .take(MAX_ALT_TITLES)
                .forEach(::add)
        }

        Log.d(TAG, "SearchBased ${api.name}: searching for \"$title\" (${candidateTitles.size} candidates)")

        diagnostics?.addStep("Searching for $title ($year)")
        var outcome = trySearch(api, title)
        var searchResults = outcome.items
        var hostDead = outcome.hostUnreachable
        var unsupported = outcome.unsupported

        if (searchResults.isNullOrEmpty() && !hostDead && !unsupported && title.contains(Regex("[:\\-–—]"))) {
            val simplified = title.replace(Regex("[:\\-–—]"), " ").replace(Regex("\\s+"), " ").trim()
            Log.d(TAG, "SearchBased ${api.name}: retrying with simplified \"$simplified\"")
            outcome = trySearch(api, simplified)
            searchResults = outcome.items
            if (outcome.hostUnreachable) hostDead = true
            if (outcome.unsupported) unsupported = true
        }

        // Multi-title fallback: if primary found nothing AND host is reachable AND
        // provider supports search, try alts in parallel. Parallel because sequential
        // 7x retries × ~500ms each = ~3.5s wasted per miss; running concurrently cuts
        // this to ~500ms. Picks the first non-empty result in candidate order.
        if (searchResults.isNullOrEmpty() && !hostDead && !unsupported) {
            val alts = candidateTitles.drop(1)
            if (alts.isNotEmpty()) {
                Log.d(TAG, "SearchBased ${api.name}: trying ${alts.size} alt titles in parallel")
                val altOutcomes = coroutineScope {
                    alts.map { alt -> async { alt to trySearch(api, alt) } }.awaitAll()
                }
                altOutcomes.firstOrNull { it.second.hostUnreachable }?.let { hostDead = true }
                altOutcomes.firstOrNull { it.second.unsupported }?.let { unsupported = true }
                if (!hostDead && !unsupported) {
                    altOutcomes.firstOrNull { !it.second.items.isNullOrEmpty() }?.let { (alt, o) ->
                        Log.d(TAG, "SearchBased ${api.name}: alt title \"$alt\" returned ${o.items?.size ?: 0} results")
                        searchResults = o.items
                    }
                }
            }
        }

        if (searchResults.isNullOrEmpty()) {
            diagnostics?.addStep(when { hostDead -> "Provider host unreachable"; unsupported -> "Search unsupported"; else -> "No search results" })
            when {
                hostDead -> Log.w(TAG, "SearchBased ${api.name}: host unreachable, skipping (primary=\"$title\")")
                unsupported -> Log.w(TAG, "SearchBased ${api.name}: search() unsupported, skipping (primary=\"$title\")")
                else -> Log.w(TAG, "SearchBased ${api.name}: 0 search results for any of ${candidateTitles.size} titles (primary=\"$title\")")
            }
            return emptyList()
        }
        Log.d(TAG, "SearchBased ${api.name}: ${searchResults.size} results")

        val bestMatch = findBestMatch(searchResults, candidateTitles, year, mediaType)
        if (bestMatch == null) {
            diagnostics?.addStep("No matching title in ${searchResults.size} results")
            Log.d(TAG, "No suitable match in ${api.name} results for: $title ($year) [candidates=${candidateTitles.size}]")
            searchResults.take(5).forEachIndexed { i, r ->
                val sim = candidateTitles.maxOf { calculateSimilarity(r.name, it) }
                Log.d(TAG, "  [$i] \"${r.name}\" (sim=${String.format("%.2f", sim)}, type=${r.type})")
            }
            return emptyList()
        }
        Log.d(TAG, "Best match from ${api.name}: ${bestMatch.name} (${bestMatch.url})")

        diagnostics?.addStep("Loading matched title: ${bestMatch.name}")
        val loadResponse = try {
            api.load(bestMatch.url)
        } catch (e: Exception) {
            Log.e(TAG, "SearchBased ${api.name} load() threw: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        } catch (e: Error) {
            val missing = extractMissingClass(e)
            Log.e(TAG, "SearchBased ${api.name} load() error: ${missing ?: e.message}", e)
            null
        }
        if (loadResponse == null) {
            Log.w(TAG, "SearchBased ${api.name}: load(${bestMatch.url}) returned null")
            return emptyList()
        }
        Log.d(TAG, "SearchBased ${api.name}: loaded ${loadResponse.javaClass.simpleName}")

        val data = extractData(loadResponse, mediaType, season, episode)
        if (data == null) {
            diagnostics?.addStep("No episode data for S${season}E${episode}")
            Log.d(TAG, "No data extracted from ${api.name} for S${season}E${episode}")
            return emptyList()
        }

        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subtitles = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())

        diagnostics?.addStep("Resolving playable links")
        val success = withTimeoutOrNull(LOADLINKS_TIMEOUT_MS) {
            try {
                api.loadLinks(
                    data = data,
                    isCasting = false,
                    subtitleCallback = { subtitles.add(it) },
                    callback = { links.add(it) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "SearchBased ${api.name} loadLinks threw: ${e.javaClass.simpleName}: ${e.message}", e)
                false
            } catch (e: Error) {
                val missing = extractMissingClass(e)
                Log.e(TAG, "SearchBased ${api.name} loadLinks error: ${missing ?: e.message}", e)
                false
            }
        }
        if (success == null) {
            Log.w(TAG, "SearchBased ${api.name} loadLinks timed out at ${LOADLINKS_TIMEOUT_MS}ms (${links.size} links collected so far)")
        }

        if (success != true && links.isEmpty()) {
            Log.w(TAG, "SearchBased ${api.name}: loadLinks returned false/null, 0 links")
            return emptyList()
        }

        Log.d(TAG, "SearchBased ${api.name}: ${links.size} links, ${subtitles.size} subs")
        val domainSubtitles = subtitles.toDomainSubtitles(api.name)
        return links.filterValid().map { link -> link.toLocalScraperResult(api.name, domainSubtitles) }
    }

    /** Extract year from SearchResponse concrete types (not in the interface). */
    private fun getSearchResponseYear(result: SearchResponse): Int? = when (result) {
        is MovieSearchResponse -> result.year
        is TvSeriesSearchResponse -> result.year
        is AnimeSearchResponse -> result.year
        else -> null
    }

    private fun findBestMatch(
        results: List<SearchResponse>,
        candidateTitles: List<String>,
        targetYear: Int?,
        mediaType: String
    ): SearchResponse? {
        val isMovie = mediaType.lowercase() == "movie"
        val movieTypes = setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary)
        val tvTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.Cartoon, TvType.AsianDrama)
        // Catch-all TV types: niche providers (anime/dorama/donghua sites) may list
        // any result as Anime/AsianDrama/OVA, giving a free type-match against any
        // TV target. Require near-exact title for these to prevent false positives
        // like MundoDonghua matching "The Invincible" for the "Invincible" series.
        val catchAllTvTypes = setOf(TvType.Anime, TvType.OVA, TvType.AsianDrama)

        return results
            .mapNotNull { result ->
                val resultType = result.type
                val resultYear = getSearchResponseYear(result)
                val titleSimilarity = candidateTitles.maxOf { calculateSimilarity(result.name, it) }
                val isExactTitle = titleSimilarity >= 0.95

                // Type check: hard reject only when title is NOT near-exact. Latam/es
                // providers often mis-classify TV series as Movie; an exact title match
                // is strong enough to override that. But a fuzzy title match ("Atom Eve"
                // for "Invincible", "Hardy Boys" for "The Boys") must have matching type.
                if (resultType != null && !isExactTitle) {
                    val typeOk = if (isMovie) resultType in movieTypes else resultType in tvTypes
                    if (!typeOk) return@mapNotNull null
                }
                // Catch-all TV types need stronger title evidence.
                if (!isMovie && resultType in catchAllTvTypes && titleSimilarity < 0.9) {
                    return@mapNotNull null
                }
                // Year check: hard reject when both years known and differ by >1.
                if (targetYear != null && resultYear != null &&
                    kotlin.math.abs(targetYear - resultYear) > 1) {
                    return@mapNotNull null
                }

                val yearBonus = if (targetYear != null && resultYear == targetYear) 0.15 else 0.0
                val typeBonus = when {
                    resultType == null -> 0.0
                    isMovie && resultType in movieTypes -> 0.05
                    !isMovie && resultType in tvTypes -> 0.05
                    else -> 0.0 // type mismatch but exact title — neutral, don't boost
                }
                val score = titleSimilarity + yearBonus + typeBonus
                result to score
            }
            .filter { it.second >= MIN_TITLE_SIMILARITY }
            .maxByOrNull { it.second }
            ?.first
    }

    private data class SearchOutcome(
        val items: List<SearchResponse>?,
        val hostUnreachable: Boolean = false,
        val unsupported: Boolean = false // provider didn't implement search()
    )

    private suspend fun trySearch(api: MainAPI, query: String): SearchOutcome = try {
        SearchOutcome(api.search(query, 1)?.items)
    } catch (e: java.net.UnknownHostException) {
        Log.e(TAG, "SearchBased ${api.name} search(\"$query\") DNS fail: ${e.message}")
        SearchOutcome(null, hostUnreachable = true)
    } catch (e: NotImplementedError) {
        // Provider (e.g. live-sports scrapers) doesn't override search(); retries
        // with alt titles will all throw the same. Short-circuit.
        Log.e(TAG, "SearchBased ${api.name}: search() not implemented; skipping provider")
        SearchOutcome(null, unsupported = true)
    } catch (e: Exception) {
        Log.e(TAG, "SearchBased ${api.name} search(\"$query\") threw: ${e.javaClass.simpleName}: ${e.message}", e)
        SearchOutcome(null)
    } catch (e: Error) {
        val missing = extractMissingClass(e)
        Log.e(TAG, "SearchBased ${api.name} search(\"$query\") error: ${missing ?: e.message}", e)
        SearchOutcome(null)
    }

    private fun equalsIgnoreCase(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

    /**
     * Returns true if the title is predominantly Latin-script (ASCII + Latin-1 +
     * Latin Extended). Filters out TMDB alternative titles in Cyrillic, CJK, Arabic,
     * Hebrew, Thai, Greek, Georgian, Korean, etc. — which waste search requests on
     * providers that index Latin-script titles only (most storm-ext / latam sites).
     */
    private fun isLatinScript(s: String): Boolean {
        val letters = s.filter(Char::isLetter)
        if (letters.isEmpty()) return true
        val latinCount = letters.count { c ->
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A,
                Character.UnicodeBlock.LATIN_EXTENDED_B,
                Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL -> true
                else -> false
            }
        }
        return latinCount.toDouble() / letters.length >= 0.7
    }

    private fun extractData(
        response: LoadResponse,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): String? = when (response) {
        is MovieLoadResponse -> response.dataUrl
        is LiveStreamLoadResponse -> response.dataUrl
        is TvSeriesLoadResponse -> {
            findEpisode(response.episodes, season, episode)?.data
        }
        is AnimeLoadResponse -> {
            val allEpisodes = response.episodes.values.flatten()
            findEpisode(allEpisodes, season, episode)?.data
        }
        else -> null
    }

    private fun findEpisode(episodes: List<Episode>, season: Int?, episode: Int?): Episode? {
        val index = matchPluginEpisodeIndex(
            entries = episodes.map { it.season to it.episode },
            season = season,
            episode = episode
        ) ?: return null
        return episodes.getOrNull(index)
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        val a = s1.lowercase().trim()
        val b = s2.lowercase().trim()
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val aNorm = normalizeTitleForMatch(a)
        val bNorm = normalizeTitleForMatch(b)
        if (aNorm == bNorm) return 0.95
        if (aNorm.isEmpty() || bNorm.isEmpty()) return 0.0

        if (aNorm.contains(bNorm) || bNorm.contains(aNorm)) {
            // Length-ratio-weighted containment. Prevents "Boys" ⊂ "Fantasy Boys" or
            // "Invincible" ⊂ "The Boys: Diábolico Latino" from scoring 0.85 — those
            // are different works. Only treat as strong match when the strings are
            // close in length (trivial punctuation/articles diff).
            val shortLen = minOf(aNorm.length, bNorm.length).toDouble()
            val longLen = maxOf(aNorm.length, bNorm.length).toDouble()
            val ratio = shortLen / longLen
            if (ratio >= 0.8) return 0.85
            // else fall through to Levenshtein — naturally scores lower for
            // significant length mismatches.
        }

        val distance = levenshteinDistance(aNorm, bNorm)
        val maxLen = maxOf(aNorm.length, bNorm.length)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    /**
     * Strip noise that provider titles commonly add: release year, season/part
     * markers, language/dub tags. Lets "Invincible S4 Castellano" normalize to
     * "Invincible" so it matches the TMDB primary title cleanly.
     */
    private fun normalizeTitleForMatch(lowered: String): String {
        return lowered
            .replace(Regex("\\(\\d{4}\\)"), " ")
            .replace(Regex("\\b\\d{4}\\b"), " ") // bare year
            .replace(Regex("\\b(temporada|season)\\s*\\d+\\b"), " ")
            .replace(Regex("\\b[st]\\d{1,2}\\b"), " ") // s1, t2, s04
            .replace(Regex("\\b(part|parte)\\s*\\d+\\b"), " ")
            .replace(Regex("\\b(latino|castellano|subtitulado|sub\\s*espa(ñ|n)ol|espa(ñ|n)ol|dual|vose|vostfr|subbed|dubbed)\\b"), " ")
            .replace(Regex("[:\\-–—]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }

    /** Filter out broken ExtractorLinks (invalid URLs, error strings, etc.) */
    private fun List<ExtractorLink>.filterValid(): List<ExtractorLink> {
        return filter { link ->
            val url = link.url
            when {
                url.isBlank() -> false
                url == "error" || url == "null" -> false
                !url.startsWith("http://") && !url.startsWith("https://") -> false
                else -> true
            }.also { valid ->
                if (!valid) Log.w(TAG, "Filtered invalid link: source=${link.source}, url=${url.take(60)}")
            }
        }
    }

    private fun List<SubtitleFile>.toDomainSubtitles(addonName: String): List<com.nuvio.tv.domain.model.Subtitle> {
        return this.mapNotNull { file ->
            val subUrl = file.url.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lang = file.lang.takeIf { it.isNotBlank() } ?: "Unknown"
            val fileHeaders = runCatching {
                val prop = file.javaClass.methods.find { it.name == "getHeaders" }
                @Suppress("UNCHECKED_CAST")
                prop?.invoke(file) as? Map<String, String>
            }.getOrNull()?.ifEmpty { null }

            com.nuvio.tv.domain.model.Subtitle(
                id = "$lang-$subUrl",
                url = subUrl,
                lang = lang,
                addonName = addonName,
                addonLogo = null,
                isStreamProvided = true,
                headers = fileHeaders
            )
        }
    }

    private fun ExtractorLink.toLocalScraperResult(
        providerName: String,
        subtitles: List<com.nuvio.tv.domain.model.Subtitle> = emptyList()
    ): LocalScraperResult {
        val qualityStr = Qualities.getStringByInt(quality).ifEmpty { null }
        val streamType = when (type) {
            ExtractorLinkType.M3U8 -> "hls"
            ExtractorLinkType.DASH -> "dash"
            else -> null
        }
        val allHeaders = buildMap {
            putAll(headers)
            if (referer.isNotBlank()) put("Referer", referer)
        }

        return LocalScraperResult(
            title = name,
            name = source,
            url = url,
            quality = qualityStr,
            type = streamType,
            headers = allHeaders.ifEmpty { null },
            provider = providerName,
            subtitles = subtitles
        )
    }
}
