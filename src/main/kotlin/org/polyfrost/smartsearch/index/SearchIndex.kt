package org.polyfrost.smartsearch.index

import dev.langchain4j.data.embedding.Embedding
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.FieldExistsQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import org.polyfrost.oneconfig.internal.ui.search.SearchDocument
import org.polyfrost.oneconfig.internal.ui.search.SearchScope
import org.polyfrost.smartsearch.SmartSearchClient
import java.security.MessageDigest
import java.nio.file.Path

/**
 * Bumped whenever [SearchIndex.buildDocument] changes how a document is laid out
 */
private const val SCHEMA_VERSION = 2

private enum class EntryStatus {
    EXISTS,
    NOT_EXISTS,
    STALE,
    NEEDS_EMBEDDING,
}

/**
 * A Lucene index over the search corpus, living at [path].
 */
open class SearchIndex(path: Path) {
    enum class Status {
        READY,
        INGESTING
    }

    /** How much of the index is written, as of the last commit */
    data class Stats(val documents: Int, val embedded: Int)

    private val directory: Directory = FSDirectory.open(path)
    // Split index and query analyzer, this helps us split words like "OverflowParticles" during indexing
    private val indexAnalyzer: Analyzer = SearchAnalyzer(splitCompounds = true)
    private val queryAnalyzer: Analyzer = SearchAnalyzer(splitCompounds = false)
    private val config: IndexWriterConfig = IndexWriterConfig(indexAnalyzer)
    private val writer: IndexWriter = IndexWriter(directory, config).apply {
        commit()  // Create db file
    }

    @Volatile
    var status: Status = Status.READY
        private set

    @Volatile
    var stats: Stats = Stats(0, 0)
        private set

    init {
        refreshStats()
    }

    /**
     * Writes every document in [added] whose indexed text is new or has changed.
     *
     * Returns the documents still needing a vector, for the caller to hand to an embedder. Ingest deliberately does
     * not embed: writing the text side is fast and makes documents lexically findable immediately, while embedding
     * thousands of documents takes long enough that it has to happen in the background.
     */
    fun ingest(added: List<SearchDocument<*>>): List<SearchDocument<*>> {
        status = Status.INGESTING
        try {
            DirectoryReader.open(directory).use { reader ->
                val searcher = IndexSearcher(reader)
                val start = System.currentTimeMillis()
                var addedCount = 0
                var updatedCount = 0

                val toEmbed: MutableList<SearchDocument<*>> = mutableListOf()
                for (doc in added) {
                    val entryStatus = checkStatus(searcher, doc)
                    if (entryStatus != EntryStatus.EXISTS) {
                        toEmbed.add(doc)
                    }
                    if (entryStatus == EntryStatus.NOT_EXISTS || entryStatus == EntryStatus.STALE) {
                        val newDoc = buildDocument(doc)
                        if (entryStatus == EntryStatus.NOT_EXISTS) {
                            writer.addDocument(newDoc)
                            addedCount++
                        } else {
                            writer.updateDocument(Term("id", doc.id), newDoc)
                            updatedCount++
                        }
                    }
                }
                writer.commit()
                refreshStats()
                SmartSearchClient.LOGGER.info("Added $addedCount and updated $updatedCount documents in ${System.currentTimeMillis() - start}ms")
                return toEmbed
            }
        } finally {
            status = Status.READY
        }
    }

    /** Drops every indexed document whose id is not in [keep]. */
    fun clean(keep: Set<String>) {
        DirectoryReader.open(directory).use { reader ->
            val staleIds = mutableListOf<String>()

            for (leaf in reader.leaves()) {
                val storedFields = leaf.reader().storedFields()
                for (docId in 0 until leaf.reader().maxDoc()) {
                    if (leaf.reader().liveDocs?.get(docId) == false) continue // skip already deleted docs
                    val id = storedFields.document(docId, setOf("id")).get("id") ?: continue
                    if (id !in keep) {
                        staleIds.add(id)
                    }
                }
            }

            if (staleIds.isNotEmpty()) {
                val terms = staleIds.map { Term("id", it) }.toTypedArray()
                writer.deleteDocuments(*terms)
                writer.commit()
                refreshStats()
                SmartSearchClient.LOGGER.info("Removed ${staleIds.size} stale search documents")
            }
        }
    }

    fun <T> search(searcher: (IndexReader, IndexSearcher, Analyzer) -> T): T {
        DirectoryReader.open(directory).use { reader ->
            val indexSearcher = IndexSearcher(reader)
            return searcher.invoke(reader, indexSearcher, queryAnalyzer)
        }
    }

    fun addEmbeddings(embeddings: Map<SearchDocument<*>, Embedding>) {
        embeddings.forEach { (doc, embedding) ->
            val newDoc = buildDocument(doc, embedding)
            writer.updateDocument(Term("id", doc.id), newDoc)
        }
        writer.commit()
        refreshStats()
    }

    private fun refreshStats() {
        stats = runCatching {
            DirectoryReader.open(directory).use { reader ->
                Stats(reader.numDocs(), IndexSearcher(reader).count(FieldExistsQuery("embedding")))
            }
        }.getOrElse {
            SmartSearchClient.LOGGER.warn("Failed to read index stats", it)
            return
        }
    }

    private fun checkStatus(searcher: IndexSearcher, document: SearchDocument<*>): EntryStatus {
        // Get entry
        val idTerm = Term("id", document.id)
        val hits = searcher.search(TermQuery(idTerm), 1)
        if (hits.totalHits.value == 0L) {
            return EntryStatus.NOT_EXISTS
        }
        // Check hash match
        val doc = searcher.storedFields().document(hits.scoreDocs.first().doc)
        if (doc.get("content_hash") != document.hash()) {
            return EntryStatus.STALE
        }
        // Check embed status
        if (searcher.search(
                BooleanQuery.Builder()
                    .add(TermQuery(idTerm), BooleanClause.Occur.MUST)
                    .add(FieldExistsQuery("embedding"), BooleanClause.Occur.MUST)
                    .build(),
                1
            ).totalHits.value == 0L
        ) {
            return EntryStatus.NEEDS_EMBEDDING
        }
        return EntryStatus.EXISTS
    }

    private fun buildDocument(entry: SearchDocument<*>, embedding: Embedding? = null): Document {
        val doc = Document()
        doc.add(StringField("id", entry.id, Field.Store.YES))
        doc.add(StringField("content_hash", entry.hash(), Field.Store.YES))
        entry.metadata.title?.let {
            doc.add(TextField("title", it, Field.Store.NO))
            doc.add(StringField("title_key", titleKey(it), Field.Store.NO))
        }
        entry.metadata.description?.let {
            doc.add(TextField("description", it, Field.Store.NO))
        }
        entry.metadata.modTitle?.let {
            doc.add(TextField("mod", it, Field.Store.NO))
        }
        // Where the option is on the settings page
        val context = listOfNotNull(
            entry.metadata.category?.takeUnless { it.equals("general", ignoreCase = true) },
            entry.metadata.distinctSubcategory?.takeUnless { it.equals("general", ignoreCase = true) },
            entry.metadata.section,
            entry.metadata.path,
        ).distinct()
        if (context.isNotEmpty()) {
            doc.add(TextField("context", context.joinToString(" "), Field.Store.NO))
        }
        if (entry.metadata.tags.isNotEmpty()) {
            doc.add(TextField("tags", entry.metadata.tags.joinToString(" "), Field.Store.NO))
        }
        embedding?.let {
            doc.add(KnnFloatVectorField("embedding", it.vector(), VectorSimilarityFunction.COSINE))
        }
        entry.scopes.forEach {
            doc.add(StringField("scope", it.toKey(), Field.Store.NO))
        }
        return doc
    }

    fun close() {
        writer.close()
        directory.close()
    }
}

private fun SearchDocument<*>.hash(): String {
    val md = MessageDigest.getInstance("MD5")
    val hashStr = buildString {
        append(SCHEMA_VERSION)
        append("::")
        listOf(
            metadata.title,
            metadata.description,
            metadata.section,
            metadata.category,
            metadata.subcategory,
            metadata.modTitle
        ).forEach {
            it?.let { append(it) }
            append("::")
        }
        append(metadata.tags.joinToString(","))
        append("::")
        append(scopes.joinToString(",") { it.toKey() })
    }
    val digest = md.digest(hashStr.toByteArray())
    return digest.toHexString()
}

internal fun SearchScope.toKey(): String = when (this) {
    is SearchScope.Mods -> "mods"
    is SearchScope.Options -> "options"
    is SearchScope.Keybinds -> "keybinds"
    is SearchScope.Config -> "config:${this.id}"
}

/**
 * The whole title as an indexable token
 *
 * Not analyzed so you can match a title exactly or check if it includes something
 */
internal fun titleKey(title: String): String = title.trim().lowercase().replace(Regex("\\s+"), " ")
