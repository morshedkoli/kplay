package com.kdrive.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.Movie

@Composable
fun DetailScreen(
    movieId: String,
    api: ApiClient,
    onPlay: (Movie) -> Unit,
) {
    var movie by remember { mutableStateOf<Movie?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(movieId) {
        try {
            movie = api.getMovie(movieId)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load movie"
        }
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        movie == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            val m = movie!!
            val playable = m.status != "processing" && m.driveFileId != null
            Column(Modifier.fillMaxSize().padding(48.dp)) {
                Text(
                    if (m.year != null) "${m.title} (${m.year})" else m.title,
                    style = MaterialTheme.typography.headlineLarge,
                )
                if (m.description != null) {
                    Text(
                        m.description,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp).width(720.dp),
                    )
                }
                Text(
                    if (m.status == "unmatched") "Not matched on TMDb" else m.status,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )

                Button(
                    onClick = { onPlay(m) },
                    enabled = playable,
                    modifier = Modifier.padding(top = 32.dp),
                ) {
                    Text(if (playable) "Play" else "Not ready yet")
                }
            }
        }
    }
}
