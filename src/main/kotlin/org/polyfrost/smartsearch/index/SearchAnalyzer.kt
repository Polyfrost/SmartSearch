package org.polyfrost.smartsearch.index

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.FlattenGraphFilter
import org.apache.lucene.analysis.en.EnglishMinimalStemFilter
import org.apache.lucene.analysis.en.EnglishPossessiveFilter
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter
import org.apache.lucene.analysis.standard.StandardTokenizer

/**
 * Splits a joined name (like OverflowParticles) into split, searchable words
 */
private const val DELIMITER_FLAGS = WordDelimiterGraphFilter.GENERATE_WORD_PARTS or
        WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS or
        WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE or
        WordDelimiterGraphFilter.SPLIT_ON_NUMERICS or
        WordDelimiterGraphFilter.PRESERVE_ORIGINAL

/**
 * How option/query text is cut into searchable words.
 */
class SearchAnalyzer(private val splitCompounds: Boolean) : Analyzer() {

    override fun createComponents(fieldName: String): TokenStreamComponents {
        val tokenizer = StandardTokenizer()
        var stream: TokenStream = tokenizer
        if (splitCompounds) {
            // Before lowercasing, or there is no case left to split on.
            stream = WordDelimiterGraphFilter(stream, DELIMITER_FLAGS, null)
            stream = FlattenGraphFilter(stream)
        }
        stream = LowerCaseFilter(stream)
        stream = ASCIIFoldingFilter(stream)
        stream = EnglishPossessiveFilter(stream)
        stream = EnglishMinimalStemFilter(stream)
        return TokenStreamComponents(tokenizer, stream)
    }
}
