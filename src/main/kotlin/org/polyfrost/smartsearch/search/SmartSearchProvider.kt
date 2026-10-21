@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.smartsearch.search

import kotlinx.coroutines.runBlocking
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.search.SearchDocument
import org.polyfrost.oneconfig.api.ui.v1.search.SearchProvider
import org.polyfrost.oneconfig.api.ui.v1.search.SearchScope
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.index.DataStore
import org.polyfrost.smartsearch.index.SearchIndex
import org.polyfrost.smartsearch.index.Embedder
import org.polyfrost.smartsearch.model.ModelController

object SmartSearchProvider : SearchProvider() {
    override val priority: Int = 1

    override fun isAvailable(): Boolean {
        return DataStore.status == SearchIndex.Status.READY && SmartSearchConfig.enabled
    }

    override fun search(
        query: String,
        scopes: Set<SearchScope>
    ): List<String> {
        return SearchEngine.rank(DataStore, query, scopes, SmartSearchConfig.params, ::embed)
    }

    override fun onCorpusUpdate(
        added: List<SearchDocument>,
        removed: Set<String>
    ) {
        Embedder.dropQueued(removed)
        Embedder.queueEmbedding(runBlocking { DataStore.ingest(added) })
        DataStore.updateTrackedStaleEntries()
    }

    /** The query as a vector, or null if the model isn't available */
    private fun embed(query: String): FloatArray? {
        if (!ModelController.isReady() || !SmartSearchConfig.enableSemantic) return null
        return runCatching {
            ModelController.getModel().embed(
                ModelController.getQueryPrefix() + query
            ).content().vector()
        }.onFailure { e ->
            SmartSearchClient.LOGGER.error("Error while searching $query", e)
        }.getOrNull()
    }
}
