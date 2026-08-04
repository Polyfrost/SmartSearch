package org.polyfrost.smartsearch.util

import com.google.gson.GsonBuilder
import org.polyfrost.oneconfig.internal.ui.search.SearchCorpus
import org.polyfrost.oneconfig.internal.ui.search.SearchMetadata
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.index.toKey
import kotlin.io.path.Path
import kotlin.io.path.bufferedWriter

private class ExportedDocument(
    val id: String,
    val scopes: List<String>,
    val metadata: SearchMetadata,
)

object DocumentExporter {
    private val EXPORT_PATH = Path("smartsearch-documents.json")
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    fun export() {
        val corpus = SearchCorpus.corpus
        // Strip out payload
        val documents = corpus.values.map { doc ->
            ExportedDocument(doc.id, doc.scopes.map { it.toKey() }, doc.metadata)
        }
        runCatching {
            EXPORT_PATH.bufferedWriter().use { GSON.toJson(documents, it) }
        }.onSuccess {
            SmartSearchClient.LOGGER.info("Exported ${documents.size} search documents to ${EXPORT_PATH.toAbsolutePath()}")
        }.onFailure {
            SmartSearchClient.LOGGER.error("Failed to export search documents", it)
        }
    }
}