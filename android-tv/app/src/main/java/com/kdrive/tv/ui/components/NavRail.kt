package com.kdrive.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kdrive.tv.ui.theme.K

enum class Section(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),

    // Sits directly under Home because it is the entry most often wanted and
    // the one nearest the top is the cheapest to reach on a remote.
    Watching("Watching", KIcons.Watching),
    Movies("Movies", Icons.Filled.PlayArrow),
    Series("Series", Icons.Filled.List),

    // The one section that works with no server at all, so it sits below the
    // three that need one.
    Downloads("Downloads", KIcons.DownloadDone),

    // An entry that navigates away rather than filtering the rows below it.
    // HomeScreen treats it as an action, so it never becomes the selected
    // section and the rail never sits on a state the content cannot show.
    Settings("Settings", Icons.Filled.Settings),
}

/**
 * Left navigation rail.
 *
 * Sits collapsed to a strip of glyphs and expands to show labels only while
 * something in it holds focus — the same trade the reference makes, and the
 * right one here: the rail is visited rarely, the artwork is looked at
 * constantly, so the rail should surrender its width when it isn't in use.
 *
 * It expands over the content rather than pushing it, so rows never reflow
 * under the user while they are moving through them.
 */
@Composable
fun NavRail(
    selected: Section,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
    syncing: Boolean = false,
    syncStatus: String? = null,
    onSync: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (expanded) K.RailExpanded else K.RailCollapsed,
        animationSpec = tween(180),
        label = "railWidth",
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            // A horizontal fade rather than a hard edge, so the rail reads as
            // sitting above the artwork instead of cutting a slab out of it.
            .background(
                Brush.horizontalGradient(
                    listOf(K.Ink, K.Ink.copy(alpha = if (expanded) 0.97f else 0.86f), Color.Transparent),
                )
            )
            // focusGroup keeps the rail a single stop in the traversal: focus
            // enters it once from the left, moves within it, and leaves right.
            .focusGroup()
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Brand(expanded)
        Spacer(Modifier.height(28.dp))
        Section.values().forEach { section ->
            RailItem(
                section = section,
                selected = section == selected,
                expanded = expanded,
                onClick = { onSelect(section) },
            )
        }

        if (onSync != null) {
            // Pushed to the far end, away from the sections, because it acts
            // on the library rather than navigating it.
            Spacer(Modifier.weight(1f))
            RailAction(
                label = "Sync",
                busy = syncing,
                status = syncStatus,
                expanded = expanded,
                onClick = onSync,
            )
        }
    }
}

@Composable
private fun Brand(expanded: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // One accent tile carrying the mark. At three metres the shape is what
        // reads, not a logotype — and the shape is a play button, which says
        // what the app is before the name beside it does.
        LogoBadge(size = 30.dp)
        if (expanded) Text("kPlay", style = K.Section, color = K.TextPrimary)
    }
}

@Composable
private fun RailItem(
    section: Section,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    val tint by animateColorAsState(
        targetValue = when {
            focused -> K.TextPrimary
            selected -> K.TextPrimary
            else -> K.TextFaint
        },
        animationSpec = tween(140),
        label = "railTint",
    )

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) K.SurfaceHi else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Every entry always draws a glyph. The first version showed only a
        // dot, and an unselected dot was transparent, so two of the three menu
        // items were invisible — there was nothing to see or aim at.
        Icon(
            imageVector = section.icon,
            contentDescription = section.label,
            tint = if (selected) K.Accent else tint,
            modifier = Modifier.size(24.dp),
        )
        if (expanded) Text(section.label, style = K.Body, color = tint)
    }
}

/**
 * The Sync row.
 *
 * An action, not a destination, so it sits below the sections separated by
 * space rather than among them — the same shape the web sidebar settled on.
 * Its status line only renders while the rail is expanded, which is exactly
 * when someone is looking at it.
 */
@Composable
private fun RailAction(
    label: String,
    busy: Boolean,
    status: String?,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    val tint by animateColorAsState(
        targetValue = if (focused) K.TextPrimary else K.TextFaint,
        animationSpec = tween(140),
        label = "railActionTint",
    )

    // One continuous turn while a scan is in flight. A scan takes as long as
    // it takes, with no percentage to report, so motion is the only honest
    // signal that it is still working.
    val spin = rememberInfiniteTransition(label = "syncSpin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "syncAngle",
    )

    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (focused) K.SurfaceHi else Color.Transparent)
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    // Pressing it twice mid-scan would run two imports over
                    // the same folder and race each other to create the same
                    // rows, so the second press does nothing.
                    enabled = !busy,
                    onClick = onClick,
                )
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = label,
                tint = if (busy) K.Accent else tint,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(if (busy) angle else 0f),
            )
            if (expanded) {
                Text(if (busy) "Syncing…" else label, style = K.Body, color = tint)
            }
        }
        if (expanded && !status.isNullOrBlank()) {
            Text(
                status,
                style = K.Eyebrow,
                color = K.TextFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            )
        }
    }
}
