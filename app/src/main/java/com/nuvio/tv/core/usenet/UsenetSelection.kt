package com.nuvio.tv.core.usenet

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import com.nuvio.tv.core.player.FilenameEpisodeMatcher

internal class UsenetCandidate(val name: String, val estimate: Long, val open: () -> UsenetFile)

internal object UsenetSelection {
    private val videos = setOf("mkv", "mp4", "webm", "avi", "m4v", "mov", "m2ts", "ts", "mpg", "mpeg", "wmv")
    private val rar = Regex("""(?i)^(.*?)(?:\.part(\d+)\.rar|\.([r-z])(\d\d)|\.rar)$""")
    private val split = Regex("""(?i)^(.+)\.(\d{3,5})$""")
    private val sidecars = setOf("par2", "nfo", "sfv", "srr", "nzb", "srt", "sub", "txt", "jpg", "png")

    private fun videoName(name: String) = name.substringAfterLast('.').lowercase() in videos

    private enum class Kind { RAR, SEVEN_ZIP, VIDEO }
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
        var probes = 0
        val recovered = files.mapIndexed { i, file ->
            if (!unknown(file) || probes >= 64) return@mapIndexed file
            checkActive()
            probes++
            try {
                val head = cache[file.articles.first()]
                val name = head.filename?.takeIf(::recognized)
                    ?: descriptions.takeIf { it.hasDescriptions() }?.find(PostedFile(file, cache, verifyTail = false))
                        ?.takeIf(::recognized)
                    ?: when {
                        head.bytes.startsWith(StoredSevenZip.signature) -> "unnamed-$i.7z"
                        head.bytes.startsWith(StoredRar.signature) -> "unnamed-$i.rar"
                        else -> null
                    }
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
        if (possible.size !in 2..64) return files
        data class Piece(val position: Int, val number: Long, val part: RarPart)
        val chains = mutableMapOf<Pair<String, Long>, MutableList<Piece>>()
        for (i in possible) {
            checkActive()
            try {
                val head = cache[files[i].articles.first()].bytes
                if (!head.startsWith(StoredRar.signature) || head.getOrNull(6)?.toInt() != 1) continue
                var number = -1L
                val member = StoredRar.index(PostedFile(files[i], cache, false), passwords) { number = it }.singleOrNull() ?: continue
                if (number !in 0..4095 || (!member.more && !member.continues)) continue
                val key = member.name to (member.unpacked ?: continue)
                chains.getOrPut(key) { mutableListOf() }.add(Piece(i, number, member))
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

    private fun rarFiles(volumes: Sequence<UsenetFile>, passwords: RarPasswords, checkActive: () -> Unit): List<UsenetCandidate> {
        val candidates = mutableListOf<UsenetCandidate>()
        var pending = mutableListOf<RarPart>()
        for (file in volumes) {
            checkActive()
            for (part in StoredRar.index(file, passwords)) {
                ensureUsenet(part.continues == pending.isNotEmpty() &&
                    (!part.continues || pending.first().name == part.name), "Incomplete multipart RAR file.")
                pending.add(part)
                if (!part.more) {
                    val chain = pending
                    pending = mutableListOf()
                    val length = chain.sumOf { it.extent.length }
                    ensureUsenet(candidates.size < 8192, "Too many archived files.")
                    candidates += UsenetCandidate(part.name, length) {
                        ensureUsenet(chain.all { it.stored }, "This RAR video uses compression and cannot be streamed.")
                        val packed = StoredFile(part.name, chain.map { it.extent })
                        if (chain.none { it.encrypted }) packed else {
                            val encryption = chain.first().encryption ?: throw UsenetFailure("Unsupported encrypted RAR layout.")
                            val size = part.unpacked ?: throw UsenetFailure("Encrypted RAR file size is unknown.")
                            ensureUsenet(size in 1..packed.size && packed.size - size < 16 && packed.size % 16 == 0L &&
                                chain.all { encryption.sameCipher(it.encryption) && it.unpacked == size }, "Inconsistent encrypted RAR volumes.")
                            RarPlaintext(packed, 0, size, passwords.key(encryption), encryption.iv)
                        }
                    }
                }
            }
        }
        ensureUsenet(pending.isEmpty(), "NZB is missing the final RAR volume.")
        return candidates
    }

    private fun groupFiles(group: List<Pair<Volume, NzbFile>>, cache: ArticleCache,
                           passwords: RarPasswords, checkActive: () -> Unit): List<UsenetCandidate> {
        val sorted = group.sortedBy { it.first.number }
        val info = sorted.first().first
        // Some posters number .part files from zero.
        val first = if (info.kind == Kind.RAR && info.number == 0) 0 else info.first
        ensureUsenet(sorted.withIndex().all { (i, pair) -> pair.first.number == first + i && pair.first.first == info.first },
            "NZB archive volumes are missing or duplicated.")
        fun openVolume(index: Int, nextHeads: List<String>): UsenetFile {
            checkActive()
            return PostedFile(sorted[index].second, cache, verifyTail = false, nextHeads = nextHeads)
        }
        fun headAt(index: Int) = sorted.getOrNull(index)?.second?.articles?.firstOrNull()
        // For RAR the indexer may immediately seek within a volume. Consume each pair
        // of head replies before that seek. Joined files need every head in sequence.
        val files = if (info.kind == Kind.RAR) sequence {
            var i = 0
            while (i < sorted.size) {
                val segments = sorted[i].second.articles
                if (segments.size > 1) cache.prefetch(segments.last(), archive = true)
                val a = openVolume(i, listOfNotNull(headAt(i + 1)))
                val b = if (i + 1 < sorted.size) openVolume(i + 1, emptyList()) else null
                yield(a)
                if (b != null) yield(b)
                i += 2
            }
        } else sorted.indices.asSequence().map { i -> openVolume(i, listOfNotNull(headAt(i + 1), headAt(i + 2))) }
        val candidates = when (info.kind) {
            Kind.RAR -> rarFiles(files, passwords, checkActive)
            else -> {
                val joined = StoredFile(info.key, files.map { FileExtent(it, 0, it.size) }.toList())
                if (info.kind == Kind.SEVEN_ZIP) StoredSevenZip.index(joined)
                else listOf(UsenetCandidate(info.key, joined.size) { joined })
            }
        }
        // A single stored archive wrapped in another archive has an unambiguous member order.
        val inner = candidates.singleOrNull() ?: return candidates
        return when {
            inner.name.endsWith(".rar", true) -> rarFiles(sequenceOf(inner.open()), passwords, checkActive)
            inner.name.endsWith(".7z", true) -> StoredSevenZip.index(inner.open())
            else -> candidates
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
        val named = regroup(recover(files, cache, checkActive), cache, passwords, checkActive)
        val groups = named.mapNotNull { file -> volume(file.name)?.let { it to file } }
            .groupBy { it.first.key }.toMutableMap()
        val candidates = mutableListOf<UsenetCandidate>()
        var archiveError: UsenetFailure? = null
        for (file in named) {
            checkActive()
            val group = volume(file.name)
            if (group == null) candidates += UsenetCandidate(file.name, file.postedBytes) { PostedFile(file, cache) }
            else {
                val entries = groups.remove(group.key) ?: continue
                try { candidates += groupFiles(entries, cache, passwords, checkActive) }
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
            episode == null || features.isEmpty() || (hints.isNotEmpty() && features.size == 1) ->
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
