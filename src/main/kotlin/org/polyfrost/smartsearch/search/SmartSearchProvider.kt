package org.polyfrost.smartsearch.search

import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import org.polyfrost.oneconfig.internal.ui.components.asRenderText
import org.polyfrost.oneconfig.internal.ui.search.ModResult
import org.polyfrost.oneconfig.internal.ui.search.OptionResult
import org.polyfrost.oneconfig.internal.ui.search.SearchProvider
import org.polyfrost.oneconfig.internal.ui.search.SearchResult
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.index.Indexer
import org.polyfrost.smartsearch.model.ModelController

object SmartSearchProvider : SearchProvider {
    override val priority: Int = 1

    override fun isAvailable(): Boolean {
        return ModelController.isReady()
    }

    override fun performSearch(query: String): Map<String, List<SearchResult>> {
        if (query.isBlank()) {
            return emptyMap()
        }
        Indexer.index()

        val model = ModelController.getModel()
        val embeddedQuery = model.embed(query).content()
        val searchRequest = EmbeddingSearchRequest.builder().queryEmbedding(embeddedQuery).maxResults(100).build()
        val grouped = Indexer.embeddings.search(searchRequest).matches()
            .filter { it.score() >= SmartSearchConfig.minScore }
            .sortedByDescending { it.score() }
            .groupBy { match ->
                when (val res = match.embedded()) {
                    is OptionResult -> res.modTitle.asRenderText()
                    is ModResult -> "Mods"
                    else -> "Unknown"
                }
            }

        val groupMaxScores = grouped.values.map { it.first().score() }

        return grouped.entries.mapIndexed { index, (key, matches) ->
            val cutoff = groupMaxScores.getOrNull(index + 1)?.minus(SmartSearchConfig.maxLessThenNext)
            val kept = if (cutoff == null) matches else matches.filter { it.score() >= cutoff }
            key to kept.map { it.embedded() }
        }.toMap().filter { it.value.isNotEmpty() }
    }
}