package org.polyfrost.smartsearch.eval

import dev.langchain4j.data.embedding.Embedding
import kotlinx.coroutines.runBlocking
import org.polyfrost.smartsearch.index.SearchIndex
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

private const val EMBEDDING_BATCH = 500

object EvalIndex {

    val index: SearchIndex by lazy { runBlocking { build() } }

    private suspend fun build(): SearchIndex {
        val dir = Path("build/eval-index")
        dir.toFile().deleteRecursively()
        dir.createDirectories()

        val start = System.currentTimeMillis()
        val index = SearchIndex(dir)
        index.ingest(EvalCorpus.documents)

        val vectors = EvalModel.documentVectors
        EvalCorpus.documents
            .filter { it.id in vectors }
            .chunked(EMBEDDING_BATCH)
            .forEach { batch ->
                index.addEmbeddings(batch.associateWith { Embedding(vectors.getValue(it.id)) })
            }
        index.flush()

        val stats = index.stats
        println("[eval] index ready: ${stats.documents} documents, ${stats.embedded} embedded, ${System.currentTimeMillis() - start}ms")
        check(stats.embedded > 0) { "no vectors made it into the evaluation index" }
        return index
    }
}
