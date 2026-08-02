package org.polyfrost.smartsearch

import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.polyfrost.oneconfig.internal.ui.search.SearchProviderRegistry
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.model.ModelController
import org.polyfrost.smartsearch.search.SmartSearchProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object SmartSearchClient {
    val LOGGER: Logger = LoggerFactory.getLogger(SmartSearchClient::class.java)

    fun init() {
        //EventListener.register()
        SmartSearchConfig.preload()
        SearchProviderRegistry.registerSearchProvider(SmartSearchProvider)

        CoroutineScope(Dispatchers.Default).launch {
            ModelController.init(AllMiniLmL6V2QuantizedEmbeddingModelFactory())
        }
    }
}