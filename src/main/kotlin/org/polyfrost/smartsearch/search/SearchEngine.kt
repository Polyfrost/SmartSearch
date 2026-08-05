package org.polyfrost.smartsearch.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.Term
import org.apache.lucene.search.*
import org.polyfrost.oneconfig.internal.ui.search.SearchScope
import org.polyfrost.smartsearch.index.SearchIndex
import org.polyfrost.smartsearch.index.titleKey
import org.polyfrost.smartsearch.index.toKey

private val WORD_TERMINATOR = Regex("[\\s_\\-.]+")

/** Every indexed text field, in the order their boosts are listed in [SearchParams]. */
private val SEARCH_FIELDS = listOf("title", "description", "mod", "context", "tags")

object SearchEngine {

    /**
     * Ranked document ids for [query], best first, capped at [SearchParams.maxResults].
     *
     * [embedQuery] turns the query into a vector, or returns null when no model is loaded, then we fall back
     * to lexical search
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

    /** Merge the arms into one ranking, with a weight assigned to each list. */
    private fun fuse(params: SearchParams, vararg arms: Pair<List<ScoreDoc>, Double>): List<Int> {
        val scores = HashMap<Int, Double>()
        for ((hits, weight) in arms) {
            val contribution = contributions(params, hits)
            hits.forEachIndexed { rank, hit ->
                scores.merge(hit.doc, weight * contribution[rank], Double::plus)
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(params.maxResults).map { it.key }
    }

    /**
     * Calculate the weight of each entry
     *
     * [FusionMode.SCORE] normalises the score across the hits in the arm
     */
    private fun contributions(params: SearchParams, hits: List<ScoreDoc>): DoubleArray {
        if (params.fusion == FusionMode.RANK) {
            return DoubleArray(hits.size) { 1.0 / (params.rankFusionDampening + it + 1) }
        }

        val best = hits.firstOrNull()?.score ?: 0f
        val worst = hits.lastOrNull()?.score ?: 0f
        val span = best - worst
        // One hit, or a run of identical scores: nothing to tell them apart by, so they all count the same.
        if (span <= 1e-6f) return DoubleArray(hits.size) { 1.0 }
        return DoubleArray(hits.size) { ((hits[it].score - worst) / span).toDouble() }
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
        val terms = analyzeToTerms(analyzer, SEARCH_FIELDS.first(), queryText)
        if (terms.isEmpty()) return null

        val boosts = listOf(
            params.lexicalTitleBoost,
            1f,
            params.lexicalModBoost,
            params.lexicalContextBoost,
            params.lexicalTagBoost,
        )
        val fields = SEARCH_FIELDS.zip(boosts)
        // The user is still typing unless they ended on a separator, so the last token is treated as a prefix.
        val lastIsPartial = queryText.isNotEmpty() && !queryText.last().isWhitespace()

        val builder = BooleanQuery.Builder()
        terms.forEachIndexed { index, term ->
            val partial = lastIsPartial && index == terms.lastIndex
            builder.add(
                bestField(params, fields) { field -> termClause(field, term, partial, params) },
                BooleanClause.Occur.SHOULD,
            )
        }
        bestFieldOrNull(params, fields) { field -> phraseClause(field, terms, lastIsPartial, params) }
            ?.let { builder.add(it, BooleanClause.Occur.SHOULD) }
        titleStartClause(queryText, params)?.let { builder.add(it, BooleanClause.Occur.SHOULD) }

        builder.setMinimumNumberShouldMatch(1)
        return builder.build()
    }

    /**
     * Reward options where the title begins with was typed.
     * Helps with accuracy when starting to type a title.
     */
    private fun titleStartClause(queryText: String, params: SearchParams): Query? {
        if (params.lexicalTitleStartBoost <= 0f) return null
        val key = titleKey(queryText)
        if (key.isEmpty()) return null
        return BoostQuery(PrefixQuery(Term("title_key", key)), params.lexicalTitleStartBoost)
    }

    private fun bestField(
        params: SearchParams,
        fields: List<Pair<String, Float>>,
        clause: (String) -> Query,
    ): Query = DisjunctionMaxQuery(
        fields.map { (field, boost) -> BoostQuery(clause(field), boost) },
        params.lexicalFieldTieBreak,
    )

    private fun bestFieldOrNull(
        params: SearchParams,
        fields: List<Pair<String, Float>>,
        clause: (String) -> Query?,
    ): Query? {
        val clauses = fields.mapNotNull { (field, boost) -> clause(field)?.let { BoostQuery(it, boost) } }
        if (clauses.isEmpty()) return null
        return DisjunctionMaxQuery(clauses, params.lexicalFieldTieBreak)
    }

    /**
     * Rewards documents where the query's words appear together and in order.
     */
    private fun phraseClause(field: String, terms: List<String>, lastIsPartial: Boolean, params: SearchParams): Query? {
        if (params.lexicalPhraseBoost <= 0f) return null
        // Last word is not complete so doesn't take part in the sentence
        val phraseTerms = if (lastIsPartial) terms.dropLast(1) else terms
        if (phraseTerms.size < 2) return null

        val phrase = PhraseQuery.Builder().apply {
            setSlop(params.lexicalPhraseSlop)
            phraseTerms.forEach { add(Term(field, it)) }
        }.build()
        return BoostQuery(phrase, params.lexicalPhraseBoost)
    }

    /**
     * Create a clause for a term against a field, supports partial and fuzzy matching
     *
     * The three clauses overlap, so the boost if all 3 hit shouldn't be astronomical
     */
    private fun termClause(field: String, term: String, partial: Boolean, params: SearchParams): Query {
        val exact = TermQuery(Term(field, term))
        val builder = BooleanQuery.Builder()
            .add(BoostQuery(exact, params.lexicalExactBoost), BooleanClause.Occur.SHOULD)

        if (partial) {
            // Score the expansion of what the user could be typing instead, since PrefixQuery is constant score
            builder.add(
                PrefixQuery(
                    Term(field, term),
                    MultiTermQuery.TopTermsScoringBooleanQueryRewrite(params.lexicalPrefixExpansions)
                ),
                BooleanClause.Occur.SHOULD,
            )
        }
        // Below this length an edit covers too much of the word to stay meaningful.
        if (term.length >= params.lexicalMinFuzzyLength) {
            builder.add(
                FuzzyQuery(Term(field, term), 1, params.lexicalFuzzyPrefixLength),
                BooleanClause.Occur.SHOULD,
            )
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
