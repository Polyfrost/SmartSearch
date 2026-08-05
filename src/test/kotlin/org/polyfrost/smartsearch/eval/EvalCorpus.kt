package org.polyfrost.smartsearch.eval

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.polyfrost.oneconfig.internal.ui.search.SearchDocument
import org.polyfrost.oneconfig.internal.ui.search.SearchMetadata
import org.polyfrost.oneconfig.internal.ui.search.SearchScope

private const val FIXTURE = "/smartsearch-documents.json"

/**
 * Evaluation search corpus, from a real export
 */
object EvalCorpus {

    val documents: List<SearchDocument<Unit>> by lazy { load() }

    val byId: Map<String, SearchDocument<Unit>> by lazy { documents.associateBy { it.id } }

    /** Titles shared by more than one document. Generated queries avoid these, as no single answer would be right. */
    val ambiguousTitles: Set<String> by lazy {
        documents.mapNotNull { it.metadata.title?.lowercase() }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
    }

    private fun load(): List<SearchDocument<Unit>> {
        val stream = EvalCorpus::class.java.getResourceAsStream(FIXTURE)
            ?: error("Missing evaluation fixture $FIXTURE")
        val array = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonArray

        return array.map { element ->
            val obj = element.asJsonObject
            SearchDocument(
                id = obj["id"].asString,
                scopes = obj["scopes"].asJsonArray.map { it.asString.toScope() }.toSet(),
                metadata = obj["metadata"].asJsonObject.toMetadata(),
                payload = Unit,
            )
        }
    }

    private fun JsonObject.toMetadata() = SearchMetadata(
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
        this == "mods" -> SearchScope.Mods
        this == "options" -> SearchScope.Options
        this == "keybinds" -> SearchScope.Keybinds
        startsWith("config:") -> SearchScope.Config(removePrefix("config:"))
        else -> error("Unknown scope key '$this'")
    }
}
