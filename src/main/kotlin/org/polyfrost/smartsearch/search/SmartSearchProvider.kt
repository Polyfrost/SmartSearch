package org.polyfrost.smartsearch.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.Term
import org.apache.lucene.search.*
import org.polyfrost.oneconfig.internal.ui.search.SearchCorpus
import org.polyfrost.oneconfig.internal.ui.search.SearchDocument
import org.polyfrost.oneconfig.internal.ui.search.SearchProvider
import org.polyfrost.oneconfig.internal.ui.search.SearchScope
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.index.DataStore
import org.polyfrost.smartsearch.index.toKey
import org.polyfrost.smartsearch.model.ModelController

private val WORD_TERMINATOR = Regex("[\\s_\\-.]+")

object SmartSearchProvider : SearchProvider {
    override val priority: Int = 1

    override fun isAvailable(): Boolean {
        return ModelController.isReady()
    }

    override fun <T> searchGrouped(
        query: String,
        scopes: Set<SearchScope>,
        grouper: (SearchDocument<*>) -> T
    ): Map<T, List<SearchDocument<*>>> {
        return search(query, scopes).groupBy(grouper)
    }

    override fun search(
        query: String,
        scopes: Set<SearchScope>
    ): List<SearchDocument<*>> {
        if (query.isBlank()) {
            return emptyList()
        }
        val scopeFilter = if (scopes.isEmpty()) null else scopeQuery(scopes)

        return DataStore.search { reader, searcher, analyzer ->
            val lexical = textQuery(analyzer, query)?.let {
                searcher.search(
                    filtered(it, scopeFilter),
                    SmartSearchConfig.maxLexicalResults
                ).scoreDocs.asList()
            }.orEmpty()

            val semantic = embed(query)?.let {
                searcher.search(
                    KnnFloatVectorQuery("embedding", it, SmartSearchConfig.maxKnnResults, scopeFilter),
                    SmartSearchConfig.maxKnnResults
                    // Hits come back descending, so the first one under the floor ends the useful part of the list.
                ).scoreDocs.takeWhile { hit -> hit.score >= SmartSearchConfig.minKnnScore }
            }.orEmpty()

            val storedFields = reader.storedFields()
            val corpus = SearchCorpus.corpus
            fuse(lexical to 1.0, semantic to semanticWeight(query)).mapNotNull { doc ->
                storedFields.document(doc, setOf("id")).get("id")?.let { corpus[it] }
            }
        }
    }

    override suspend fun onCorpusUpdate(
        added: List<SearchDocument<*>>,
        removed: Set<String>
    ) {
        DataStore.ingest(added)
    }

    /**
     * Merge using reciprocal rank fusion, with a weight assigned to each list
     */
    private fun fuse(vararg lists: Pair<List<ScoreDoc>, Double>): List<Int> {
        val scores = HashMap<Int, Double>()
        for ((list, weight) in lists) {
            list.forEachIndexed { rank, hit ->
                scores.merge(
                    hit.doc,
                    weight / (SmartSearchConfig.rankFusionDampening + rank + 1),
                    Double::plus
                )
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(SmartSearchConfig.maxResults).map { it.key }
    }

    /**
     * Weight of lexical vs semantic
     */
    private fun semanticWeight(query: String): Double {
        val words = query.trim().split(WORD_TERMINATOR).size
        val ramp = ((words - SmartSearchConfig.KnnWeightScalingStartWords).toDouble()
                / (SmartSearchConfig.maxKnnWeightWords - SmartSearchConfig.KnnWeightScalingStartWords))
            .coerceIn(0.0, 1.0)
        return SmartSearchConfig.minKnnWeight + ramp *
                (SmartSearchConfig.maxKnnWeight - SmartSearchConfig.minKnnWeight)
    }

    /** The query as a vector, or null if the model isn't available */
    private fun embed(query: String): FloatArray? {
        if (!ModelController.isReady()) return null
        return runCatching {
            ModelController.getModel().embed(
                ModelController.getQueryPrefix() + query
            ).content().vector()
        }.getOrNull()
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
    private fun textQuery(analyzer: Analyzer, queryText: String): Query? {
        val titleQuery = fieldQuery(analyzer, "title", queryText)
        val descQuery = fieldQuery(analyzer, "description", queryText)
        if (titleQuery == null && descQuery == null) return null

        return BooleanQuery.Builder().apply {
            titleQuery?.let {
                add(
                    BoostQuery(it, SmartSearchConfig.lexicalTitleBoost),
                    BooleanClause.Occur.SHOULD
                )
            }
            descQuery?.let { add(it, BooleanClause.Occur.SHOULD) }
            setMinimumNumberShouldMatch(1)
        }.build()
    }

    private fun fieldQuery(analyzer: Analyzer, field: String, queryText: String): Query? {
        val terms = analyzeToTerms(analyzer, field, queryText)
        if (terms.isEmpty()) return null

        // The user is still typing unless they ended on a separator, so the last token is treated as a prefix.
        val lastIsPartial = queryText.isNotEmpty() && !queryText.last().isWhitespace()

        if (terms.size == 1) {
            return termClause(field, terms[0], partial = lastIsPartial)
        }

        val builder = BooleanQuery.Builder()
        terms.forEachIndexed { index, term ->
            val partial = lastIsPartial && index == terms.lastIndex
            builder.add(termClause(field, term, partial), BooleanClause.Occur.SHOULD)
        }
        builder.setMinimumNumberShouldMatch(1)
        return builder.build()
    }

    /**
     * Create a clause for a term against a field, supports partial and fuzzy matching
     */
    private fun termClause(field: String, term: String, partial: Boolean): Query {
        val exact = TermQuery(Term(field, term))
        val builder = BooleanQuery.Builder()
            .add(BoostQuery(exact, SmartSearchConfig.lexicalExactBoost), BooleanClause.Occur.SHOULD)

        if (partial) {
            builder.add(PrefixQuery(Term(field, term)), BooleanClause.Occur.SHOULD)
        }
        // Below this length an edit covers too much of the word to stay meaningful.
        if (term.length >= SmartSearchConfig.lexicalMinFuzzyLength) {
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