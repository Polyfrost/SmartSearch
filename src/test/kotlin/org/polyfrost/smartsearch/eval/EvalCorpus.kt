@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.smartsearch.eval

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.internal.SearchInterop
import org.polyfrost.oneconfig.api.ui.v1.search.SearchDocument
import org.polyfrost.oneconfig.api.ui.v1.search.SearchMetadata
import org.polyfrost.oneconfig.api.ui.v1.search.SearchScope

private const val FIXTURE = "/smartsearch-documents.json"

/**
 * Evaluation search corpus, from a real export
 */
object EvalCorpus {

    val documents: List<SearchDocument> by lazy { load() }

    val byId: Map<String, SearchDocument> by lazy { documents.associateBy { it.id } }

    /** Titles shared by more than one document. Generated queries avoid these, as no single answer would be right. */
    val ambiguousTitles: Set<String> by lazy {
        documents.mapNotNull { it.metadata.title?.lowercase() }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
    }

    private fun load(): List<SearchDocument> {
        val stream = EvalCorpus::class.java.getResourceAsStream(FIXTURE)
            ?: error("Missing evaluation fixture $FIXTURE")
        val array = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonArray

        return array.map { element ->
            val obj = element.asJsonObject
            SearchInterop.document(
                id = obj["id"].asString,
                scopes = obj["scopes"].asJsonArray.map { it.asString.toScope() }.toSet(),
                metadata = obj["metadata"].asJsonObject.toMetadata(),
            )
        }
    }

    private fun JsonObject.toMetadata(): SearchMetadata = SearchInterop.metadata(
        title = string("title"),
        id = string("id"),
        description = string("description"),
        section = string("section"),
        category = string("category"),
        subcategory = string("subcategory"),
        tags = get("tags")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty(),
        modTitle = string("modTitle"),
        modDescription = string("modDescription"),
        path = string("path"),
    )

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun String.toScope(): SearchScope = when {
        this == "mods" -> SearchScope.MODS
        this == "options" -> SearchScope.OPTIONS
        this == "keybinds" -> SearchScope.KEYBINDS
        this == "huds" -> SearchScope.HUDS
        startsWith("config:") -> SearchScope.config(removePrefix("config:"))
        else -> error("Unknown scope key '$this'")
    }
}
