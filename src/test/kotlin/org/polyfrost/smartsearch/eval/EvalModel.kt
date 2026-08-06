package org.polyfrost.smartsearch.eval

import kotlinx.coroutines.runBlocking
import org.polyfrost.smartsearch.index.embeddingText
import org.polyfrost.smartsearch.model.ArcticEmbedXsFactory
import org.polyfrost.smartsearch.model.ModelController
import org.polyfrost.smartsearch.model.MultiThreadedArcticEmbedXs
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

private val CACHE_DIR = Path("build/eval-cache")

/**
 * The embedding model, plus a disk cache of the document vectors to speed up eval.
 */
object EvalModel {
    val documentTexts: Map<String, String> by lazy {
        EvalCorpus.documents.associate { it.id to embeddingText(it) }
            .filterValues { it.isNotBlank() }
    }

    val queryPrefix: String by lazy {
        ensureModelLoaded()
        ModelController.getQueryPrefix()
    }

    fun embedQuery(query: String): FloatArray {
        ensureModelLoaded()
        return ModelController.getModel().embed(queryPrefix + query).content().vector()
    }

    val documentVectors: Map<String, FloatArray> by lazy {
        val ids = documentTexts.keys.sorted()
        val cache = CACHE_DIR.resolve("doc-vectors-${cacheKey(ids)}.bin")
        if (cache.exists()) {
            runCatching { readCache(cache, ids) }.getOrNull()?.let {
                println("[eval] reusing ${it.size} cached document vectors")
                return@lazy it
            }
            println("[eval] cached vectors at $cache unreadable, re-embedding")
        }
        val vectors = embedAll(ids)
        CACHE_DIR.createDirectories()
        writeCache(cache, ids, vectors)
        vectors
    }

    private fun ensureModelLoaded() {
        if (ModelController.isReady()) return
        synchronized(this) {
            if (ModelController.isReady()) return
            runBlocking { ModelController.init(ArcticEmbedXsFactory) }
            check(ModelController.isReady()) {
                "Embedding model failed to load (state=${ModelController.state}). "
            }
        }
    }

    /**
     * Embeds every document, sharded across threads.
     */
    private fun embedAll(ids: List<String>): Map<String, FloatArray> {
        val model = MultiThreadedArcticEmbedXs()
        val threads = minOf(4, Runtime.getRuntime().availableProcessors())
        println("[eval] embedding ${ids.size} documents on $threads threads, this takes a few minutes...")
        val start = System.currentTimeMillis()

        val vectors = HashMap<String, FloatArray>(ids.size)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val shards = ids.chunked((ids.size + threads - 1) / threads)
            val results = shards.map { shard ->
                pool.submit<List<Pair<String, FloatArray>>> {
                    shard.map { id -> id to model.embed(documentTexts.getValue(id)).content().vector() }
                }
            }
            results.forEach { vectors.putAll(it.get()) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }

        println("[eval] embedded ${vectors.size} documents in ${(System.currentTimeMillis() - start) / 1000}s")
        return vectors
    }

    private fun cacheKey(ids: List<String>): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(ArcticEmbedXsFactory::class.java.name.toByteArray())
        for (id in ids) {
            md.update(id.toByteArray())
            md.update(documentTexts.getValue(id).toByteArray())
        }
        return md.digest().toHexString().take(16)
    }

    private fun readCache(path: java.nio.file.Path, ids: List<String>): Map<String, FloatArray> {
        DataInputStream(path.inputStream().buffered()).use { input ->
            val count = input.readInt()
            val dim = input.readInt()
            check(count == ids.size) { "cached vector count $count != ${ids.size}" }
            val vectors = HashMap<String, FloatArray>(count)
            repeat(count) {
                val id = input.readUTF()
                val vector = FloatArray(dim) { input.readFloat() }
                vectors[id] = vector
            }
            return vectors
        }
    }

    private fun writeCache(path: java.nio.file.Path, ids: List<String>, vectors: Map<String, FloatArray>) {
        DataOutputStream(path.outputStream().buffered()).use { output ->
            output.writeInt(ids.size)
            output.writeInt(vectors.values.first().size)
            for (id in ids) {
                output.writeUTF(id)
                vectors.getValue(id).forEach(output::writeFloat)
            }
        }
    }
}
