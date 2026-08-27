package com.kdrive.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kdrive.tv.ui.theme.K

/**
 * The kPlay mark.
 *
 * A lowercase "k" whose arm and leg are a single play triangle — the stem is
 * the letter, the triangle is both the rest of the letter and the play glyph.
 * It replaces the plain "K" tile, which said the name and nothing else.
 *
 * The geometry is the same 48-unit drawing used by the web mark (app/Logo.js)
 * and the launcher icon (res/drawable/ic_launcher_foreground.xml), scaled to
 * whatever size it is given, so the three never drift apart.
 */
@Composable
fun LogoMark(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = K.TextPrimary,
) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 48f

        drawRoundRect(
            color = color,
            topLeft = Offset(10f * unit, 9f * unit),
            size = Size(6f * unit, 30f * unit),
            cornerRadius = CornerRadius(3f * unit, 3f * unit),
        )

        drawPath(
            path = Path().apply {
                moveTo(21f * unit, 12.6f * unit)
                lineTo(39.2f * unit, 24f * unit)
                lineTo(21f * unit, 35.4f * unit)
                close()
            },
            color = color,
        )
    }
}

/** The mark on its accent tile — the app's badge wherever one is wanted. */
@Composable
fun LogoBadge(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4.4f))
            .background(K.Accent),
        contentAlignment = Alignment.Center,
    ) {
        LogoMark(size = size * 0.66f)
    }
}
