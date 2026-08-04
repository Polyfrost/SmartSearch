package org.polyfrost.smartsearch.config

import org.polyfrost.oneconfig.api.config.v1.Config
import org.polyfrost.oneconfig.api.config.v1.Properties
import org.polyfrost.oneconfig.api.config.v1.Tree
import org.polyfrost.oneconfig.api.config.v1.annotations.Button
import org.polyfrost.oneconfig.api.config.v1.annotations.Number
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch
import org.polyfrost.smartsearch.index.DataStore
import org.polyfrost.smartsearch.ui.IndexerStatusVisualizer
import org.polyfrost.smartsearch.util.DocumentExporter

object SmartSearchConfig : Config(
    "smartsearch.json",
    "SmartSearch",
    Category.OTHER
) {
    @Switch(
        title = "Enabled"
    )
    var enabled: Boolean = true

    @Button(
        title = "Clean database",
    )
    fun cleanDb() {
        DataStore.clean()
    }

    @Number(
        title = "Maximum Results",
        description = "The maximum amount of search results",
        category = "Advanced",
        subcategory = "General",
        min = 1f,
        max = 200f
    )
    var maxResults: Int = 20

    @Number(
        title = "Rank fusion dampening",
        description = "The amount of dampening applied when merging results, higher values flattens the ranking, " +
                "lower lets top results dominate.",
        category = "Advanced",
        subcategory = "General",
        min = 1f,
        max = 200f
    )
    var rankFusionDampening = 60f

    @Number(
        title = "Maximum Lexical Results",
        description = "Amount of results the lexical search pulls before fusion",
        category = "Advanced",
        subcategory = "Lexical",
        min = 1f,
        max = 200f
    )
    var maxLexicalResults: Int = 20

    @Number(
        title = "Lexical Title Boost",
        description = "Boost in lexical search if the match is found in the title",
        category = "Advanced",
        subcategory = "Lexical",
        min = 1f,
        max = 3f
    )
    var lexicalTitleBoost: Float = 2f

    @Number(
        title = "Lexical Exact Match Boost",
        description = "Boost in lexical search if there is an exact match",
        category = "Advanced",
        subcategory = "Lexical",
        min = 1f,
        max = 3f
    )
    var lexicalExactBoost: Float = 2f

    @Number(
        title = "Minimum Length for Fuzzy Search",
        description = "Minimum length of the search query before fuzzy search is used, " +
                "shorter queries will surface a lot of junk with fuzzy search.",
        category = "Advanced",
        subcategory = "Lexical",
        min = 1f,
        max = 20f
    )
    var lexicalMinFuzzyLength: Int = 4

    @Number(
        title = "Maximum Semantic Results",
        description = "Amount of results the semantic (KNN) vector search pulls before fusion.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 1f,
        max = 200f
    )
    var maxKnnResults: Int = 20

    @Number(
        title = "Minimum Semantic Score",
        description = "The minimum score for semantic (KNN) vector search results.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 0f,
        max = 1f,
    )
    var minKnnScore: Float = 0.62f

    @Number(
        title = "Words before weight scaling starts",
        description = "The amount of words needed before the weight scaling of semantic results start.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 1f,
        max = 10f,
    )
    var KnnWeightScalingStartWords: Int = 3

    @Number(
        title = "Words for maximum semantic weight",
        description = "The weight of the semantic search depends on the length of the search query, " +
                "this option decides the amount of words before the maximum weight is reached.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 1f,
        max = 10f,
    )
    var maxKnnWeightWords: Int = 5

    @Number(
        title = "Minimum Semantic Weight",
        description = "The minimum weight attached to semantic search results.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 0f,
        max = 3f,
    )
    var minKnnWeight: Float = 1f

    @Number(
        title = "Maximum Semantic Weight",
        description = "The minimum weight attached to semantic search results.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 0f,
        max = 3f,
    )
    var maxKnnWeight: Float = 1.4f

    @Button(
        title = "Export Search Documents",
        description = "Export all currently loaded search documents, useful for benchmarking.",
        category = "Advanced",
        subcategory = "Misc"
    )
    fun exportSearchDocuments() {
        DocumentExporter.export()
    }

    override fun makeTree(): Tree {
        // Put at the top
        val collected = super.makeTree()
        val reordered = Tree(collected.id, collected.title, collected.description, null)

        // Add status visualizer
        reordered.put(Properties.dummy("indexerStatus", "Search index", "The state of the embedding model and the search index.").apply {
            addMetadata("visualizer", IndexerStatusVisualizer)
            addMetadata("category", "General")
            addMetadata("subcategory", "General")
        })
        collected.map.values.forEach(reordered::put)
        collected.metadata?.let { reordered.addMetadata(HashMap(it)) }

        return reordered
    }
}
