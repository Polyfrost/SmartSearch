package org.polyfrost.smartsearch.search

/**
 * All parameters available for search
 */
data class SearchParams(
    val maxResults: Int = 20,
    val rankFusionDampening: Float = 60f,
    val maxLexicalResults: Int = 100,
    val lexicalTitleBoost: Float = 2f,
    val lexicalExactBoost: Float = 2f,
    val lexicalMinFuzzyLength: Int = 4,
    val maxKnnResults: Int = 100,
    val minKnnScore: Float = 0.62f,
    val knnWeightScalingStartWords: Int = 2,
    val maxKnnWeightWords: Int = 5,
    val minKnnWeight: Float = 1f,
    val maxKnnWeight: Float = 1.4f,
) {
    companion object {
        val DEFAULT = SearchParams()
    }
}
