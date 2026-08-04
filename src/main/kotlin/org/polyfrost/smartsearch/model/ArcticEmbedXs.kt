package org.polyfrost.smartsearch.model

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.AbstractInProcessEmbeddingModel
import dev.langchain4j.model.embedding.onnx.OnnxBertBiEncoder
import dev.langchain4j.model.embedding.onnx.PoolingMode
import dev.langchain4j.spi.model.embedding.EmbeddingModelFactory
import java.util.concurrent.Executor

// Runs submitted tasks on the calling thread, so embedAll() never fans out.
private val SAME_THREAD_EXECUTOR = Executor { it.run() }

class ArcticEmbedXs : AbstractInProcessEmbeddingModel(SAME_THREAD_EXECUTOR) {
    private val model: OnnxBertBiEncoder = load()

    override fun model(): OnnxBertBiEncoder = model

    private fun load(): OnnxBertBiEncoder {
        val classLoader = Thread.currentThread().contextClassLoader
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
