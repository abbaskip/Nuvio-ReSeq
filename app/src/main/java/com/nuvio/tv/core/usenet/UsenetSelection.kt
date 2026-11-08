package com.nuvio.tv.core.usenet

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

internal object UsenetSelection {
    private val videos = setOf("mkv", "mp4", "webm", "avi", "m4v", "mov", "m2ts", "ts", "mpg", "mpeg", "wmv")
    private val volumeSuffix = Regex("""(?i)^(.*?)(?:\.part(\d+)\.rar|\.r(\d\d)|\.rar)$""")

    private fun videoName(name: String) = name.substringAfterLast('.').lowercase() in videos

    private class Candidate(val name: String, val estimate: Long, val open: () -> UsenetFile)

    private fun matcher(hints: List<String>): (String) -> Boolean {
        ensureUsenet(hints.size <= 32, "Too many NZB filename filters.")
        val filters = hints.map { hint ->
            ensureUsenet(hint.length in 1..512, "Invalid NZB filename filter.")
            if (hint.startsWith('/')) {
                val end = hint.lastIndexOf('/')
                ensureUsenet(end > 0 && hint.substring(end + 1) in setOf("", "i"), "Unsupported NZB filename filter.")
                val pattern = try {
                    Pattern.compile(hint.substring(1, end), if (hint.endsWith("/i")) Pattern.CASE_INSENSITIVE else 0)
                } catch (_: PatternSyntaxException) { throw UsenetFailure("Unsupported NZB filename filter.") }
                val matches: (String) -> Boolean = { name -> pattern.matcher(name).find() }
                matches
            } else {
                { name: String -> name.contains(hint, ignoreCase = true) }
            }
        }
        return { name -> filters.isEmpty() || filters.any { it(name) } }
    }

    fun select(files: List<NzbFile>, cache: ArticleCache, index: Int?, hints: List<String>,
               checkActive: () -> Unit = {}): UsenetFile {
        val matches = matcher(hints)
        val groups = linkedMapOf<String, MutableList<Pair<Int, NzbFile>>>()
        fun group(file: NzbFile): String? {
            val match = volumeSuffix.matchEntire(file.name) ?: return null
            val part = match.groupValues[2]
            val old = match.groupValues[3]
            val ordinal = when {
                part.isNotEmpty() -> part.toIntOrNull() ?: throw UsenetFailure("Invalid RAR volume number.")
                old.isNotEmpty() -> old.toInt() + 1
                else -> 0
            }
            val key = match.groupValues[1].lowercase() + if (part.isEmpty()) ".rar" else ".part"
            groups.getOrPut(key) { mutableListOf() }.add(ordinal to file)
            return key
        }
        // Some indexers strip filenames from NZB subjects but leave them in the first yEnc header.
        // Bound discovery work and avoid probing known recovery files.
        val hasNamedMedia = files.any { videoName(it.name) || volumeSuffix.matches(it.name) }
        var probes = 0
        val namedFiles = if (hasNamedMedia) files else files.map { file ->
            val ext = file.name.substringAfterLast('.').lowercase()
            if (file.articles.isEmpty() || ext in setOf("par2", "nfo", "sfv", "srr", "nzb") || probes >= 64) file else {
                checkActive()
                probes++
                val first = try { cache[file.articles.first()] } catch (_: UsenetFailure) { null }
                val name = first?.filename
                if (name != null && (videoName(name) || volumeSuffix.matches(name))) file.copy(name = name) else file
            }
        }
        val grouped = namedFiles.map(::group)
        val candidates = mutableListOf<Candidate>()
        namedFiles.forEachIndexed { i, file ->
            val key = grouped[i]
            if (key == null) {
                candidates += Candidate(file.name, file.postedBytes) { PostedFile(file, cache) }
            } else {
                val volumes = groups.remove(key) ?: return@forEachIndexed
                val sorted = volumes.sortedBy { it.first }
                ensureUsenet(sorted.first().first in 0..1 && sorted.zipWithNext().all { (a, b) -> b.first == a.first + 1 },
                    "NZB is missing a RAR volume.")
                var pending = mutableListOf<RarPart>()
                for ((_, volume) in sorted) {
                    checkActive()
                    for (part in StoredRar.index(PostedFile(volume, cache))) {
                        ensureUsenet(part.continues == pending.isNotEmpty() &&
                            (!part.continues || pending.first().name == part.name), "Incomplete multipart RAR file.")
                        pending.add(part)
                        if (!part.more) {
                            val chain = pending
                            pending = mutableListOf()
                            val length = chain.sumOf { it.extent.length }
                            candidates += Candidate(part.name, length) {
                                ensureUsenet(chain.none { it.encrypted }, "This RAR video is password-protected.")
                                ensureUsenet(chain.all { it.stored }, "This RAR video uses compression and cannot be streamed.")
                                StoredFile(part.name, chain.map { it.extent })
                            }
                        }
                    }
                }
                ensureUsenet(pending.isEmpty(), "NZB is missing the final RAR volume.")
            }
        }
        fun eligible(candidate: Candidate) = videoName(candidate.name) && matches(candidate.name)
        val selected = if (index != null) candidates.getOrNull(index)?.takeIf(::eligible)
            else candidates.filter(::eligible).maxByOrNull { it.estimate }
        checkActive()
        return selected?.open() ?: throw UsenetFailure("The NZB contains no matching video.")
    }
}
