@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.smartsearch.util

import com.google.gson.GsonBuilder
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.search.SearchMetadata
import org.polyfrost.oneconfig.api.ui.v1.search.SearchProviders
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
        // Strip out payload
        val documents = SearchProviders.documentIds().mapNotNull(SearchProviders::document).map { doc ->
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