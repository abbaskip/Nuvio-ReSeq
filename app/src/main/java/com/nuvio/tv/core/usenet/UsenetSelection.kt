// Lazy archive behavior adapted from haaihond/NuvioTV (GPL-3.0),
// native/usenet/internal/engine/select.go, commit bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6.
package com.nuvio.tv.core.usenet

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import com.nuvio.tv.core.player.FilenameEpisodeMatcher

internal class UsenetCandidate(val name: String, val estimate: Long, val open: () -> UsenetFile)

internal object UsenetSelection {
    private val videos = setOf("mkv", "mp4", "webm", "avi", "m4v", "mov", "m2ts", "ts", "mpg", "mpeg", "wmv")
    private val rar = Regex("""(?i)^(.*?)(?:\.part(\d+)\.rar|\.([r-z])(\d{2,3})|\.rar)$""")
    private val split = Regex("""(?i)^(.+)\.(\d{3,5})$""")
    private val sidecars = setOf("par2", "nfo", "sfv", "srr", "nzb", "srt", "sub", "txt", "jpg", "png")

    private fun videoName(name: String) = name.substringAfterLast('.').lowercase() in videos

    private enum class Kind { RAR, SEVEN_ZIP, VIDEO, NUMERIC }
    private data class Volume(val key: String, val number: Int, val first: Int, val kind: Kind)

    private fun volume(name: String): Volume? {
        val lower = name.lowercase()
        rar.matchEntire(lower)?.let { match ->
            val (stem, part, letter, digits) = match.destructured
            if (part.isNotEmpty()) return Volume("$stem.part.rar", part.toIntOrNull() ?: -1, 1, Kind.RAR)
            val number = if (letter.isEmpty()) 0 else (letter.single() - 'r') * 100 + digits.toInt() + 1
            return Volume("$stem.rar", number, 0, Kind.RAR)
        }
        val parts = split.matchEntire(lower)
        val key = parts?.groupValues?.get(1) ?: lower
        val kind = when {
            key.endsWith(".7z") -> Kind.SEVEN_ZIP
            parts != null && videoName(key) -> Kind.VIDEO
            parts != null -> Kind.NUMERIC
            else -> return null
        }
        return Volume(key, parts?.groupValues?.get(2)?.toInt() ?: 0, if (parts == null) 0 else 1, kind)
    }

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

    private val sample = Regex("""(?i)(?:^|[\\/._ -])(?:sample|trailer)(?:[\\/._ -]|$)""")
    private val episodeList = Regex("""(?i)S\s*(\d{1,3})\s*E\s*(\d{1,4})((?:-?E\d{1,4})+)""")
    private val followingEpisode = Regex("""(?i)(-?)E(\d{1,4})""")

    private fun episodeMatch(name: String, season: Int?, episode: Int): Boolean? {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val coordinates = FilenameEpisodeMatcher.extractSeasonEpisode(leaf) ?: return null
        if (coordinates.first != null && coordinates.first != season) return false
        if (coordinates.second == episode) return true
        val list = episodeList.find(leaf) ?: return false
        var previous = coordinates.second
        for (entry in followingEpisode.findAll(list.groupValues[3])) {
            val next = entry.groupValues[2].toInt()
            if (episode == next || (entry.groupValues[1] == "-" && episode in previous..next)) return true
            previous = next
        }
        return false
    }

    private fun recognized(name: String) = videoName(name) || volume(name) != null

    private fun recover(files: List<NzbFile>, cache: ArticleCache, checkActive: () -> Unit): List<NzbFile> {
        fun unknown(file: NzbFile) = file.articles.isNotEmpty() && !recognized(file.name) &&
            file.name.substringAfterLast('.').lowercase() !in sidecars
        if (files.none(::unknown)) return files
        val recovery = files.filter { it.name.endsWith(".par2", true) && it.articles.isNotEmpty() }
        if (recovery.isEmpty() && files.any { recognized(it.name) }) return files
        val names = Par2Names()
        var failure: UsenetFailure? = null
        val descriptions by lazy {
            for (file in recovery.sortedBy { it.postedBytes }.take(3)) {
                checkActive()
                try { names.read(PostedFile(file, cache, verifyTail = false), checkActive) }
                catch (e: UsenetFailure) { checkActive(); failure = e }
                if (names.hasDescriptions()) break
            }
            names
        }
        cache.archive("obfuscated", files.count(::unknown))
        cache.probeHeads(files.filter(::unknown).take(4096).map { it.articles.first() }, checkActive)
        var probes = 0
        val recovered = files.mapIndexed { i, file ->
            if (!unknown(file) || probes >= 4096) return@mapIndexed file
            checkActive()
            probes++
            try {
                val head = cache.head(file.articles.first()) ?: cache[file.articles.first()]
                val name = head.filename?.takeIf(::recognized)
                    ?: when {
                        head.bytes.startsWith(StoredSevenZip.signature) -> "unnamed-$i.7z"
                        head.bytes.startsWith(StoredRar.signature) -> "unnamed-$i.rar"
                        else -> null
                    }
                    ?: descriptions.takeIf { it.hasDescriptions() }?.find(PostedFile(file, cache, verifyTail = false))
                        ?.takeIf(::recognized)
                if (name == null) file else file.copy(name = name)
            } catch (e: UsenetFailure) {
                checkActive()
                failure = e
                file
            }
        }
        if (recovered.none { recognized(it.name) }) failure?.let { throw it }
        return recovered
    }

    // Some multipart posts have unrelated .rar filenames. RAR5 supplies the real ordinal.
    // Join only one complete, unambiguous chain with matching member name and declared file size.
    private fun regroup(files: List<NzbFile>, cache: ArticleCache, passwords: RarPasswords,
                        checkActive: () -> Unit): List<NzbFile> {
        val volumes = files.map { volume(it.name) }
        val normalKeys = volumes.filterNotNull().filter { it.number != 0 }.mapTo(hashSetOf()) { it.key }
        val possible = files.indices.filter { i ->
            val v = volumes[i]
            v?.kind == Kind.RAR && v.first == 0 && v.number == 0 && v.key !in normalKeys && files[i].articles.isNotEmpty()
        }
        if (possible.size !in 2..4096) return files
        data class Piece(val position: Int, val number: Long, val part: RarPart)
        val chains = mutableMapOf<Pair<String, Long>, MutableList<Piece>>()
        for (i in possible) {
            checkActive()
            try {
                val head = (cache.head(files[i].articles.first()) ?: cache[files[i].articles.first()]).bytes
                if (!head.startsWith(StoredRar.signature)) continue
                val version = head.getOrNull(6)?.toInt() ?: continue
                var number = -1L
                val member = StoredRar.index(PostedFile(files[i], cache, false), passwords) { number = it }.singleOrNull() ?: continue
                if (!member.more && !member.continues) continue
                val key = "$version:${member.name}" to (member.unpacked ?: continue)
                val chain = chains.getOrPut(key) { mutableListOf() }
                if (version == 0) number = chain.size.toLong() // RAR4 has no internal ordinal: preserve NZB order.
                if (number !in 0..4095) continue
                chain.add(Piece(i, number, member))
            } catch (_: UsenetFailure) { /* The normal per-archive path reports an unreadable volume if selected. */ }
        }
        val result = files.toMutableList()
        for (pieces in chains.values) {
            val ordered = pieces.sortedBy { it.number }
            if (ordered.size < 2 || !ordered.withIndex().all { (i, p) -> p.number == i.toLong() &&
                    p.part.continues == (i > 0) && p.part.more == (i < ordered.lastIndex) }) continue
            for (p in ordered) result[p.position] = result[p.position].copy(
                name = "\u0000rar-group-${ordered.first().position}.part${p.number + 1}.rar")
        }
        return result
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun groupFiles(group: List<Pair<Volume, NzbFile>>, cache: ArticleCache,
                           passwords: RarPasswords, checkActive: () -> Unit): Sequence<UsenetCandidate> = sequence {
        val sorted = group.groupBy { it.second.name.lowercase() }.values.map { copies ->
            copies.maxWith(compareBy<Pair<Volume, NzbFile>> { it.second.articles.isNotEmpty() }.thenBy { it.second.postedBytes })
        }.sortedBy { it.first.number }
        val info = sorted.first().first
        val first = if (info.kind == Kind.RAR && info.number == 0) 0 else info.first
        ensureUsenet(sorted.withIndex().all { (i, pair) -> pair.first.number == first + i && pair.first.first == info.first },
            "NZB archive volumes are missing or duplicated.")
        val opened = sorted.map { pair -> lazy {
            checkActive()
            PostedFile(pair.second, cache, verifyTail = false)
        } }
        fun openVolume(i: Int): UsenetFile = opened[i].value
        val openers = sorted.indices.map { i -> { openVolume(i) } }
        val kind = if (info.kind == Kind.NUMERIC) {
            val head = openVolume(0).bytesAt(0, minOf(8L, openVolume(0).size).toInt())
            when {
                head.startsWith(StoredRar.signature) -> Kind.RAR
                head.startsWith(StoredSevenZip.signature) -> Kind.SEVEN_ZIP
                else -> throw UsenetFailure("Unrecognized split archive.")
            }
        } else info.kind
        cache.archive(if (kind == Kind.RAR) "rar" else if (kind == Kind.SEVEN_ZIP) "7z" else "split", sorted.size)
        val candidates = if (kind == Kind.RAR) {
            RarWalker(openers, passwords, checkActive) { i, size -> LazyPostedFile(sorted[i].second, cache, size) }.candidates()
        } else {
            val head = openVolume(0)
            val joined = StoredFile(info.key, sorted.indices.map { i ->
                val file = if (i == 0) head else if (i == sorted.lastIndex) openVolume(i)
                    else LazyPostedFile(sorted[i].second, cache, head.size)
                FileExtent(file, 0, file.size)
            })
            if (kind == Kind.SEVEN_ZIP) StoredSevenZip.index(joined).asSequence()
            else sequenceOf(UsenetCandidate(info.key, joined.size) { joined })
        }
        // Preserve explicit-index semantics: only a sole archive member is unwrapped.
        // Ordinary video candidates are yielded before asking the walker for another entry.
        val iterator = candidates.iterator()
        if (!iterator.hasNext()) return@sequence
        val firstCandidate = iterator.next()
        val nestedRar = firstCandidate.name.endsWith(".rar", true)
        val nestedSevenZip = firstCandidate.name.endsWith(".7z", true)
        if ((nestedRar || nestedSevenZip) && !iterator.hasNext()) {
            if (nestedRar) yieldAll(RarWalker(listOf(firstCandidate.open), passwords, checkActive).candidates())
            else yieldAll(StoredSevenZip.index(firstCandidate.open()))
        } else {
            yield(firstCandidate)
            while (iterator.hasNext()) yield(iterator.next())
        }
    }

    fun select(files: List<NzbFile>, cache: ArticleCache, index: Int?, hints: List<String>,
               season: Int? = null, episode: Int? = null, password: String? = null, checkActive: () -> Unit = {}): UsenetFile {
        val passwords = RarPasswords(password, checkActive)
        val matches = matcher(hints)
        if (index != null && index in files.indices && videoName(files[index].name) &&
            (0 until index).all { videoName(files[it].name) || files[it].name.substringAfterLast('.').lowercase() in sidecars }) {
            checkActive()
            val file = files[index]
            ensureUsenet(matches(file.name), "The NZB contains no matching video.")
            return PostedFile(file, cache)
        }
        if (index == null && episode != null) {
            files.firstOrNull { videoName(it.name) && matches(it.name) && !sample.containsMatchIn(it.name) &&
                episodeMatch(it.name, season, episode) == true }?.let {
                checkActive()
                return PostedFile(it, cache)
            }
        }
        // Named episode sets can be selected before unrelated standalone .rar files need regrouping.
        if (index == null && episode != null) {
            val matchingGroups = files.mapNotNull { file -> volume(file.name)?.let { it to file } }
                .groupBy { it.first.key }.filterKeys { episodeMatch(it, season, episode) == true }
            for (group in matchingGroups.values) {
                try {
                    for (candidate in groupFiles(group, cache, passwords, checkActive)) {
                        if (videoName(candidate.name) && matches(candidate.name) && !sample.containsMatchIn(candidate.name) &&
                            episodeMatch(candidate.name, season, episode) == true) return candidate.open()
                    }
                } catch (_: UsenetFailure) { checkActive() /* Full inventory below preserves failure and fallback handling. */ }
            }
        }
        val named = regroup(recover(files, cache, checkActive), cache, passwords, checkActive)
        val groups = named.mapNotNull { file -> volume(file.name)?.let { it to file } }
            .groupBy { it.first.key }.toMutableMap()
        val candidates = mutableListOf<UsenetCandidate>()
        var archiveError: UsenetFailure? = null
        val ordered = if (index == null && episode != null) named.sortedByDescending {
            episodeMatch(volume(it.name)?.key ?: it.name, season, episode) == true
        } else named
        for (file in ordered) {
            checkActive()
            val group = volume(file.name)
            if (group == null) candidates += UsenetCandidate(file.name, file.postedBytes) { PostedFile(file, cache) }
            else {
                val entries = groups.remove(group.key) ?: continue
                try {
                    for (candidate in groupFiles(entries, cache, passwords, checkActive)) {
                        candidates += candidate
                        val eligible = videoName(candidate.name) && matches(candidate.name) && !sample.containsMatchIn(candidate.name)
                        if (index == null && eligible && ((episode != null && episodeMatch(candidate.name, season, episode) == true) ||
                            (episode == null && (hints.isNotEmpty() || candidate.estimate >= entries.sumOf { it.second.postedBytes } / 2)))) {
                            checkActive()
                            return candidate.open()
                        }
                        if (index != null && index in candidates.indices) break
                    }
                }
                catch (error: UsenetFailure) {
                    // Unknown member count makes every following explicit index ambiguous.
                    if (index != null) throw error
                    archiveError = error
                }
            }
            ensureUsenet(candidates.size <= 16384, "Too many archived files.")
            if (index != null && index in candidates.indices) break
        }
        val eligible = candidates.filter { videoName(it.name) && matches(it.name) }
        val features = eligible.filterNot { sample.containsMatchIn(it.name) }.ifEmpty { eligible }
        val selected = when {
            index != null -> candidates.getOrNull(index)?.takeIf { it in eligible }
            episode == null || features.isEmpty() ->
                features.maxByOrNull { it.estimate }
            else -> {
                val marked = features.map { it to episodeMatch(it.name, season, episode) }
                marked.filter { it.second == true }.maxByOrNull { it.first.estimate }?.first
                    ?: marked.singleOrNull()?.takeIf { it.second == null && archiveError == null }?.first
                    ?: throw UsenetFailure("Could not identify the requested episode in this release.")
            }
        }
        checkActive()
        return selected?.open() ?: throw (archiveError ?: UsenetFailure("The NZB contains no matching video."))
    }
}
