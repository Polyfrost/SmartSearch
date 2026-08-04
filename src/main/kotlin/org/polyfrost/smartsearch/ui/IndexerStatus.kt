package org.polyfrost.smartsearch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.polyfrost.oneconfig.api.config.v1.Visualizer
import org.polyfrost.oneconfig.internal.ui.components.Text
import org.polyfrost.oneconfig.internal.ui.themes.LocalTheme
import org.polyfrost.smartsearch.index.DataStore
import org.polyfrost.smartsearch.index.SearchIndex
import org.polyfrost.smartsearch.index.Embedder
import org.polyfrost.smartsearch.model.ModelController

private const val POLL_INTERVAL_MS = 500L

val IndexerStatusVisualizer = Visualizer { IndexerStatus() }

private data class IndexerSnapshot(
    val modelState: ModelController.State,
    val ingesting: Boolean,
    val embedding: Boolean,
    val pending: Int,
    val documents: Int,
    val embedded: Int,
) {
    companion object {
        fun capture(): IndexerSnapshot {
            val stats = DataStore.stats
            return IndexerSnapshot(
                modelState = ModelController.state,
                ingesting = DataStore.status == SearchIndex.Status.INGESTING,
                embedding = Embedder.isRunning(),
                pending = Embedder.pending,
                documents = stats.documents,
                embedded = stats.embedded,
            )
        }
    }
}

@Composable
private fun IndexerStatus() {
    val theme = LocalTheme.current
    var snapshot by remember { mutableStateOf(IndexerSnapshot.capture()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            snapshot = IndexerSnapshot.capture()
        }
    }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            snapshot.headline(),
            color = snapshot.headlineColor(theme.textColor),
            fontSize = 14.sp,
            textAlign = TextAlign.End,
        )
        Text(
            snapshot.detail(),
            color = theme.textColorSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
        )
    }
}

private val ERROR_COLOR = Color(0xFFE05252)

private fun IndexerSnapshot.headline(): String = when (modelState) {
    ModelController.State.NOT_LOADED -> "Model not loaded"
    ModelController.State.LOADING -> "Loading model..."
    ModelController.State.ERRORED -> "Model failed to load"
    ModelController.State.NO_NATIVES -> "Unsupported platform"
    ModelController.State.LOADED -> when {
        ingesting -> "Indexing..."
        embedding -> "Embedding... ($pending left)"
        else -> "Ready"
    }
}

private fun IndexerSnapshot.headlineColor(default: Color): Color = when (modelState) {
    ModelController.State.ERRORED, ModelController.State.NO_NATIVES -> ERROR_COLOR
    else -> default
}

private fun IndexerSnapshot.detail(): String = when (modelState) {
    ModelController.State.ERRORED -> "Disabled SmartSearch, see the logs for more details"
    ModelController.State.NO_NATIVES -> "Disabled SmartSearch, no native libraries for this OS"
    ModelController.State.NOT_LOADED, ModelController.State.LOADING, ModelController.State.LOADED -> indexLine()
}

private fun IndexerSnapshot.indexLine(): String =
    "$documents ${if (documents == 1) "entry" else "entries"}, $embedded embedded"
