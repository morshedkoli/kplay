package com.kdrive.tv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Glyphs the app needs that `material-icons-core` does not carry.
 *
 * Core ships around fifty icons and none of them says "resume". The extended
 * artifact does, but it is a multi-thousand-icon dependency to pull in for a
 * single 24dp shape, so the one shape is declared here instead — same path
 * data, same licence, none of the weight.
 */
object KIcons {

    /** Two bars. Core ships PlayArrow but no Pause, which left the transport
     * drawing its paused state as the text "❚❚" — a pair of box-drawing
     * characters whose weight and size are whatever font the television
     * happens to use. */
    val Pause: ImageVector by lazy { icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z") }

    /** A speaker with waves — the mark for the soundtrack list. */
    val Audio: ImageVector by lazy {
        icon(
            "Audio",
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 " +
                "2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 " +
                "7-4.49 7-8.77s-2.99-7.86-7-8.77z",
        )
    }

    /** Double triangles, for the 30-second transport keys. */
    val FastForward: ImageVector by lazy {
        icon("FastForward", "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z")
    }

    val FastRewind: ImageVector by lazy {
        icon("FastRewind", "M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z")
    }

    /**
     * A clock with a counter-clockwise arrow — the standard "history" mark.
     *
     * Read at three metres it is a circle with a notch, which is enough to
     * tell it apart from the solid triangle next to it in the rail. That
     * separation is the whole job: the rail's entries are recognised by
     * silhouette long before anyone reads the labels.
     */
    val Watching: ImageVector by lazy {
        icon(
            "Watching",
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 " +
                "3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 " +
                "10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21" +
                "-3.5-2.08V8H12z",
        )
    }

    /** One 24dp path on a 24-unit viewport, which is every icon here. The
     * black fill is only what the vector declares before `Icon()` tints it at
     * the call site, exactly as a Material icon does. */
    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        }.build()
}
