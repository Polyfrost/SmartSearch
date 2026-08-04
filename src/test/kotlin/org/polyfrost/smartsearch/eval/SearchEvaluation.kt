package org.polyfrost.smartsearch.eval

import org.polyfrost.smartsearch.search.SearchEngine
import org.polyfrost.smartsearch.search.SearchParams

/** Runs a judged query set through the real ranking code and scores it. */
object SearchEvaluation {

    /**
     * Query vectors are cached across runs
     */
    private val queryVectors = HashMap<String, FloatArray>()

    fun run(queries: List<EvalQuery>, params: SearchParams): List<QueryResult> {
        warmQueryVectors(queries)
        return queries.map { query ->
            val start = System.nanoTime()
            val ranked = SearchEngine.rank(
                index = EvalIndex.index,
                query = query.text,
                scopes = query.scopes,
                params = params,
                embedQuery = { queryVectors[it] },
            )
            QueryResult(query, ranked, (System.nanoTime() - start) / 1_000_000)
        }
    }

    private fun warmQueryVectors(queries: List<EvalQuery>) {
        val missing = queries.map { it.text }.distinct().filter { it !in queryVectors }
        if (missing.isEmpty()) return
        println("[eval] embedding ${missing.size} queries")
        missing.forEach { queryVectors[it] = EvalModel.embedQuery(it) }
    }

    /** Per-family scores plus an overall row, formatted as a table. */
    fun report(results: List<QueryResult>): String = buildString {
        val byFamily = results.groupBy { it.query.family }
        appendLine(Metrics.score(results).format("OVERALL"))
        appendLine()
        byFamily.entries.sortedBy { it.key }.forEach { (family, familyResults) ->
            appendLine(Metrics.score(familyResults).format(family))
        }
    }

    /** The worst results in a family, for eyeballing what is actually going wrong. */
    fun failures(results: List<QueryResult>, family: String, limit: Int = 10): String = buildString {
        val failed = results.filter { it.query.family == family && !it.hitAt(3) }
        appendLine("$family: ${failed.size} of ${results.count { it.query.family == family }} missed top 3")
        failed.take(limit).forEach { result ->
            val expected = result.query.relevant.first()
            val title = EvalCorpus.byId[expected]?.metadata?.title
            val mod = EvalCorpus.byId[expected]?.metadata?.modTitle
            appendLine("  %-45s -> rank %s, want %s [%s]".format(
                result.query.text.take(45),
                result.firstRelevantRank?.toString() ?: "none",
                title,
                mod,
            ))
        }
    }
}
