package com.nuvio.tv.livetv.data

import java.text.Normalizer
import java.util.Locale

/** Remove redundant OR phrases without changing the set of matching FTS documents. */
internal object SportsSearchVocabulary {
    private val asciiTokens=Regex("[a-z0-9]+")
    fun minimalCover(words: Collection<String>): List<String> {
        val phrases=words.map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }.distinct()
        val covered=HashSet<String>()
        val ascii=phrases.filter { phrase -> phrase.all { it.code<128 } }
            .map { it to asciiTokens.findAll(it.lowercase(Locale.ROOT)).map { m -> m.value }.toList() }
            .sortedBy { it.second.size }
        val result=ArrayList<String>()
        for((phrase,tokens) in ascii) {
            if(tokens.indices.any { start -> (start+1..tokens.size).any { end ->
                    tokens.subList(start,end).joinToString(" ") in covered } }) continue
            covered+=tokens.joinToString(" ");result+=phrase
        }
        // Keep non-ASCII spellings verbatim: compatibility normalization and FTS have different rules.
        result+=phrases.filter { phrase -> phrase.any { it.code>=128 } }
        return result
    }
    fun compatibilityWords(term:String): List<String> =
        asciiTokens.findAll(Normalizer.normalize(term,Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)).map { it.value }.toList()
}
