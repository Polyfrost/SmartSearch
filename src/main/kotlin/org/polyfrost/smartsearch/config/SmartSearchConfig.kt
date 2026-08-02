package org.polyfrost.smartsearch.config

import org.polyfrost.oneconfig.api.config.v1.Config
import org.polyfrost.oneconfig.api.config.v1.annotations.Button
import org.polyfrost.oneconfig.api.config.v1.annotations.Number
import org.polyfrost.smartsearch.index.Indexer

object SmartSearchConfig : Config(
    "polysearch.json",
    "PolySearch",
    Category.OTHER
) {
    @Number(
        title = "Minimum Score",
        description = "The minimum score for search results",
        min = 0f,
        max = 1f,
    )
    var minScore: Float = 0.62f

    @Number(
        title = "Maximum Less Then Next",
        description = "The maximum score an option can have less then the top ranking result in the next group.",
        min = 0f,
        max = 1f,
    )
    var maxLessThenNext: Float = 0.05f

    @Button(
        title = "Clear indexes"
    )
    fun clearIndex() {
        Indexer.clear()
    }
}