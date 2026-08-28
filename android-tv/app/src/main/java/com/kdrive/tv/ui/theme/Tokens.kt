package com.kdrive.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The whole visual language in one place.
 *
 * Deliberately not a MaterialTheme colorScheme: almost nothing here maps onto
 * Material's semantic slots (there is no "primaryContainer" in a TV browse
 * grid), and reading tokens by name at the call site is clearer than
 * remembering which Material slot was repurposed for what.
 */
object K {

    // Near-black rather than pure black: OLED panels smear on true #000 during
    // horizontal scroll, and a slight lift keeps poster edges visible.
    val Ink = Color(0xFF0B0B0F)
    val Scrim = Color(0xFF000000)

    /** Cards and any raised surface. */
    val Surface = Color(0xFF1A1A21)
    val SurfaceHi = Color(0xFF26262F)

    /** The one saturated colour in the app. Spent only on the primary action
     * and the active nav indicator — everywhere else earns attention through
     * focus scale and white, the way the reference does. */
    val Accent = Color(0xFFE0464A)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFFA3A3AD)
    val TextFaint = Color(0xFF6E6E78)

    /** Focus is communicated by a white edge — the only thing on screen that
     * is ever pure white at full opacity besides primary text. */
    val Focus = Color(0xFFFFFFFF)

    // Type. One family (the platform sans), separated by weight and tracking
    // rather than by mixing faces — a 10-foot UI has to stay legible at size,
    // and decorative display faces fall apart at viewing distance.
    val Hero = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val PageTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold)
    val Section = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val CardTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)

    /** Uppercase micro-label for metadata rows. Tracking does the work that a
     * second typeface would otherwise do. */
    val Eyebrow = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp)

    // Layout constants shared across screens, so the rail and the content
    // gutter can never drift apart.
    val RailCollapsed = 72.dp
    val RailExpanded = 220.dp
    val Gutter = 48.dp
    val PosterW = 132.dp
    val PosterH = 198.dp

    /** The Watching shelf's landscape card. 16:9 to match the backdrop it
     * shows, and wider than a poster because it also carries a title, a time
     * remaining and a position bar. */
    val WatchCardW = 268.dp
    val WatchCardH = 151.dp
}
