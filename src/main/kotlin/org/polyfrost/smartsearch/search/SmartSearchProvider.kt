package org.polyfrost.smartsearch.search

import org.polyfrost.oneconfig.internal.ui.search.SearchCorpus
import org.polyfrost.oneconfig.internal.ui.search.SearchDocument
import org.polyfrost.oneconfig.internal.ui.search.SearchProvider
import org.polyfrost.oneconfig.internal.ui.search.SearchScope
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.index.DataStore
import org.polyfrost.smartsearch.index.SearchIndex
import org.polyfrost.smartsearch.index.Embedder
import org.polyfrost.smartsearch.model.ModelController

object SmartSearchProvider : SearchProvider {
    override val priority: Int = 1

    override fun isAvailable(): Boolean {
        return ModelController.isReady() && DataStore.status == SearchIndex.Status.READY
                && !Embedder.isRunning() && SmartSearchConfig.enabled
    }

    override fun <T> searchGrouped(
        query: String,
        scopes: Set<SearchScope>,
        grouper: (SearchDocument<*>) -> T
    ): Map<T, List<SearchDocument<*>>> {
        return search(query, scopes).groupBy(grouper)
    }

    override fun search(
        query: String,
        scopes: Set<SearchScope>
    ): List<SearchDocument<*>> {
        val corpus = SearchCorpus.corpus
        return SearchEngine.rank(DataStore, query, scopes, SmartSearchConfig.params, ::embed)
            .mapNotNull { corpus[it] }
    }

    override suspend fun onCorpusUpdate(
        added: List<SearchDocument<*>>,
        removed: Set<String>
    ) {
        Embedder.queueEmbedding(DataStore.ingest(added))
    }

    /** The query as a vector, or null if the model isn't available */
    private fun embed(query: String): FloatArray? {
        if (!ModelController.isReady()) return null
        return runCatching {
            ModelController.getModel().embed(
                ModelController.getQueryPrefix() + query
            ).content().vector()
        }.getOrNull()
    }
}
