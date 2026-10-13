package org.polyfrost.smartsearch.model

import ai.djl.util.ClassLoaderUtils
import ai.djl.util.Platform
import ai.djl.util.Utils
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.AbstractInProcessEmbeddingModel
import dev.langchain4j.model.embedding.onnx.OnnxBertBiEncoder
import dev.langchain4j.model.embedding.onnx.PoolingMode
import dev.langchain4j.spi.model.embedding.EmbeddingModelFactory
import org.polyfrost.smartsearch.SmartSearchClient
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

// Runs submitted tasks on the calling thread, so embedAll() never fans out.
private val SAME_THREAD_EXECUTOR = Executor { it.run() }

internal fun dropTruncatedTokenizerCache(cacheDir: Path = Utils.getEngineCacheDir("tokenizers")) {
    runCatching {
        val platform = Platform.detectPlatform("tokenizers")
        val dir = cacheDir.resolve("${platform.version}-cpu-${platform.classifier}")
        if (!dir.isDirectory()) return
        for (cached in dir.listDirectoryEntries()) {
            val expected = bundledSize("native/lib/${platform.classifier}/cpu/${cached.name}") ?: continue
            val actual = cached.fileSize()
            if (actual != expected) {
                SmartSearchClient.LOGGER.warn("Cached $cached is $actual bytes, expected $expected, extracting it again")
                Utils.deleteQuietly(dir)
                return
            }
        }
    }.onFailure { SmartSearchClient.LOGGER.warn("Failed to check cached tokenizer library", it) }
}

private fun bundledSize(resource: String): Long? {
    val url = ClassLoaderUtils.getResource(resource) ?: return null
    return url.openConnection().contentLengthLong.takeIf { it >= 0 }
        ?: url.openStream().use { it.transferTo(OutputStream.nullOutputStream()) }
}

class ArcticEmbedXs : AbstractInProcessEmbeddingModel(SAME_THREAD_EXECUTOR) {
    private val model: OnnxBertBiEncoder = load()

    override fun model(): OnnxBertBiEncoder = model

    private fun load(): OnnxBertBiEncoder {
        val classLoader = ArcticEmbedXs::class.java.classLoader
        val modelBytes = classLoader.getResourceAsStream("models/arctic-embed-xs/model_int8.onnx")
            ?.use { it.readBytes() }
            ?: error("Embedding model file is not available")
        val tokenizer = classLoader.getResourceAsStream("models/arctic-embed-xs/tokenizer.json")
            ?: error("Tokenizer file is not available")

        // Limit to 1 core/thread to prevent heavy CPU burst when indexing
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }
        val session = environment.createSession(modelBytes, options)

        dropTruncatedTokenizerCache()
        return tokenizer.use { OnnxBertBiEncoder(environment, session, it, PoolingMode.CLS) }
    }
}

class MultiThreadedArcticEmbedXs : AbstractInProcessEmbeddingModel(null) {
    private val model = loadFromJar(
        "models/arctic-embed-xs/model_int8.onnx", "models/arctic-embed-xs/tokenizer.json",
        PoolingMode.CLS
    )

    override fun model(): OnnxBertBiEncoder = model
}

object ArcticEmbedXsFactory : EmbeddingModelFactory {
    override fun create(): EmbeddingModel = ArcticEmbedXs()
}
