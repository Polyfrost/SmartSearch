package org.polyfrost.smartsearch.index

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.polyfrost.oneconfig.internal.ui.search.SearchDocument
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.model.ModelController
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object Embedder {
    const val BATCH_SIZE = 50

    private val toEmbed: ConcurrentLinkedQueue<SearchDocument<*>> = ConcurrentLinkedQueue()
    private val isRunning = AtomicBoolean(false)

    fun queueEmbedding(toEmbed: List<SearchDocument<*>>) {
        this.toEmbed.addAll(toEmbed)
        startEmbeddings()
    }

    fun startEmbeddings() {
        if (toEmbed.isEmpty() || !ModelController.isReady() || isRunning.getAndSet(true)) return
        SmartSearchClient.LOGGER.info("Starting embedding of ${toEmbed.size} documents...")
        val start = System.currentTimeMillis()

        CoroutineScope(Dispatchers.Default).launch {
            val model = ModelController.getModel()
            while (toEmbed.isNotEmpty()) {
                val batch = mutableListOf(toEmbed.poll())
                while (batch.size < BATCH_SIZE && toEmbed.isNotEmpty()) {
                    batch.add(toEmbed.poll())
                }
                DataStore.addEmbeddings(batch.mapNotNull {
                    val str = buildEmbeddingString(it)
                    if (str.isBlank()) return@mapNotNull null
                    it to model.embed(str).content()
                }.toMap())
            }
            isRunning.set(false)

            SmartSearchClient.LOGGER.info("Embedding finished in ${System.currentTimeMillis() - start}ms")
            startEmbeddings() // Re-trigger in case of a race condition
        }
    }

    private fun buildEmbeddingString(doc: SearchDocument<*>): String = buildString {
        addNotNull(doc.metadata.title)
        val categories = listOfNotNull(
            // Filter out default "general", it doesn't provide any useful info
            doc.metadata.category?.takeIf { it.lowercase() != "general" },
            doc.metadata.distinctSubcategory?.takeIf { it.lowercase() != "general" }
        )
        addNotNull(categories.takeIf { it.isNotEmpty() }) { "Category: ${it.joinToString()}" }
        addNotNull(doc.metadata.tags) { it.joinToString() }
        addNotNull(doc.metadata.description)
        addNotNull(doc.metadata.path)
        addNotNull(doc.metadata.modTitle)
    }.removeSuffix("\n")
}

private fun <T> StringBuilder.addNotNull(obj: T?, terminator: String? = "\n", strFunc: ((T) -> String)? = null) {
    if (obj == null) return
    var res = strFunc?.invoke(obj) ?: obj
    if (res is String) res = res.normalize()
    append(res)
    terminator?.let { append(it) }
}

private val WHITESPACE_REGEX = "[^\\S\\r\\n]+".toRegex()

/**
 * Remove normalize whitespace, remove duplicate newlines and whitespaces
 */
private fun String.normalize(): String =
    this.replace(WHITESPACE_REGEX, " ")
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")