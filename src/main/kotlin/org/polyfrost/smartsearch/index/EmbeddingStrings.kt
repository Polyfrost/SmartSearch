package org.polyfrost.smartsearch.index

import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.internal.ui.api.TreeConfigData
import org.polyfrost.oneconfig.internal.ui.components.asRenderText
import org.polyfrost.oneconfig.internal.ui.components.localizedDescription
import org.polyfrost.oneconfig.internal.ui.components.localizedGroup
import org.polyfrost.oneconfig.internal.ui.components.localizedString
import org.polyfrost.oneconfig.internal.ui.components.localizedTitle

fun buildEmbeddingString(configData: TreeConfigData, property: Property<*>): String {
    // TODO: expand with other metadata
    val title = property.localizedTitle().asRenderText().normalize()
    val description = property.localizedDescription()?.asRenderText()?.normalize()
    val modTitle = localizedString(null, configData.title).normalize()
    val category: String = property.localizedGroup("category", "categoryKey", "").normalize()
    val subcategory: String = property.localizedGroup("subcategory", "subcategoryKey", "").normalize()

    return buildString {
        if (title.isNotBlank()) {
            append(title)
            append("\n")
        }
        if (modTitle.isNotBlank() && modTitle != title) {
            append("Mod: $modTitle\n")
        }
        if (category.isNotBlank() || subcategory.isNotBlank()) {
            append("Category: ")
        }
        if (category.isNotBlank()) {
            append(category)
            if (subcategory.isNotBlank() && category != subcategory) append(", ")
        }
        if (subcategory.isNotBlank() && category != subcategory) {
            append(subcategory)
        }
        if (category.isNotBlank() || subcategory.isNotBlank()) {
            append("\n")
        }
        if (!description.isNullOrBlank()) {
            append(description)
        }
    }.trim()
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