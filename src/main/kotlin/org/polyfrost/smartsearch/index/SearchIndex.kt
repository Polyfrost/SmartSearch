@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.smartsearch.index

import dev.langchain4j.data.embedding.Embedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.CorruptIndexException
import org.apache.lucene.index.IndexFileNames
import org.apache.lucene.index.IndexFormatTooNewException
import org.apache.lucene.index.IndexFormatTooOldException
import org.apache.lucene.index.IndexNotFoundException
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.LeafReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.FieldExistsQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.store.LockObtainFailedException
import org.apache.lucene.util.FixedBitSet
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.search.SearchDocument
import org.polyfrost.oneconfig.api.ui.v1.search.SearchScope
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.cache.CacheStore
import org.polyfrost.smartsearch.cache.EmbeddingCache
import java.io.EOFException
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.concurrent.write

/**
 * Bumped whenever [SearchIndex.buildDocument] changes how a document is laid out
 */
private const val SCHEMA_VERSION = 3

private enum class EntryStatus {
    EXISTS,
    NOT_EXISTS,
    STALE,
    NEEDS_EMBEDDING,
}

private val LUCENE_PACKAGE: String = IndexWriter::class.java.name.removeSuffix("index.IndexWriter")

/**
 * A Lucene index over the search corpus, living at [path].
 */
open class SearchIndex(private val path: Path) {
    enum class Status {
        READY,
        INGESTING
    }

    /** How much of the index is written, as of the last commit */
    data class Stats(val documents: Int, val embedded: Int)

    /** Used to track what an index already holds */
    private data class StoredEntry(val hash: String, val hasEmbedding: Boolean)

    private class Opened(val directory: Directory, val writer: IndexWriter, val searcherManager: SearcherManager)

    // Split index and query analyzer, this helps us split words like "OverflowParticles" during indexing
    private val indexAnalyzer: Analyzer = SearchAnalyzer(splitCompounds = true)
    private val queryAnalyzer: Analyzer = SearchAnalyzer(splitCompounds = false)

    @Volatile
    private var opened: Opened = open(path)
    private val directory: Directory get() = opened.directory
    private val writer: IndexWriter get() = opened.writer
    private val searcherManager: SearcherManager get() = opened.searcherManager
    private val ingestMutex = Mutex()

    private var rebuilt = false

    /** Held for reading while the index is in use, and for writing by [close] */
    private val lifecycleLock = ReentrantReadWriteLock()

    @Volatile
    private var closed = false

    @Volatile
    var status: Status = Status.READY
        private set

    private val statsLock = Any()

    @Volatile
    var stats: Stats = Stats(0, 0)
        private set

    init {
        refreshStats()
    }

    private fun open(path: Path): Opened {
        try {
            return open(FSDirectory.open(path))
        } catch (e: LockObtainFailedException) {
            SmartSearchClient.LOGGER.warn("Search index at $path is in use by another instance")
        } catch (e: Exception) {
            if (!isCorruption(e)) {
                SmartSearchClient.LOGGER.warn("Failed to open search index at $path", e)
            } else {
                SmartSearchClient.LOGGER.warn("Search index at $path is unreadable, rebuilding it", e)
                try {
                    wipe(path)
                    return open(FSDirectory.open(path))
                } catch (rebuildError: Exception) {
                    SmartSearchClient.LOGGER.warn("Failed to rebuild search index at $path", rebuildError)
                }
            }
        }
        SmartSearchClient.LOGGER.warn("Keeping the search index in memory for this session")
        return open(ByteBuffersDirectory())
    }

    private fun isCorruption(e: Throwable?): Boolean = generateSequence(e) { it.cause }.any {
        it is CorruptIndexException || it is IndexFormatTooOldException || it is IndexFormatTooNewException ||
            it is IndexNotFoundException || isUnknownCodec(it) ||
            ((it is EOFException || it is NoSuchFileException || it is FileNotFoundException) && thrownByLucene(it))
    }

    private fun thrownByLucene(e: Throwable): Boolean = e.stackTrace.any { it.className.startsWith(LUCENE_PACKAGE) }

    private fun isUnknownCodec(e: Throwable?): Boolean =
        e is IllegalArgumentException && e.message?.contains("SPI class") == true

    private fun wipe(path: Path) {
        FSDirectory.open(path).use { directory ->
            directory.obtainLock(IndexWriter.WRITE_LOCK_NAME).use {
                for (file in path.listDirectoryEntries()) {
                    val name = file.name
                    if (name.startsWith(IndexFileNames.SEGMENTS) || name.startsWith(IndexFileNames.PENDING_SEGMENTS) ||
                        IndexFileNames.CODEC_FILE_PATTERN.matcher(name).matches()
                    ) file.deleteExisting()
                }
            }
        }
    }

    private fun open(directory: Directory): Opened {
        try {
            val writer = IndexWriter(directory, IndexWriterConfig(indexAnalyzer))
            try {
                writer.commit()
                return Opened(directory, writer, SearcherManager(writer, null))
            } catch (e: Throwable) {
                runCatching { writer.rollback() }.onFailure(e::addSuppressed)
                throw e
            }
        } catch (e: Throwable) {
            runCatching { directory.close() }.onFailure(e::addSuppressed)
            throw e
        }
    }

    private fun rebuild(attempted: Opened, e: Exception): Boolean {
        if (!isCorruption(e) && !isCorruption(attempted.writer.tragicException)) return false
        if (lifecycleLock.readHoldCount > 0) return false
        lifecycleLock.write {
            if (closed) return false
            if (opened !== attempted) return true

            SmartSearchClient.LOGGER.warn("Search index at $path is damaged, rebuilding it", e)
            runCatching { attempted.searcherManager.close() }
            runCatching { attempted.writer.rollback() }
            runCatching { attempted.directory.close() }
            closed = true
            opened = if (rebuilt || attempted.directory !is FSDirectory) {
                SmartSearchClient.LOGGER.warn("Search index at $path was damaged again, keeping it in memory for this session")
                open(ByteBuffersDirectory())
            } else try {
                wipe(path)
                open(FSDirectory.open(path))
            } catch (rebuildError: Exception) {
                SmartSearchClient.LOGGER.warn("Failed to rebuild search index at $path, keeping it in memory for this session", rebuildError)
                open(ByteBuffersDirectory())
            }
            rebuilt = true
            closed = false
            refreshStats()
            onRebuilt()
            return true
        }
    }

    protected open fun onRebuilt() {}

    /**
     * Writes every document in [added] whose indexed text is new or has changed.
     *
     * Returns the documents still needing a vector, for the caller to hand to an embedder.
     */
    suspend fun ingest(added: List<SearchDocument>): List<SearchDocument> = ingestMutex.withLock {
        withContext(Dispatchers.IO) {
            whileOpen {
                status = Status.INGESTING
                try {
                    withSearcher { searcher ->
                        val start = System.currentTimeMillis()
                        var addedCount = 0
                        var updatedCount = 0
                        val existing = snapshotEntries(searcher)

                        val toEmbed: MutableList<SearchDocument> = mutableListOf()
                        for (doc in added) {
                            val entryStatus = checkStatus(existing, doc)
                            if (entryStatus == EntryStatus.EXISTS) continue

                            val contentHash = doc.hash()
                            val cachedEmbedding = CacheStore.get(contentHash)
                            if (cachedEmbedding == null) toEmbed.add(doc)

                            if (entryStatus == EntryStatus.NOT_EXISTS || entryStatus == EntryStatus.STALE
                                || (entryStatus == EntryStatus.NEEDS_EMBEDDING && cachedEmbedding != null)
                            ) {
                                val newDoc = buildDocument(doc, cachedEmbedding, contentHash = contentHash)
                                writer.updateDocument(Term("id", doc.id), newDoc)
                                if (entryStatus == EntryStatus.NOT_EXISTS) addedCount++ else updatedCount++
                            }
                        }
                        commitAndRefresh()
                        SmartSearchClient.LOGGER.info("Added $addedCount and updated $updatedCount documents in ${System.currentTimeMillis() - start}ms")
                        return@whileOpen toEmbed
                    }
                } finally {
                    status = Status.READY
                }
            } ?: emptyList()
        }
    }

    /**
     * Collect the IDs of all documents not in the known list
     */
    fun collectUnknownEntries(known: Set<String>): Set<String> = whileOpen {
        withSearcher { searcher ->
            val staleIds = mutableSetOf<String>()

            for (leaf in searcher.indexReader.leaves()) {
                val storedFields = leaf.reader().storedFields()
                for (docId in 0 until leaf.reader().maxDoc()) {
                    if (leaf.reader().liveDocs?.get(docId) == false) continue // skip already deleted docs
                    val id = storedFields.document(docId, setOf("id")).get("id") ?: continue
                    if (id !in known) {
                        staleIds.add(id)
                    }
                }
            }

            return@whileOpen staleIds
        }
    } ?: emptySet()

    fun removeEntries(ids: Set<String>) {
        if (ids.isEmpty()) return
        whileOpen {
            val terms = ids.map { Term("id", it) }.toTypedArray()
            writer.deleteDocuments(*terms)
            commitAndRefresh()
            SmartSearchClient.LOGGER.info("Removed ${ids.size} search documents")
        }
    }

    /** Create an embedding cache of all embeddings loaded */
    fun createEmbeddingCache(): EmbeddingCache = whileOpen {
        withSearcher { searcher ->
            val res = mutableMapOf<String, Embedding>()
            for (leaf in searcher.indexReader.leaves()) {
                val reader = leaf.reader()
                val vectors = reader.getFloatVectorValues("embedding") ?: continue
                val storedFields = reader.storedFields()
                val iterator = vectors.iterator()
                var docId = iterator.nextDoc()
                while (docId != DocIdSetIterator.NO_MORE_DOCS) {
                    if (reader.liveDocs?.get(docId) != false) { // skip already deleted docs
                        val hash = storedFields.document(docId, setOf("content_hash")).get("content_hash")
                        if (hash != null) {
                            // vectorValue reuses array, so copy
                            res[hash] = Embedding(vectors.vectorValue(iterator.index()).copyOf())
                        }
                    }
                    docId = iterator.nextDoc()
                }
            }
            EmbeddingCache(res)
        }
    } ?: EmbeddingCache(emptyMap())

    /** Runs [searcher] against the index, or returns null once the index is closed */
    fun <T> search(searcher: (IndexReader, IndexSearcher, Analyzer) -> T): T? = whileOpen {
        withSearcher { indexSearcher ->
            searcher.invoke(indexSearcher.indexReader, indexSearcher, queryAnalyzer)
        }
    }

    /**
     * Writes the vectors in [embeddings] and makes them searchable, without committing.
     *
     * After you are done adding embeddings call flush to write the entries to disk
     */
    fun addEmbeddings(embeddings: Map<SearchDocument, Embedding>) {
        if (embeddings.isEmpty()) return
        whileOpen {
            embeddings.forEach { (doc, embedding) ->
                val newDoc = buildDocument(doc, embedding)
                writer.updateDocument(Term("id", doc.id), newDoc)
            }
            searcherManager.maybeRefreshBlocking()
            synchronized(statsLock) { stats = stats.copy(embedded = stats.embedded + embeddings.size) }
        }
    }

    /**
     * Commits everything written since the last commit, and re-reads stats
     * Don't run this in a search/whileOpen block, or there will be a deadlock if close() is called at the same time
     */
    fun flush() {
        whileOpen { commitAndRefresh() }
    }

    /**
     * Runs [block] against the index and keep the index open until finished,
     * returns null if the index is already closed.
     */
    private inline fun <T> whileOpen(block: () -> T): T? {
        val attempted = opened
        try {
            return locked(block)
        } catch (e: Exception) {
            if (!rebuild(attempted, e)) throw e
        }
        return locked(block)
    }

    private inline fun <T> locked(block: () -> T): T? {
        lifecycleLock.readLock().lock()
        try {
            if (closed) return null
            return block()
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    /** Borrow the shared searcher, and give it back later */
    private inline fun <T> withSearcher(block: (IndexSearcher) -> T): T {
        val searcher = searcherManager.acquire()
        try {
            return block(searcher)
        } finally {
            searcherManager.release(searcher)
        }
    }

    /** Writes pending changes, then refreshes the searcher */
    private fun commitAndRefresh() {
        writer.commit()
        searcherManager.maybeRefreshBlocking()
        refreshStats()
    }

    private fun refreshStats() {
        val fresh = runCatching {
            withSearcher { searcher ->
                Stats(searcher.indexReader.numDocs(), searcher.count(FieldExistsQuery("embedding")))
            }
        }.getOrElse {
            SmartSearchClient.LOGGER.warn("Failed to read index stats", it)
            return
        }
        synchronized(statsLock) { stats = fresh }
    }

    /**
     * Reads the id, content hash and embedding presence of every live document in one pass.
     */
    private fun snapshotEntries(searcher: IndexSearcher): Map<String, StoredEntry> {
        val entries = HashMap<String, StoredEntry>(searcher.indexReader.numDocs())
        val wanted = setOf("id", "content_hash")

        for (leaf in searcher.indexReader.leaves()) {
            val reader = leaf.reader()
            val embedded = embeddedDocs(reader)
            val storedFields = reader.storedFields()
            val liveDocs = reader.liveDocs
            for (docId in 0 until reader.maxDoc()) {
                if (liveDocs?.get(docId) == false) continue // skip already deleted docs
                val doc = storedFields.document(docId, wanted)
                val id = doc.get("id") ?: continue
                val hash = doc.get("content_hash") ?: continue
                entries[id] = StoredEntry(hash, embedded.get(docId))
            }
        }

        return entries
    }

    /** Check what documents have an embedding */
    private fun embeddedDocs(reader: LeafReader): FixedBitSet {
        val bits = FixedBitSet(reader.maxDoc())
        val vectors = reader.getFloatVectorValues("embedding") ?: return bits
        val iterator = vectors.iterator()
        var docId = iterator.nextDoc()
        while (docId != DocIdSetIterator.NO_MORE_DOCS) {
            bits.set(docId)
            docId = iterator.nextDoc()
        }
        return bits
    }

    private fun checkStatus(existing: Map<String, StoredEntry>, document: SearchDocument): EntryStatus {
        val entry = existing[document.id] ?: return EntryStatus.NOT_EXISTS
        if (entry.hash != document.hash()) return EntryStatus.STALE
        if (!entry.hasEmbedding) return EntryStatus.NEEDS_EMBEDDING
        return EntryStatus.EXISTS
    }

    private fun buildDocument(
        entry: SearchDocument,
        embedding: Embedding? = null,
        contentHash: String = entry.hash()
    ): Document {
        val doc = Document()
        doc.add(StringField("id", entry.id, Field.Store.YES))
        doc.add(StringField("content_hash", contentHash, Field.Store.YES))
        entry.metadata.title?.let {
            doc.add(TextField("title", it, Field.Store.NO))
            doc.add(TextField("title$JOINS_SUFFIX", it, Field.Store.NO))
            doc.add(StringField("title_key", titleKey(it), Field.Store.NO))
        }
        entry.metadata.description?.let {
            doc.add(TextField("description", it, Field.Store.NO))
        }
        entry.metadata.modTitle?.let {
            doc.add(TextField("mod", it, Field.Store.NO))
            doc.add(TextField("mod$JOINS_SUFFIX", it, Field.Store.NO))
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

    /** Closes the index, once every in flight read and write has finished */
    fun close(): Unit = lifecycleLock.write {
        if (closed) return
        closed = true
        searcherManager.close()
        writer.close()
        directory.close()
    }
}

internal fun SearchDocument.hash(): String {
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
            metadata.path,
            metadata.modTitle,
            metadata.modDescription,
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

internal fun SearchScope.toKey(): String = key

/**
 * The whole title as an indexable token
 *
 * Not analyzed so you can match a title exactly or check if it includes something
 */
internal fun titleKey(title: String): String = title.trim().lowercase().replace(Regex("\\s+"), " ")
