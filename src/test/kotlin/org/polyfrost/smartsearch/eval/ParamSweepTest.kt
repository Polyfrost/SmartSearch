package org.polyfrost.smartsearch.eval

import org.polyfrost.smartsearch.search.SearchParams
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * One-knob-at-a-time sweeps over the ranking parameters, written to `build/eval/sweep.txt`.
 *
 * Off by default because it re-scores the query set once per value; enable with
 * `./gradlew test --tests '*ParamSweepTest*' -Deval.sweep=true -i`.
 */
class ParamSweepTest {

    @Test
    fun sweep() {
        if (System.getProperty("eval.sweep") != "true") {
            println("[eval] sweep skipped; pass -Deval.sweep=true to run it")
            return
        }

        val base = SearchParams.DEFAULT
        val report = buildString {
            appendLine("Parameter sweep over ${QuerySet.queries.size} queries. Baseline first.")
            appendLine()
            appendLine(scoreLine("baseline", base))
            appendLine()

            sweep("rankFusionDampening", listOf(1f, 10f, 30f, 60f, 120f)) { base.copy(rankFusionDampening = it) }
            sweep("minKnnScore", listOf(0.0f, 0.5f, 0.62f, 0.7f, 0.8f)) { base.copy(minKnnScore = it) }
            sweep("maxKnnWeight", listOf(0.5f, 1.0f, 1.4f, 2.0f, 3.0f)) { base.copy(maxKnnWeight = it) }
            sweep("minKnnWeight", listOf(0.0f, 0.5f, 1.0f, 1.5f)) { base.copy(minKnnWeight = it) }
            sweep("lexicalTitleBoost", listOf(1f, 2f, 3f)) { base.copy(lexicalTitleBoost = it) }
            sweep("lexicalExactBoost", listOf(1f, 2f, 3f)) { base.copy(lexicalExactBoost = it) }
            sweep("lexicalMinFuzzyLength", listOf(3, 4, 6, 20)) { base.copy(lexicalMinFuzzyLength = it) }
            sweep("candidatePool", listOf(20, 50, 100, 200)) {
                base.copy(maxLexicalResults = it, maxKnnResults = it)
            }
        }

        println(report)
        Path("build/eval").createDirectories()
        Path("build/eval/sweep.txt").writeText(report)
    }

    private fun <T> StringBuilder.sweep(name: String, values: List<T>, build: (T) -> SearchParams) {
        appendLine("## $name")
        values.forEach { appendLine(scoreLine("  $name=$it", build(it))) }
        appendLine()
    }

    private fun scoreLine(label: String, params: SearchParams): String =
        Metrics.score(SearchEvaluation.run(QuerySet.queries, params)).format(label)
}
