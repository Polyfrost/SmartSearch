package org.polyfrost.smartsearch.model

import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.spi.model.embedding.EmbeddingModelFactory
import org.polyfrost.smartsearch.SmartSearchClient
import java.util.concurrent.atomic.AtomicReference

object ModelController {
    enum class State {
        NOT_LOADED,
        LOADING,
        LOADED,
        ERRORED,
        NO_NATIVES,
    }

    private lateinit var model: EmbeddingModel
    private val stateRef = AtomicReference(State.NOT_LOADED)
    val state: State
        get() = stateRef.get()

    suspend fun init(modelFactory: EmbeddingModelFactory, onLoaded: (suspend (model: EmbeddingModel) -> Unit)? = null) {
        if (!stateRef.compareAndSet(State.NOT_LOADED, State.LOADING)) {
            return
        }

        try {
            val start = System.currentTimeMillis()
            model = modelFactory.create()
            stateRef.set(State.LOADED)
            SmartSearchClient.LOGGER.info("Loaded embedding model in ${System.currentTimeMillis() - start}ms")
            onLoaded?.invoke(model)
        } catch (e: UnsatisfiedLinkError) {
            stateRef.set(State.NO_NATIVES)
            SmartSearchClient.LOGGER.warn("Failed to load native libraries", e)
        } catch (e: Exception) {
            stateRef.set(State.ERRORED)
            SmartSearchClient.LOGGER.error("Failed to load embedding model", e)
        }
    }

    fun getModel(): EmbeddingModel {
        if (stateRef.get() != State.LOADED || !::model.isInitialized) {
            throw IllegalStateException("Tried to access embedding model before it was loaded")
        }
        return model
    }

    fun getQueryPrefix(): String {
        if (model is ArcticEmbedXs) {
            return "Represent this sentence for searching relevant passages: "
        }
        return ""
    }

    fun isReady(): Boolean = stateRef.get() == State.LOADED
}