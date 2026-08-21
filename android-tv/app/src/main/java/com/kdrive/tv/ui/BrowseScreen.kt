package com.kdrive.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text as TvText
import coil.compose.AsyncImage
import coil.ImageLoader
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.Movie

/** TV-focusable poster grid, D-pad navigable (TvLazyVerticalGrid handles
 * focus movement between cells natively). Mirrors app/Movies.js's grid. */
@Composable
fun BrowseScreen(
    api: ApiClient,
    imageLoader: ImageLoader,
    onSelect: (Movie) -> Unit,
) {
    var movies by remember { mutableStateOf<List<Movie>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            movies = api.listMovies()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load movies"
        }
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't load movies: $error", color = MaterialTheme.colorScheme.error)
        }
        movies == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        movies!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No movies yet — add one from the KDrive web app.")
        }
        else -> TvLazyVerticalGrid(
            columns = TvGridCells.Fixed(6),
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(movies!!) { movie ->
                val imageUrl = movie.posterPath?.let { api.posterUrl(it) }
                PosterCard(movie = movie, imageUrl = imageUrl, imageLoader = imageLoader, onClick = { onSelect(movie) })
            }
        }
    }
}

@Composable
private fun PosterCard(movie: Movie, imageUrl: String?, imageLoader: ImageLoader, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.aspectRatio(2f / 3f)) {
        Box(Modifier.fillMaxSize()) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    imageLoader = imageLoader,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TvText(movie.title, color = Color.White, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
