package org.polyfrost.smartsearch.index

import org.apache.lucene.codecs.Codec
import org.apache.lucene.codecs.FilterCodec
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.CorruptIndexException
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.store.IOContext
import java.nio.file.NoSuchFileException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchIndexRecoveryTest {

    @Test
    fun rebuildsUnreadableIndex() {
        val dir = createTempDirectory("smartsearch-db")
        SearchIndex(dir).close()
        dir.listDirectoryEntries("segments_*").forEach { it.writeBytes(ByteArray(8)) }
        val other = dir.resolve("stale-entries.json").apply { writeBytes(ByteArray(1)) }

        val index = SearchIndex(dir)
        assertEquals(0, index.stats.documents)
        assertTrue(other.exists(), "only Lucene's files should be wiped")
        index.close()
        SearchIndex(dir).close()
    }

    @Test
    fun rebuildsIndexWithUnknownCodec() {
        val dir = createTempDirectory("smartsearch-db")
        val codec = object : FilterCodec("LuceneGone", Codec.getDefault()) {}
        IndexWriter(FSDirectory.open(dir), IndexWriterConfig().setCodec(codec)).use {
            it.addDocument(Document().apply { add(StringField("id", "1", Field.Store.NO)) })
        }

        SearchIndex(dir).close()
        FSDirectory.open(dir).use { DirectoryReader.open(it).close() }
    }

    @Test
    fun rebuildsIndexDamagedWhileOpen() {
        val dir = createTempDirectory("smartsearch-db")
        IndexWriter(FSDirectory.open(dir), IndexWriterConfig()).use {
            it.addDocument(Document().apply { add(StringField("id", "1", Field.Store.NO)) })
        }
        var rebuilds = 0
        val index = object : SearchIndex(dir) {
            override fun onRebuilt() {
                rebuilds++
            }
        }
        assertEquals(1, index.stats.documents)

        var calls = 0
        val result = index.search { reader, _, _ ->
            if (calls++ == 0) throw CorruptIndexException("damaged", "test")
            reader.numDocs()
        }
        assertEquals(0, result, "the search should run again on the rebuilt index")
        assertEquals(0, index.stats.documents)
        assertEquals(1, rebuilds, "the owner should be told to refill the index")
        index.close()
        SearchIndex(dir).close()
    }

    @Test
    fun rebuildsIndexWithFileLostWhileOpen() {
        val dir = createTempDirectory("smartsearch-db")
        val index = SearchIndex(dir)
        var calls = 0
        val result = index.search { reader, _, _ ->
            if (calls++ == 0) FSDirectory.open(dir).use { it.openInput("_0.cfs", IOContext.DEFAULT) }
            reader.numDocs()
        }
        assertEquals(0, result, "a missing segment file should rebuild like any other damage")
        index.close()
    }

    @Test
    fun keepsIndexWhenCallbackLosesItsOwnFile() {
        var rebuilds = 0
        val index = object : SearchIndex(createTempDirectory("smartsearch-db")) {
            override fun onRebuilt() {
                rebuilds++
            }
        }
        assertFailsWith<NoSuchFileException> { index.search { _, _, _ -> throw NoSuchFileException("mine.txt") } }
        assertEquals(0, rebuilds, "file trouble outside Lucene says nothing about the index")
        index.close()
    }

    @Test
    fun leavesDiskAloneOnceInMemory() {
        val dir = createTempDirectory("smartsearch-db")
        val owner = SearchIndex(dir)
        val index = SearchIndex(dir)
        owner.close()
        val before = dir.listDirectoryEntries().map { it.name }.sorted()

        var calls = 0
        index.search { _, _, _ -> if (calls++ == 0) throw CorruptIndexException("damaged", "test") }
        assertEquals(before, dir.listDirectoryEntries().map { it.name }.sorted(), "the other instance's index should be kept")
        index.close()
    }

    @Test
    fun rebuildsIndexWithWrappedDamage() {
        val index = SearchIndex(createTempDirectory("smartsearch-db"))
        var calls = 0
        val result = index.search { reader, _, _ ->
            if (calls++ == 0) throw RuntimeException(CorruptIndexException("damaged", "test"))
            reader.numDocs()
        }
        assertEquals(0, result, "damage wrapped in another exception should still rebuild")
        index.close()
    }

    @Test
    fun givesUpOnDiskWhenDamagedAgain() {
        val dir = createTempDirectory("smartsearch-db")
        var rebuilds = 0
        val index = object : SearchIndex(dir) {
            override fun onRebuilt() {
                rebuilds++
            }
        }
        repeat(2) {
            var calls = 0
            index.search { _, _, _ -> if (calls++ == 0) throw CorruptIndexException("damaged", "test") }
        }
        assertEquals(2, rebuilds)
        IndexWriter(FSDirectory.open(dir), IndexWriterConfig()).close()
        index.close()
    }

    @Test
    fun leavesLockedIndexAlone() {
        val dir = createTempDirectory("smartsearch-db")
        val first = SearchIndex(dir)
        val before = dir.listDirectoryEntries().associate { it.name to it.readBytes().toList() }

        SearchIndex(dir).close()
        val after = dir.listDirectoryEntries().associate { it.name to it.readBytes().toList() }
        assertContentEquals(before.entries.sortedBy { it.key }, after.entries.sortedBy { it.key })
        first.close()
    }
}
