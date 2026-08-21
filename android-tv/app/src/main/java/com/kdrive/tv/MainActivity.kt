package com.kdrive.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.MaterialTheme
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.Credentials
import com.kdrive.tv.data.Prefs
import com.kdrive.tv.data.authenticatedImageLoader
import com.kdrive.tv.ui.BrowseScreen
import com.kdrive.tv.ui.DetailScreen
import com.kdrive.tv.ui.LoginScreen
import com.kdrive.tv.ui.PlayerScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Distinguishes "still reading DataStore" from "read it, nothing saved" —
 * both would otherwise collapse to the same `null` and show the wrong screen
 * for a frame (or permanently, if DataStore is slow to emit). */
private sealed class AuthState {
    object Loading : AuthState()
    object LoggedOut : AuthState()
    data class LoggedIn(val credentials: Credentials) : AuthState()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(applicationContext)
        val authState = prefs.credentials.map { creds ->
            if (creds != null) AuthState.LoggedIn(creds) else AuthState.LoggedOut
        }

        setContent {
            MaterialTheme {
                val state by authState.collectAsState(initial = AuthState.Loading)
                val scope = rememberCoroutineScope()
                var loginError by remember { mutableStateOf<String?>(null) }
                var loginBusy by remember { mutableStateOf(false) }

                when (val s = state) {
                    is AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is AuthState.LoggedOut -> LoginScreen(
                        error = loginError,
                        busy = loginBusy,
                        onSubmit = { serverUrl, deviceKey ->
                            scope.launch {
                                loginBusy = true
                                loginError = null
                                try {
                                    // Verify the credentials actually work before saving them.
                                    ApiClient(Credentials(serverUrl.trimEnd('/'), deviceKey)).listLibrary()
                                    prefs.save(serverUrl, deviceKey)
                                } catch (e: Exception) {
                                    loginError = e.message ?: "Could not reach server"
                                } finally {
                                    loginBusy = false
                                }
                            }
                        },
                    )
                    is AuthState.LoggedIn -> AppNav(s.credentials)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppNav(credentials: Credentials) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(credentials) { ApiClient(credentials) }
    val imageLoader = remember(credentials) { authenticatedImageLoader(context, credentials) }
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "browse") {
        composable("browse") {
            BrowseScreen(
                api = api,
                imageLoader = imageLoader,
                onSelect = { item -> navController.navigate("detail/${item.id}") },
            )
        }
        composable(
            "detail/{mediaId}",
            arguments = listOf(navArgument("mediaId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getString("mediaId")!!
            DetailScreen(
                mediaId = mediaId,
                api = api,
                // A movie plays its own id, an episode plays its own. Detail
                // knows which, so it hands back the id to stream rather than
                // the item it was showing.
                onPlay = { playableId -> navController.navigate("player/$playableId") },
            )
        }
        composable(
            "player/{mediaId}",
            arguments = listOf(navArgument("mediaId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getString("mediaId")!!
            PlayerScreen(mediaId = mediaId, api = api)
        }
    }
}
