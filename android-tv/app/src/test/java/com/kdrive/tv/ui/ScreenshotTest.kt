package com.kdrive.tv.ui

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.ImageLoader
import coil.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.Credentials
import com.kdrive.tv.data.DownloadMeta
import com.kdrive.tv.data.Episode
import com.kdrive.tv.data.MediaDetail
import com.kdrive.tv.data.MediaItem
import com.kdrive.tv.data.WatchingItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.kdrive.tv.ui.theme.K
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the screens to PNG on the JVM.
 *
 * This machine has no hypervisor, so no Android emulator can boot — these
 * files are the only way to actually look at the interface before it reaches
 * a television. Output lands in app/build/outputs/roborazzi/.
 *
 * Artwork is faked with flat fills: a JVM test cannot reach TMDb, and an
 * empty tile would misrepresent the layout it is there to check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w960dp-h540dp-television-xhdpi")
class ScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val api = ApiClient(Credentials("https://example.invalid", "test-key"))

    private fun fakeImages(): ImageLoader {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = FakeImageLoaderEngine.Builder()
            .default(ColorDrawable(0xFF3A3A46.toInt()))
            .build()
        val loader = ImageLoader.Builder(context).components { add(engine) }.build()
        Coil.setImageLoader(loader)
        return loader
    }

    private fun movie(id: String, title: String, year: Int?) = MediaItem(
        id = id,
        type = "movie",
        title = title,
        description = "A restless, razor-sharp story about someone refusing to " +
            "settle for the life that was picked out for them.",
        year = year,
        posterPath = "/poster$id.jpg",
        backdropPath = "/backdrop$id.jpg",
        status = "matched",
        driveFileId = "drive-$id",
    )

    private fun series(id: String, title: String, episodes: Int) = MediaItem(
        id = id,
        type = "series",
        title = title,
        description = "Six episodes about a town that keeps its worst secret in " +
            "plain sight.",
        posterPath = "/poster$id.jpg",
        backdropPath = "/backdrop$id.jpg",
        status = "matched",
        episodeCount = episodes,
    )

    /** The transport, mid-scrub: bar previewing a wound-to position and the
     * offset badge showing how far. */
    @Test
    fun `player controls`() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(K.Ink), contentAlignment = Alignment.BottomStart) {
                    Controls(
                        title = "My Brilliant Career  ·  S01E02",
                        position = downTo(14, 20),
                        buffered = downTo(21, 5),
                        duration = downTo(48, 30),
                        isPlaying = true,
                        seekTargetMs = downTo(16, 50),
                        hasAudioChoice = true,
                    )
                }
            }
        }
        rule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/player-controls.png",
            roborazziOptions = RoborazziOptions(),
        )
    }

    private fun downTo(minutes: Int, seconds: Int) = (minutes * 60L + seconds) * 1000L

    private fun watching(
        id: String,
        title: String,
        position: Double,
        duration: Double?,
        season: Int? = null,
        episode: Int? = null,
    ) = WatchingItem(
        id = id,
        type = if (season != null) "episode" else "movie",
        mediaId = "m-$id",
        title = title,
        description = "Picked up part-way through, which is the only thing this " +
            "shelf is for.",
        year = 2019,
        posterPath = "/poster$id.jpg",
        backdropPath = "/backdrop$id.jpg",
        positionSeconds = position,
        durationSeconds = duration,
        season = season,
        episode = episode,
    )

    /**
     * The Watching shelf on the home screen: landscape cards, each with its
     * own position bar, above the ordinary poster rows.
     *
     * The last entry deliberately has no duration — that is what every row
     * written before the client started reporting one looks like, and the
     * card has to stay the same height with an empty bar rather than
     * collapsing and making the row ragged.
     */
    @Test
    fun `browse with watching`() {
        val loader = fakeImages()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BrowseContent(
                    movies = listOf(movie("1", "Inception", 2010), movie("2", "Dune", 2021)),
                    series = listOf(series("8", "My Brilliant Career", 2)),
                    watching = listOf(
                        watching("w1", "Arrival", position = 2_400.0, duration = 6_960.0),
                        watching(
                            "w2",
                            "My Brilliant Career",
                            position = 300.0,
                            duration = 3_000.0,
                            season = 1,
                            episode = 2,
                        ),
                        watching("w3", "Heat", position = 900.0, duration = null),
                    ),
                    api = api,
                    imageLoader = loader,
                    onSelect = {},
                    onResume = {},
                    onSync = {},
                )
            }
        }
        rule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/browse-watching.png",
            roborazziOptions = RoborazziOptions(),
        )
    }

    /**
     * A download in whatever state, built by hand.
     *
     * Media3 has no test double for this, and the shelf's whole job is to
     * distinguish the states — a completed download from one at 6% that will
     * run out the moment the network does.
     */
    @OptIn(UnstableApi::class)
    private fun download(
        id: String,
        title: String,
        state: Int,
        percent: Float,
        subtitle: String? = null,
    ): Download {
        val progress = DownloadProgress().apply {
            percentDownloaded = percent
            bytesDownloaded = (percent * 10_000_000).toLong()
        }
        return Download(
            DownloadRequest.Builder(id, Uri.parse("https://example.invalid/$id"))
                .setData(
                    DownloadMeta(
                        title = title,
                        subtitle = subtitle,
                        posterPath = "/poster$id.jpg",
                        backdropPath = "/backdrop$id.jpg",
                    ).encode()
                )
                .build(),
            state,
            /* startTimeMs = */ 0L,
            /* updateTimeMs = */ 0L,
            /* contentLength = */ 1_000_000_000L,
            /* stopReason = */ 0,
            /* failureReason = */ 0,
            progress,
        )
    }

    /**
     * The Downloads shelf.
     *
     * Three states side by side, because they are only meaningful against
     * each other: finished, past the point where it can be watched, and not
     * yet there.
     */
    @OptIn(UnstableApi::class)
    @Test
    fun `browse with downloads`() {
        val loader = fakeImages()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BrowseContent(
                    movies = listOf(movie("1", "Inception", 2010)),
                    series = listOf(series("8", "My Brilliant Career", 2)),
                    downloads = listOf(
                        download("d1", "Arrival", Download.STATE_COMPLETED, 100f),
                        download(
                            "d2",
                            "My Brilliant Career",
                            Download.STATE_DOWNLOADING,
                            42f,
                            subtitle = "S01E02",
                        ),
                        download("d3", "Heat", Download.STATE_DOWNLOADING, 6f),
                    ),
                    api = api,
                    imageLoader = loader,
                    onSelect = {},
                    onSync = {},
                )
            }
        }
        rule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/browse-downloads.png",
            roborazziOptions = RoborazziOptions(),
        )
    }

    @Test
    fun browse() {
        val loader = fakeImages()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BrowseContent(
                    movies = listOf(
                        movie("1", "Inception", 2010),
                        movie("2", "The Nightingale", 2018),
                        movie("3", "Arrival", 2016),
                        movie("4", "Whiplash", 2014),
                        movie("5", "Sicario", 2015),
                        movie("6", "Dune", 2021),
                        movie("7", "Heat", 1995),
                    ),
                    series = listOf(
                        series("8", "My Brilliant Career", 2),
                        series("9", "Severance", 9),
                        series("10", "The Bear", 10),
                    ),
                    api = api,
                    imageLoader = loader,
                    onSelect = {},
                    // Non-null so the rail draws its Sync row.
                    onSync = {},
                )
            }
        }
        rule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/browse.png",
            roborazziOptions = RoborazziOptions(),
        )
    }

    @Test
    fun `movie detail`() {
        val loader = fakeImages()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DetailContent(
                    item = MediaDetail(
                        id = "1",
                        type = "movie",
                        title = "Inception",
                        description = "A thief who steals corporate secrets through " +
                            "dream-sharing technology is given the inverse task of " +
                            "planting an idea into the mind of a CEO.",
                        year = 2010,
                        posterPath = "/p.jpg",
                        backdropPath = "/b.jpg",
                        status = "matched",
                        driveFileId = "drive-1",
                    ),
                    api = api,
                    imageLoader = loader,
                    onPlay = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/movie-detail.png",
            roborazziOptions = RoborazziOptions(),
        )
    }

    @Test
    fun `series detail`() {
        val loader = fakeImages()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DetailContent(
                    item = MediaDetail(
                        id = "8",
                        type = "series",
                        title = "My Brilliant Career",
                        description = "Restless, razor-sharp and refusing to conform, " +
                            "17-year-old Sybylla Melvyn is dead set on writing her own " +
                            "story in an era that expects her to find a man and settle down.",
                        posterPath = "/p.jpg",
                        backdropPath = "/b.jpg",
                        status = "matched",
                        episodes = listOf(
                            Episode("e1", "8", 1, 1, "REVOLT", "Sybylla is sent to her posh grandmother to be polished for marriage.", "d1"),
                            Episode("e2", "8", 1, 2, "RUIN", "A proposal arrives from the last person she expected.", "d2"),
                            Episode("e3", "8", 1, 3, "RECKONING", "The manuscript reaches the wrong hands.", null),
                        ),
                    ),
                    api = api,
                    imageLoader = loader,
                    onPlay = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/series-detail.png",
            roborazziOptions = RoborazziOptions(),
        )
    }

    /**
     * The audio picker over a paused frame.
     *
     * Built from a real Tracks object rather than hand-written rows, so this
     * also checks the labelling: a language tag has to come out as a language
     * name, and an unsupported track has to stay listed and say so.
     */
    @Test
    fun `audio menu`() {
        val group = androidx.media3.common.Tracks.Group(
            androidx.media3.common.TrackGroup(
                format("en", 6, "ac-3"),
                format("hi", 2, "aac"),
                format("ja", 8, "dts"),
            ),
            false,
            intArrayOf(
                androidx.media3.common.C.FORMAT_HANDLED,
                androidx.media3.common.C.FORMAT_HANDLED,
                androidx.media3.common.C.FORMAT_UNSUPPORTED_SUBTYPE,
            ),
            booleanArrayOf(true, false, false),
        )
        val options = audioOptions(androidx.media3.common.Tracks(listOf(group)))

        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(K.Ink)) {
                    AudioMenu(options = options, cursor = 1)
                }
            }
        }
        rule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/audio-menu.png",
            roborazziOptions = RoborazziOptions(),
        )
    }

    private fun format(language: String, channels: Int, codec: String) =
        androidx.media3.common.Format.Builder()
            .setLanguage(language)
            .setChannelCount(channels)
            .setCodecs(codec)
            .setSampleMimeType("audio/mp4a-latm")
            .build()

    /** The launch lock, mid-entry and rejected — the state that has to read
     * clearly from across a room. */
    @Test
    fun `pin screen`() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PinScreen(purpose = PinPurpose.Unlock, error = "Wrong PIN.") {}
            }
        }
        rule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/pin-screen.png",
            roborazziOptions = RoborazziOptions(),
        )
    }
}
