package org.polyfrost.smartsearch.index

import kotlin.io.path.Path

/** The game's search index. */
object DataStore : SearchIndex(Path("smartsearch-db"))
