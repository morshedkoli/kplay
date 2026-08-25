package com.kdrive.tv

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.Credentials
import com.kdrive.tv.data.Prefs
import com.kdrive.tv.data.authenticatedImageLoader
import com.kdrive.tv.ui.DetailScreen
import com.kdrive.tv.ui.HomeScreen
import com.kdrive.tv.ui.LoginScreen
import com.kdrive.tv.ui.PinPurpose
import com.kdrive.tv.ui.PinScreen
import com.kdrive.tv.ui.PlayerScreen
import com.kdrive.tv.ui.SettingsScreen
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

/**
 * Server config compiled into the APK.
 *
 * A release build carries both halves, so the app opens straight into the
 * library — no setup screen, ever. Entering a URL and a random key on a
 * television remote is genuinely painful, and this is a single-server app:
 * there is nothing for the viewer to decide.
 *
 * The URL has a committed default (see app/build.gradle.kts). The device key
 * does not, because it is a secret and this repository is not the place for
 * it — supply it with -PkdriveDeviceKey, KDRIVE_DEVICE_KEY in the
 * environment, or a line in the gitignored android-tv/local.properties.
 *
 * When the key is missing the app falls back to asking on first launch, which
 * keeps a plain `gradlew assembleDebug` from producing an APK that cannot
 * reach anything.
 *
 * NOTE: a baked key ends up readable inside the APK. Anyone who gets the file
 * can extract it and reach the server. That is the trade for zero-setup
 * install; rotate KDRIVE_DEVICE_KEY if an APK leaks.
 */
private val bakedInCredentials: Credentials? =
    if (BuildConfig.SERVER_URL.isNotBlank() && BuildConfig.DEVICE_KEY.isNotBlank()) {
        Credentials(BuildConfig.SERVER_URL, BuildConfig.DEVICE_KEY)
    } else {
        null
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(applicationContext)

        // A baked-in server wins over anything saved: the APK was built for
        // one specific server, so there is nothing to ask and nothing to
        // restore.
        val authState = prefs.credentials.map { saved ->
            val creds = bakedInCredentials ?: saved
            if (creds != null) AuthState.LoggedIn(creds) else AuthState.LoggedOut
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by authState.collectAsState(initial = AuthState.Loading)
                val scope = rememberCoroutineScope()
                var loginError by remember { mutableStateOf<String?>(null) }
                var loginBusy by remember { mutableStateOf(false) }

                when (val s = state) {
                    is AuthState.Loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
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

                    is AuthState.LoggedIn -> Locked(prefs) {
                        AppNav(s.credentials, prefs)
                    }
                }
            }
        }
    }
}

/**
 * The app-lock gate.
 *
 * Nothing behind it composes until the PIN is right — the library is not
 * merely covered, it is never built, so no artwork or title leaks around the
 * edge of the lock screen.
 *
 * It re-arms on ON_STOP rather than only at launch. "Asks for your PIN each
 * time it opens" has to include coming back from the home screen, or the lock
 * is one Home press away from being bypassed for the rest of the day.
 *
 * `remember`, not `rememberSaveable`: saved state survives process death, and
 * an unlocked flag that survives process death would let a relaunch walk
 * straight in.
 */
@Composable
private fun Locked(prefs: Prefs, content: @Composable () -> Unit) {
    val lockEnabled by prefs.lockEnabled.collectAsState(initial = null)
    var unlocked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) unlocked = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        // Still reading the setting. Showing the library for the frame it
        // takes would defeat the whole feature.
        lockEnabled == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        lockEnabled == false || unlocked -> content()

        else -> PinScreen(purpose = PinPurpose.Unlock, error = error) { entered ->
            scope.launch {
                if (prefs.verifyPin(entered)) {
                    error = null
                    unlocked = true
                } else {
                    error = "Wrong PIN."
                }
            }
        }
    }
}

@Composable
private fun AppNav(credentials: Credentials, prefs: Prefs) {
    val context = LocalContext.current
    val api = remember(credentials) { ApiClient(credentials) }
    val imageLoader = remember(credentials) { authenticatedImageLoader(context, credentials) }
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "browse") {
        composable("browse") {
            HomeScreen(
                api = api,
                imageLoader = imageLoader,
                onSelect = { item -> navController.navigate("detail/${item.id}") },
                onOpenSettings = { navController.navigate("settings") },
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
                imageLoader = imageLoader,
                // A movie plays its own id, an episode plays its own. Detail
                // knows which, so it hands back the id to stream rather than
                // the item it was showing, plus a label for the player.
                onPlay = { playableId, label ->
                    navController.navigate("player/$playableId?title=${Uri.encode(label)}")
                },
            )
        }
        composable("settings") {
            SettingsScreen(
                prefs = prefs,
                credentials = credentials,
                versionName = BuildConfig.VERSION_NAME,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            "player/{mediaId}?title={title}",
            arguments = listOf(
                navArgument("mediaId") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getString("mediaId")!!
            PlayerScreen(
                mediaId = mediaId,
                title = backStackEntry.arguments?.getString("title"),
                api = api,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
