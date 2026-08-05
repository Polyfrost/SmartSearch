package org.polyfrost.smartsearch.search

enum class FusionMode {
    /** Reciprocal rank fusion */
    RANK,

    /** Each arm normalized between own best/worst hits, then added together. */
    SCORE,
}

/**
 * All parameters available for search
 */
data class SearchParams(
    val maxResults: Int = 20,
    val fusion: FusionMode = FusionMode.SCORE,
    val rankFusionDampening: Float = 60f,
    val maxLexicalResults: Int = 100,
    val lexicalTitleBoost: Float = 2f,
    val lexicalModBoost: Float = 1f,
    val lexicalContextBoost: Float = 0.7f,
    val lexicalTagBoost: Float = 1f,
    val lexicalFieldTieBreak: Float = 0f,
    val lexicalTitleStartBoost: Float = 8f,
    val lexicalExactBoost: Float = 0.5f,
    val lexicalPhraseBoost: Float = 3f,
    val lexicalPhraseSlop: Int = 2,
    val lexicalMinFuzzyLength: Int = 4,
    val lexicalFuzzyPrefixLength: Int = 0,
    val lexicalPrefixExpansions: Int = 64,
    /** Lexical hits below this are dropped before fusion */
    val lexicalScoreFloor: Float = 0.4f,
    val maxKnnResults: Int = 100,
    val minKnnScore: Float = 0.8f,
    val knnWeightScalingStartWords: Int = 2,
    val maxKnnWeightWords: Int = 6,
    val minKnnWeight: Float = 0.25f,
    val maxKnnWeight: Float = 2f,
    /** Fused results below this are dropped. */
    val resultScoreFloor: Float = 0.4f,
) {
    companion object {
        val DEFAULT = SearchParams()
    }
}
