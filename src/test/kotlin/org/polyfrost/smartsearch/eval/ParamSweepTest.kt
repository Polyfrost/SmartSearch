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

            sweep("minKnnScore", listOf(0.73f, 0.74f, 0.75f, 0.76f, 0.77f, 0.78f, 0.79f, 0.8f)) { base.copy(minKnnScore = it) }
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
