package org.polyfrost.smartsearch.model

import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.spi.model.embedding.EmbeddingModelFactory
import org.polyfrost.smartsearch.SmartSearchClient

object ModelController {
    enum class State {
        NOT_LOADED,
        LOADING,
        LOADED,
        ERRORED,
        NO_NATIVES,
    }

    private lateinit var model: EmbeddingModel
    var state: State = State.NOT_LOADED
        private set

    suspend fun init(modelFactory: EmbeddingModelFactory, onLoaded: (suspend (model: EmbeddingModel) -> Unit)? = null) {
        if (state != State.NOT_LOADED) {
            return
        }

        state = State.LOADING
        try {
            val start = System.currentTimeMillis()
            model = modelFactory.create()
            state = State.LOADED
            onLoaded?.invoke(model)
            SmartSearchClient.LOGGER.info("Loaded embedding model in ${System.currentTimeMillis() - start}ms")
        } catch (e: UnsatisfiedLinkError) {
            state = State.NO_NATIVES
            SmartSearchClient.LOGGER.warn("Failed to load native libraries", e)
        } catch (e: Exception) {
            state = State.ERRORED
            SmartSearchClient.LOGGER.error("Failed to load embedding model", e)
        }
    }

    fun getModel(): EmbeddingModel {
        if (state != State.LOADED || !::model.isInitialized) {
            throw IllegalStateException("Tried to access embedding model before it was loaded")
        }
        return model
    }

    fun isReady(): Boolean = state == State.LOADED
}