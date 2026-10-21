@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.smartsearch.index

import kotlinx.coroutines.launch
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.search.SearchProviders
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.config.StaleEntries

/** The game's search index. */
object DataStore : SearchIndex(SmartSearchConfig.dbPath()) {
    /** Amount of launches an entry needs to be unseen before it's removed */
    private const val LAUNCHES_BEFORE_REMOVAL = 2
    val staleEntries: StaleEntries = StaleEntries.load()

    override fun onRebuilt() {
        SmartSearchClient.scope.launch { Embedder.queueEmbedding(ingest(SearchProviders.documentIds().mapNotNull(SearchProviders::document))) }
    }

    /** Drops every indexed document whose id is not in [keep]. */
    fun clean(keep: Set<String> = SearchProviders.documentIds()) {
        val stale = collectUnknownEntries(keep)
        removeEntries(stale)
        staleEntries.clear()
        staleEntries.save()
    }

    /** Update the tracked stale entries in the DB */
    fun updateTrackedStaleEntries(keep: Set<String> = SearchProviders.documentIds()) {
        val stale = collectUnknownEntries(keep)
        if (staleEntries.track(stale, LAUNCHES_BEFORE_REMOVAL)) {
            staleEntries.save()
        }
    }

    /** Count down the launches and remove entries that haven't been seen in [LAUNCHES_BEFORE_REMOVAL] launches */
    fun removeTrackedStaleEntries() {
        val expired = staleEntries.countDown()
        removeEntries(expired)
        staleEntries.save()
    }
}
