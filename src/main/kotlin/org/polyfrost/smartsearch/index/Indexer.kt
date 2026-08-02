package org.polyfrost.smartsearch.index

import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.api.config.v1.Tree
import org.polyfrost.oneconfig.internal.ui.api.ConfigRegistry
import org.polyfrost.oneconfig.internal.ui.api.TreeConfigData
import org.polyfrost.oneconfig.internal.ui.components.asRenderText
import org.polyfrost.oneconfig.internal.ui.search.OptionResult
import org.polyfrost.oneconfig.internal.ui.search.SearchResult
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.model.ModelController
import java.util.concurrent.atomic.AtomicBoolean

object Indexer {
    enum class State {
        UNINDEXED,
        INDEXING,
        INDEXED
    }

    val embeddings: InMemoryEmbeddingStore<SearchResult> = InMemoryEmbeddingStore()
    private var hasIndexed: AtomicBoolean = AtomicBoolean(false)
    var state: State = State.UNINDEXED
        private set

    fun index() {
        if (!ModelController.isReady() || hasIndexed.getAndSet(true)) {
            return
        }
        state = State.INDEXING
        SmartSearchClient.LOGGER.info("Starting indexing")
        val start = System.currentTimeMillis()

        for (configData in ConfigRegistry.configs) {
            if (!ConfigRegistry.shouldShowInSearch(configData)) continue
            val tree = (configData as? TreeConfigData)?.tree ?: continue
            indexTree(configData, tree)
        }

        state = State.INDEXED
        SmartSearchClient.LOGGER.info("Finished indexing, took ${System.currentTimeMillis() - start}ms")
    }

    private fun indexTree(configData: TreeConfigData, tree: Tree) {
        tree.map.values.forEach { node ->
            when (node) {
                is Property<*> -> {
                    val text = buildEmbeddingString(configData, node)
                    println(text)
                    if (text.isBlank()) return@forEach
                    val searchRes = OptionResult(
                        configData.id,
                        configData.title,
                        node.title?.asRenderText() ?: "",
                        node.getMetadata("category"),
                        configData.icon,
                        node
                    )

                    // Do the embedding
                    val model = ModelController.getModel()
                    val embedding = model.embed(text).content()
                    embeddings.add(embedding, searchRes)
                }

                is Tree -> indexTree(configData, node)
            }
        }
    }

    fun hasFinished(): Boolean = state == State.INDEXED && hasIndexed.get()

    fun clear() {
        state = State.UNINDEXED
        embeddings.removeAll()
        hasIndexed.set(false)
    }
}