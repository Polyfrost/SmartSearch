package org.polyfrost.smartsearch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.polyfrost.oneconfig.api.event.v1.EventManager
import org.polyfrost.oneconfig.api.event.v1.events.ShutdownEvent
import org.polyfrost.oneconfig.internal.ui.search.SearchProviderRegistry
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.index.DataStore
import org.polyfrost.smartsearch.index.Embedder
import org.polyfrost.smartsearch.model.ArcticEmbedXsFactory
import org.polyfrost.smartsearch.model.ModelController
import org.polyfrost.smartsearch.search.SmartSearchProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object SmartSearchClient {
    val LOGGER: Logger = LoggerFactory.getLogger(SmartSearchClient::class.java)

    fun init() {
        SmartSearchConfig.preload()
        SearchProviderRegistry.registerSearchProvider(SmartSearchProvider)

        CoroutineScope(Dispatchers.Default).launch {
            ModelController.init(ArcticEmbedXsFactory) {
                Embedder.startEmbeddings()
            }
        }
        EventManager.register(ShutdownEvent::class.java) { _ ->
            DataStore.close()
        }
    }
}