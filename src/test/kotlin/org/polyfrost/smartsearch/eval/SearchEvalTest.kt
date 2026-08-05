package org.polyfrost.smartsearch.eval

import org.polyfrost.smartsearch.search.SearchParams
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scores our ranking in some simple tests
 */
class SearchEvalTest {

    @Test
    fun judgmentsPointAtRealDocuments() {
        val missing = QuerySet.queries
            .flatMap { query -> query.relevant.map { query to it } }
            .filter { (_, id) -> id !in EvalCorpus.byId }
        assertTrue(missing.isEmpty(), "judgments reference unknown document ids: ${missing.map { it.second }}")

        val outOfScope = QuerySet.queries.flatMap { query ->
            query.relevant.mapNotNull { id ->
                val doc = EvalCorpus.byId.getValue(id)
                if (query.scopes.any { it in doc.scopes }) null else "${query.text} -> $id"
            }
        }
        assertTrue(outOfScope.isEmpty(), "judgments expect documents outside the searched scope: $outOfScope")
    }

    @Test
    fun evaluate() {
        val results = SearchEvaluation.run(QuerySet.queries, SearchParams.DEFAULT)
        val report = buildString {
            appendLine("Evaluating ${EvalCorpus.documents.size} documents, ${QuerySet.queries.size} queries")
            appendLine()
            append(SearchEvaluation.report(results))
            appendLine()
            listOf("title-exact", "title-subset", "mod-qualified", "description", "mod-part", "curated", "curated-short").forEach {
                appendLine(SearchEvaluation.failures(results, it))
            }
        }
        println(report)
        Path("build/eval").createDirectories()
        Path("build/eval/report.txt").writeText(report)

        // Floors sit a little under the numbers measured when the harness was written, so ordinary noise passes and
        // a real regression does not. Raise them as the ranking improves.
        val overall = Metrics.score(results)
        assertTrue(overall.empty < 0.02, "too many queries returned nothing: ${overall.empty}")
        assertTrue(overall.mrr > 0.70, "overall MRR regressed: ${overall.mrr}")

        val exact = Metrics.score(results.filter { it.query.family == "title-exact" })
        assertTrue(exact.hitAt1 > 0.78, "searching an option's exact title should almost always win: ${exact.hitAt1}")
        assertTrue(exact.recall > 0.90, "exact titles should essentially always be found: ${exact.recall}")
    }
}
