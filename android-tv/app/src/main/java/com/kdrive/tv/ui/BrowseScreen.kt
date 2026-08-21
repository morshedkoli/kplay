package com.kdrive.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text as TvText
import coil.compose.AsyncImage
import coil.ImageLoader
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.MediaItem

/**
 * TV-focusable poster grid, D-pad navigable (LazyVerticalGrid handles focus
 * movement between cells natively). Mirrors app/LibraryGrid.js, except both
 * halves of the library are shown here as two labelled sections rather than
 * as separate /movies and /series routes — a TV user shouldn't have to
 * navigate a menu to reach half their library.
 */
@Composable
fun BrowseScreen(
    api: ApiClient,
    imageLoader: ImageLoader,
    onSelect: (MediaItem) -> Unit,
) {
    var movies by remember { mutableStateOf<List<MediaItem>?>(null) }
    var series by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val library = api.listLibrary()
            movies = library.movies
            series = library.series
        } catch (e: Exception) {
            error = e.message ?: "Failed to load the library"
        }
    }

    val loaded = movies != null && series != null
    val isEmpty = loaded && movies!!.isEmpty() && series!!.isEmpty()

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't load the library: $error", color = MaterialTheme.colorScheme.error)
        }
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing here yet — add files to your Drive folder, then scan from the web app.")
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (movies!!.isNotEmpty()) {
                item(span = { GridItemSpan(COLUMNS) }) { SectionHeading("Movies") }
                items(movies!!) { item ->
                    PosterCard(item, api, imageLoader) { onSelect(item) }
                }
            }
            if (series!!.isNotEmpty()) {
                item(span = { GridItemSpan(COLUMNS) }) { SectionHeading("Series") }
                items(series!!) { item ->
                    PosterCard(item, api, imageLoader) { onSelect(item) }
                }
            }
        }
    }
}

private const val COLUMNS = 6

@Composable
private fun SectionHeading(text: String) {
    TvText(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PosterCard(
    item: MediaItem,
    api: ApiClient,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val imageUrl = item.posterPath?.let { api.posterUrl(it) }

    Card(onClick = onClick, modifier = Modifier.aspectRatio(2f / 3f)) {
        Box(Modifier.fillMaxSize()) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    imageLoader = imageLoader,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No poster: the title is all the user has to go on, so give
                // it the whole tile rather than a generic placeholder icon.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TvText(item.title, color = Color.White, modifier = Modifier.padding(8.dp))
                }
            }

            if (item.isSeries) {
                TvText(
                    text = "${item.episodeCount} ep",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }
    }
}
