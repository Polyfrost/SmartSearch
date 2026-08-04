package org.polyfrost.smartsearch.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.Term
import org.apache.lucene.search.*
import org.polyfrost.oneconfig.internal.ui.search.SearchScope
import org.polyfrost.smartsearch.index.SearchIndex
import org.polyfrost.smartsearch.index.toKey

private val WORD_TERMINATOR = Regex("[\\s_\\-.]+")

/**
 * Ranking, with nothing attached to the running game.
 *
 * [SmartSearchProvider] is the game-facing wrapper; everything that decides *order* lives here so the evaluation
 * harness scores exactly the code that ships.
 */
object SearchEngine {

    /**
     * Ranked document ids for [query], best first, capped at [SearchParams.maxResults].
     *
     * [embedQuery] turns the query into a vector, or returns null when no model is loaded - in which case the search
     * degrades to lexical only rather than failing.
     */
    fun rank(
        index: SearchIndex,
        query: String,
        scopes: Set<SearchScope>,
        params: SearchParams = SearchParams.DEFAULT,
        embedQuery: (String) -> FloatArray?,
    ): List<String> {
        if (query.isBlank()) return emptyList()
        val scopeFilter = if (scopes.isEmpty()) null else scopeQuery(scopes)

        return index.search { reader, searcher, analyzer ->
            val lexical = textQuery(analyzer, query, params)?.let {
                searcher.search(
                    filtered(it, scopeFilter),
                    params.maxLexicalResults
                ).scoreDocs.asList()
            }.orEmpty()

            val semantic = embedQuery(query)?.let {
                searcher.search(
                    KnnFloatVectorQuery("embedding", it, params.maxKnnResults, scopeFilter),
                    params.maxKnnResults
                    // Hits come back descending, so the first one under the floor ends the useful part of the list.
                ).scoreDocs.takeWhile { hit -> hit.score >= params.minKnnScore }
            }.orEmpty()

            val storedFields = reader.storedFields()
            fuse(params, lexical to 1.0, semantic to semanticWeight(query, params)).mapNotNull { doc ->
                storedFields.document(doc, setOf("id")).get("id")
            }
        }
    }

    /**
     * Merge using reciprocal rank fusion, with a weight assigned to each list
     */
    private fun fuse(params: SearchParams, vararg lists: Pair<List<ScoreDoc>, Double>): List<Int> {
        val scores = HashMap<Int, Double>()
        for ((list, weight) in lists) {
            list.forEachIndexed { rank, hit ->
                scores.merge(
                    hit.doc,
                    weight / (params.rankFusionDampening + rank + 1),
                    Double::plus
                )
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(params.maxResults).map { it.key }
    }

    /**
     * Weight of lexical vs semantic
     */
    private fun semanticWeight(query: String, params: SearchParams): Double {
        val words = query.trim().split(WORD_TERMINATOR).size
        val ramp = ((words - params.knnWeightScalingStartWords).toDouble()
                / (params.maxKnnWeightWords - params.knnWeightScalingStartWords))
            .coerceIn(0.0, 1.0)
        return params.minKnnWeight + ramp * (params.maxKnnWeight - params.minKnnWeight)
    }

    private fun filtered(query: Query, filter: Query?): Query = if (filter == null) query else {
        BooleanQuery.Builder()
            .add(query, BooleanClause.Occur.MUST)
            .add(filter, BooleanClause.Occur.FILTER)
            .build()
    }

    /** Matches documents carrying at least one of [scopes]. */
    private fun scopeQuery(scopes: Set<SearchScope>): Query {
        val builder = BooleanQuery.Builder()
        for (scope in scopes) {
            builder.add(TermQuery(Term("scope", scope.toKey())), BooleanClause.Occur.SHOULD)
        }
        builder.setMinimumNumberShouldMatch(1)
        return builder.build()
    }

    /** Matches [queryText] against the indexed text, or null when it analyzes to nothing searchable. */
    private fun textQuery(analyzer: Analyzer, queryText: String, params: SearchParams): Query? {
        val titleQuery = fieldQuery(analyzer, "title", queryText, params)
        val descQuery = fieldQuery(analyzer, "description", queryText, params)
        if (titleQuery == null && descQuery == null) return null

        return BooleanQuery.Builder().apply {
            titleQuery?.let {
                add(
                    BoostQuery(it, params.lexicalTitleBoost),
                    BooleanClause.Occur.SHOULD
                )
            }
            descQuery?.let { add(it, BooleanClause.Occur.SHOULD) }
            setMinimumNumberShouldMatch(1)
        }.build()
    }

    private fun fieldQuery(analyzer: Analyzer, field: String, queryText: String, params: SearchParams): Query? {
        val terms = analyzeToTerms(analyzer, field, queryText)
        if (terms.isEmpty()) return null

        // The user is still typing unless they ended on a separator, so the last token is treated as a prefix.
        val lastIsPartial = queryText.isNotEmpty() && !queryText.last().isWhitespace()

        if (terms.size == 1) {
            return termClause(field, terms[0], partial = lastIsPartial, params = params)
        }

        val builder = BooleanQuery.Builder()
        terms.forEachIndexed { index, term ->
            val partial = lastIsPartial && index == terms.lastIndex
            builder.add(termClause(field, term, partial, params), BooleanClause.Occur.SHOULD)
        }
        builder.setMinimumNumberShouldMatch(1)
        return builder.build()
    }

    /**
     * Create a clause for a term against a field, supports partial and fuzzy matching
     */
    private fun termClause(field: String, term: String, partial: Boolean, params: SearchParams): Query {
        val exact = TermQuery(Term(field, term))
        val builder = BooleanQuery.Builder()
            .add(BoostQuery(exact, params.lexicalExactBoost), BooleanClause.Occur.SHOULD)

        if (partial) {
            builder.add(PrefixQuery(Term(field, term)), BooleanClause.Occur.SHOULD)
        }
        // Below this length an edit covers too much of the word to stay meaningful.
        if (term.length >= params.lexicalMinFuzzyLength) {
            builder.add(FuzzyQuery(Term(field, term), 1, 1), BooleanClause.Occur.SHOULD)
        } else if (!partial) {
            return exact
        }

        builder.setMinimumNumberShouldMatch(1)
        return builder.build()
    }

    private fun analyzeToTerms(analyzer: Analyzer, field: String, text: String): List<String> {
        val terms = mutableListOf<String>()
        val tokenStream: TokenStream = analyzer.tokenStream(field, text)
        val attr = tokenStream.addAttribute(CharTermAttribute::class.java)

        tokenStream.reset()
        while (tokenStream.incrementToken()) {
            terms.add(attr.toString())
        }
        tokenStream.end()
        tokenStream.close()

        return terms
    }
}
