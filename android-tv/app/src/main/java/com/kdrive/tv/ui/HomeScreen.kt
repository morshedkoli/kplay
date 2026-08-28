package com.kdrive.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.MediaItem
import androidx.media3.exoplayer.offline.Download
import com.kdrive.tv.data.Downloads
import com.kdrive.tv.data.meta
import com.kdrive.tv.data.WatchingItem
import com.kdrive.tv.ui.components.CarouselRow
import com.kdrive.tv.ui.components.DownloadsRow
import com.kdrive.tv.ui.components.NavRail
import com.kdrive.tv.ui.components.Section
import com.kdrive.tv.ui.components.WatchingRow
import com.kdrive.tv.ui.theme.K
import kotlinx.coroutines.launch

/**
 * The browse screen: a rail on the left, and to its right a hero above
 * horizontal rows.
 *
 * The rail is a layout sibling, not an overlay. Overlaying it looked better
 * but made it unreachable: focus search on Android is geometric, so a rail
 * drawn on top of the content it shares coordinates with gives the D-pad no
 * unambiguous direction to travel in. Real columns, real edges.
 *
 * The hero follows focus — move to any poster and the backdrop, title and
 * blurb become that title's. The data is already loaded, so a static header
 * becomes a preview surface for free.
 */
@Composable
fun HomeScreen(
    api: ApiClient,
    imageLoader: ImageLoader,
    onSelect: (MediaItem) -> Unit,
    onResume: (WatchingItem) -> Unit = {},
    onPlayDownload: (Download) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val downloads by remember { Downloads.flow(context) }
        .collectAsState(initial = emptyList())
    var movies by remember { mutableStateOf<List<MediaItem>?>(null) }
    var series by remember { mutableStateOf<List<MediaItem>?>(null) }
    var watching by remember { mutableStateOf<List<WatchingItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val library = api.listLibrary()
            movies = library.movies
            series = library.series
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the server"
        }
        // After the library, and never allowed to fail the screen: listWatching
        // swallows its own errors, so a server without the endpoint yet simply
        // shows no shelf instead of an error page over a working library.
        watching = api.listWatching()
    }

    // Coming back from the player re-enters this composable without the
    // activity ever having stopped, so the lifecycle observer below never
    // fires for it. Without a load here the Watching shelf would still show
    // the position the viewer had before they pressed play — the one place
    // the shelf being stale is guaranteed to be noticed.
    LaunchedEffect(Unit) { load() }

    // Load on entry, and again whenever the app comes back to the foreground.
    //
    // Entry alone was not enough: a television sits on this screen for hours,
    // and a title deleted in the admin panel meanwhile stayed on the rail —
    // still selectable, still opening a detail screen for something the server
    // no longer had. The library the remote sees is now whatever the server
    // says it is as of the last time someone picked the app up.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { load() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * Imports whatever is new in the Drive folder, then reloads the library.
     *
     * Before this the TV could only ever show what the web app had already
     * imported — dropping a file into Drive meant walking to a browser. The
     * scan is metadata-only, so the same request that serves the web sidebar
     * serves the remote.
     *
     * Reload only on a scan that changed something: a no-op sync should not
     * make the rows flicker and reset where focus was.
     */
    fun sync() {
        if (syncing) return
        syncing = true
        syncStatus = null
        scope.launch {
            try {
                val result = api.scanLibrary()
                syncStatus = result.summary()
                if (result.changed || (movies.isNullOrEmpty() && series.isNullOrEmpty())) load()
            } catch (e: Exception) {
                syncStatus = e.message ?: "Sync failed"
            } finally {
                syncing = false
            }
        }
    }

    when {
        // A server that cannot be reached is not the end of the screen when
        // there are downloads: those play with no server at all, and showing
        // an error page over the top of them would hide the one thing that
        // still works.
        error != null && downloads.isEmpty() ->
            Framed { Message("Can't reach your library", error!!) }

        error != null -> BrowseContent(
            movies = emptyList(),
            series = emptyList(),
            downloads = downloads,
            offline = true,
            api = api,
            imageLoader = imageLoader,
            onSelect = onSelect,
            onPlayDownload = onPlayDownload,
            onOpenSettings = onOpenSettings,
        )

        movies == null || series == null -> Framed {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = K.Accent)
            }
        }

        else -> BrowseContent(
            movies = movies!!,
            series = series!!,
            watching = watching,
            downloads = downloads,
            api = api,
            imageLoader = imageLoader,
            onSelect = onSelect,
            onResume = onResume,
            onPlayDownload = onPlayDownload,
            onOpenSettings = onOpenSettings,
            syncing = syncing,
            syncStatus = syncStatus,
            onSync = { sync() },
        )
    }
}

/**
 * The browse screen with its data already in hand.
 *
 * Split out from the fetching wrapper so it can be rendered from a screenshot
 * test with fixed content — this machine has no hypervisor and therefore no
 * emulator, so JVM rendering is the only way to actually look at the result.
 */
@Composable
internal fun BrowseContent(
    movies: List<MediaItem>,
    series: List<MediaItem>,
    watching: List<WatchingItem> = emptyList(),
    downloads: List<Download> = emptyList(),
    /** True when the library could not be fetched and only what is on the
     * device can be shown. The rail and the empty states both change wording
     * rather than pretending the server is simply empty. */
    offline: Boolean = false,
    api: ApiClient,
    imageLoader: ImageLoader,
    onSelect: (MediaItem) -> Unit,
    onResume: (WatchingItem) -> Unit = {},
    onPlayDownload: (Download) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    syncing: Boolean = false,
    syncStatus: String? = null,
    // Null in the screenshot tests, which render this with fixed data and
    // have no server to sync against.
    onSync: (() -> Unit)? = null,
) {
    // Offline opens on Downloads, because it is the only section with
    // anything in it — landing on an empty Home would read as a broken app.
    var section by remember { mutableStateOf(if (offline) Section.Downloads else Section.Home) }
    // The hero shows whatever holds focus, and focus can be on a poster or on
    // a Watching card — two different shapes. Both are flattened to the four
    // things the hero actually draws, so it never has to know which.
    var spotlight by remember {
        mutableStateOf(
            watching.firstOrNull()?.let { Spotlight.of(it) }
                ?: (movies.firstOrNull() ?: series.firstOrNull())?.let { Spotlight.of(it) }
        )
    }

    // Something must hold focus or the remote does nothing at all: Android
    // delivers key events to the focused view, and with no focus there is
    // nowhere for them to go. The first card claims it as soon as data lands.
    val firstCard = remember { FocusRequester() }
    var focusClaimed by remember { mutableStateOf(false) }

    LaunchedEffect(movies, series, watching, downloads) {
        val anything = movies.isNotEmpty() || series.isNotEmpty() ||
            watching.isNotEmpty() || downloads.isNotEmpty()
        if (!focusClaimed && anything) {
            focusClaimed = true
            // Guarded: requestFocus throws if the node isn't attached yet, and
            // a race here would take the whole screen down.
            runCatching { firstCard.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize().background(K.Ink)) {
        NavRail(
            selected = section,
            // Settings navigates away instead of filtering, so it must not
            // become the selected section — the rail would then sit lit on a
            // screen the user has already left.
            onSelect = { picked ->
                if (picked == Section.Settings) onOpenSettings() else section = picked
            },
            // Above the content so the expanded labels are not painted over,
            // while still occupying its own column in the layout.
            modifier = Modifier.zIndex(1f),
            syncing = syncing,
            syncStatus = syncStatus,
            onSync = onSync,
        )

        Box(Modifier.fillMaxSize()) {
            if (movies.isEmpty() && series.isEmpty() && downloads.isEmpty()) {
                Message(
                    if (offline) "Can't reach your library" else "Nothing here yet",
                    if (offline) {
                        "Nothing is downloaded to this device either, so there is nothing to play until the server is back."
                    } else {
                        "Drop video files into your Drive folder, then press Sync in the menu on the left."
                    },
                )
            } else if (section == Section.Downloads && downloads.isEmpty()) {
                Message(
                    "Nothing downloaded",
                    "Open a film or an episode and press Download. It plays here with no network at all.",
                )
            } else if (section == Section.Watching && watching.isEmpty()) {
                // Its own state rather than an empty page: the section exists
                // in the rail whether or not anything is in it, and landing on
                // a blank screen reads as a broken app.
                Message(
                    "Nothing in progress",
                    "Start a film or an episode and it will wait for you here.",
                )
            } else {
                Hero(item = spotlight, api = api, imageLoader = imageLoader)

                // A plain scrolling Column, not a LazyColumn. With only a few
                // rows there is nothing to gain from laziness, and a
                // LazyColumn does not compose off-screen rows — so focus
                // search moving down would find no target and simply stop,
                // which is exactly the "can't move the page" symptom.
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    // Holds the top of the screen open for the hero. Rows
                    // scroll up over it, which is what gives depth.
                    Spacer(Modifier.height(252.dp))

                    // Watching leads on Home, because something half-finished
                    // is what the remote was most likely picked up for, and
                    // it is the whole of the Watching section.
                    val showWatching =
                        (section == Section.Home || section == Section.Watching) &&
                            watching.isNotEmpty()
                    // Downloads lead on Home when the server is unreachable —
                    // they are then the only thing that can be played at all.
                    val showDownloads =
                        (section == Section.Home || section == Section.Downloads) &&
                            downloads.isNotEmpty()
                    val narrowed = section == Section.Watching || section == Section.Downloads
                    val showMovies = !narrowed && section != Section.Series && movies.isNotEmpty()
                    val showSeries = !narrowed && section != Section.Movies && series.isNotEmpty()
                    val downloadsFirst = offline || section == Section.Downloads

                    // Exactly one row may claim the initial focus, and it has
                    // to be the topmost one actually rendered — otherwise the
                    // page opens scrolled to a row that is not at the top.
                    // Exactly one row claims the initial focus, and it must be
                    // whichever is drawn first.
                    if (showDownloads && downloadsFirst) {
                        DownloadsRow(
                            title = "Downloaded",
                            downloads = downloads,
                            api = api,
                            imageLoader = imageLoader,
                            onPlay = onPlayDownload,
                            onFocusItem = { spotlight = Spotlight.of(it) },
                            firstItemFocusRequester = firstCard,
                        )
                    }
                    if (showWatching) {
                        WatchingRow(
                            title = "Continue watching",
                            items = watching,
                            api = api,
                            imageLoader = imageLoader,
                            onResume = onResume,
                            onFocusItem = { spotlight = Spotlight.of(it) },
                            firstItemFocusRequester =
                                if (showDownloads && downloadsFirst) null else firstCard,
                        )
                    }
                    if (showDownloads && !downloadsFirst) {
                        DownloadsRow(
                            title = "Downloaded",
                            downloads = downloads,
                            api = api,
                            imageLoader = imageLoader,
                            onPlay = onPlayDownload,
                            onFocusItem = { spotlight = Spotlight.of(it) },
                            firstItemFocusRequester = if (showWatching) null else firstCard,
                        )
                    }
                    val topClaimed = showWatching || showDownloads
                    if (showMovies) {
                        CarouselRow(
                            title = "Movies",
                            items = movies,
                            api = api,
                            imageLoader = imageLoader,
                            onSelect = onSelect,
                            onFocusItem = { spotlight = Spotlight.of(it) },
                            firstItemFocusRequester = if (topClaimed) null else firstCard,
                        )
                    }
                    if (showSeries) {
                        CarouselRow(
                            title = "Series",
                            items = series,
                            api = api,
                            imageLoader = imageLoader,
                            onSelect = onSelect,
                            onFocusItem = { spotlight = Spotlight.of(it) },
                            firstItemFocusRequester =
                                if (topClaimed || showMovies) null else firstCard,
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

/** Page background for the states that render before any content exists. */
@Composable
private fun Framed(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(K.Ink)) { content() }
}

/**
 * Backdrop, scrim and blurb for whatever currently holds focus.
 *
 * Crossfaded rather than cut, because focus moves fast under a D-pad and a
 * hard swap on every keypress reads as flicker.
 */
@Composable
private fun Hero(item: Spotlight?, api: ApiClient, imageLoader: ImageLoader) {
    Box(Modifier.fillMaxWidth().height(264.dp)) {
        Crossfade(targetState = item, animationSpec = tween(320), label = "heroArt") { current ->
            val art = current?.let { api.heroImageUrl(it.backdropPath, it.posterPath) }
            if (art != null) {
                AsyncImage(
                    model = art,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(K.Surface))
            }
        }

        // Two scrims: vertical so the rows below sit on solid ground,
        // horizontal so the title stays readable over a busy left edge.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to K.Ink.copy(alpha = 0.3f),
                    0.55f to K.Ink.copy(alpha = 0.75f),
                    1f to K.Ink,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to K.Ink.copy(alpha = 0.9f),
                    0.62f to K.Ink.copy(alpha = 0.08f),
                    1f to K.Ink.copy(alpha = 0f),
                )
            )
        )

        if (item != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = K.Gutter, bottom = 24.dp, end = K.Gutter)
                    .fillMaxWidth(0.58f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    item.title,
                    style = K.Hero,
                    color = K.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(item.meta, style = K.Eyebrow, color = K.TextMuted)
                if (!item.description.isNullOrBlank()) {
                    Text(
                        item.description,
                        style = K.Body,
                        color = K.TextMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * What the hero draws, independent of which kind of card focus is sitting on.
 *
 * A library poster and a Watching card carry different fields — one knows an
 * episode count, the other an episode number and a time remaining — but the
 * hero only ever renders four things. Flattening here keeps that from turning
 * into a branch inside the hero for every new card shape.
 */
internal data class Spotlight(
    val title: String,
    val meta: String,
    val description: String?,
    val backdropPath: String?,
    val posterPath: String?,
) {
    companion object {
        /** "SERIES · 2 EPISODES" / "MOVIE · 2010" — uppercase, tracked, never
         * a sentence. */
        fun of(item: MediaItem): Spotlight {
            val parts = mutableListOf<String>()
            parts += if (item.isSeries) "SERIES" else "MOVIE"
            if (item.isSeries) {
                parts += "${item.episodeCount} " +
                    if (item.episodeCount == 1) "EPISODE" else "EPISODES"
            } else if (item.year != null) {
                parts += item.year.toString()
            }
            if (item.status == "unmatched") parts += "NO MATCH"
            return Spotlight(
                title = item.title,
                meta = parts.joinToString("  ·  "),
                description = item.description,
                backdropPath = item.backdropPath,
                posterPath = item.posterPath,
            )
        }

        /** "OFFLINE · S01E04 · 100% DOWNLOADED" — a downloaded item knows only
         * what was saved with it, which is exactly the four things the hero
         * draws, plus how much of it is actually here. */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun of(download: Download): Spotlight {
            val meta = download.meta
            val parts = mutableListOf("OFFLINE")
            meta.subtitle?.takeIf { it.isNotBlank() }?.let { parts += it }
            parts += if (download.state == Download.STATE_COMPLETED) {
                "ON THIS DEVICE"
            } else {
                "${download.percentDownloaded.toInt()}% DOWNLOADED"
            }
            return Spotlight(
                title = meta.title,
                meta = parts.joinToString("  ·  "),
                description = null,
                backdropPath = meta.backdropPath,
                posterPath = meta.posterPath,
            )
        }

        /** "RESUME · S01E04 · 22 MIN LEFT" — the same tracked micro-label, but
         * saying what is actually useful about a part-watched title. */
        fun of(item: WatchingItem): Spotlight {
            val parts = mutableListOf("RESUME")
            item.episodeLabel?.let { parts += it }
            if (!item.isEpisode && item.year != null) parts += item.year.toString()
            item.remainingLabel?.let { parts += it.uppercase() }
            return Spotlight(
                title = item.title,
                meta = parts.joinToString("  ·  "),
                description = item.description,
                backdropPath = item.backdropPath,
                posterPath = item.posterPath,
            )
        }
    }
}

@Composable
private fun Message(headline: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(headline, style = K.PageTitle, color = K.TextPrimary)
            Text(detail, style = K.Body, color = K.TextMuted)
        }
    }
}
