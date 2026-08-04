package org.polyfrost.smartsearch.eval

import kotlin.math.ln
import kotlin.math.min

/** Aggregate quality over one set of queries. All rates are in 0..1. */
data class Scores(
    val queries: Int,
    /** Fraction of queries whose top result is relevant. What "search feels right" mostly means. */
    val hitAt1: Double,
    val hitAt3: Double,
    val hitAt5: Double,
    /** Fraction of queries where a relevant document appears anywhere in the returned results. */
    val recall: Double,
    /** Mean reciprocal rank: 1.0 if always first, 0.5 if always second, 0 if never found. */
    val mrr: Double,
    val ndcgAt10: Double,
    /** Fraction of queries that returned nothing at all. */
    val empty: Double,
    val medianLatencyMs: Long,
) {
    fun format(label: String): String = "%-14s n=%-5d P@1=%.3f  H@3=%.3f  H@5=%.3f  MRR=%.3f  nDCG@10=%.3f  recall=%.3f  empty=%.3f  %dms"
        .format(label, queries, hitAt1, hitAt3, hitAt5, mrr, ndcgAt10, recall, empty, medianLatencyMs)
}

data class QueryResult(
    val query: EvalQuery,
    val ranked: List<String>,
    val latencyMs: Long,
) {
    /** 1-based rank of the first relevant result, or null if none was returned. */
    val firstRelevantRank: Int? =
        ranked.indexOfFirst { it in query.relevant }.takeIf { it >= 0 }?.plus(1)

    fun hitAt(k: Int): Boolean = firstRelevantRank != null && firstRelevantRank <= k
}

object Metrics {

    fun score(results: List<QueryResult>): Scores {
        require(results.isNotEmpty()) { "no results to score" }
        val n = results.size.toDouble()
        return Scores(
            queries = results.size,
            hitAt1 = results.count { it.hitAt(1) } / n,
            hitAt3 = results.count { it.hitAt(3) } / n,
            hitAt5 = results.count { it.hitAt(5) } / n,
            recall = results.count { it.firstRelevantRank != null } / n,
            mrr = results.sumOf { r -> r.firstRelevantRank?.let { 1.0 / it } ?: 0.0 } / n,
            ndcgAt10 = results.sumOf { ndcgAt10(it) } / n,
            empty = results.count { it.ranked.isEmpty() } / n,
            medianLatencyMs = results.map { it.latencyMs }.sorted()[results.size / 2],
        )
    }

    private fun ndcgAt10(result: QueryResult): Double {
        val dcg = result.ranked.take(10).withIndex().sumOf { (index, id) ->
            if (id in result.query.relevant) 1.0 / log2(index + 2.0) else 0.0
        }
        val ideal = (0 until min(result.query.relevant.size, 10)).sumOf { 1.0 / log2(it + 2.0) }
        return if (ideal == 0.0) 0.0 else dcg / ideal
    }

    private fun log2(value: Double) = ln(value) / ln(2.0)
}
