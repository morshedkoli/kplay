package com.kdrive.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kdrive.tv.ui.theme.K

/**
 * Focus plumbing shared by everything the D-pad can land on.
 *
 * A TV has no cursor, so focus *is* the pointer: it has to be unmistakable at
 * three metres. Every focusable here grows slightly and gains a white edge,
 * and nothing unfocused ever does either — that consistency is what makes the
 * remote feel predictable.
 */

private const val FOCUS_SCALE = 1.06f
private const val FOCUS_ANIM_MS = 140

/**
 * A focusable, clickable box that scales and outlines when focus lands on it.
 *
 * `onFocused` fires as focus arrives, which is how the home screen keeps its
 * hero in sync with the highlighted card. The content lambda receives the
 * focus state so a card can reveal its title only while focused.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Int = 6,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(cornerRadius.dp)
    val scale by animateFloatAsState(
        targetValue = if (focused) FOCUS_SCALE else 1f,
        animationSpec = tween(FOCUS_ANIM_MS),
        label = "focusScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .border(
                BorderStroke(
                    if (focused) 3.dp else 0.dp,
                    if (focused) K.Focus else Color.Transparent,
                ),
                shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable(enabled)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        content(focused)
    }
}

/**
 * The primary action, styled as a solid pill.
 *
 * It fills with the accent only once focused — a permanently red button would
 * compete with the artwork behind it, and on this screen the artwork is the
 * point.
 */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)

    val background = when {
        focused && primary -> K.Accent
        focused -> K.TextPrimary
        primary -> K.TextPrimary.copy(alpha = 0.92f)
        else -> K.SurfaceHi
    }
    val foreground = when {
        focused && primary -> K.TextPrimary
        focused -> K.Ink
        primary -> K.Ink
        else -> K.TextPrimary
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leading != null) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Text(label, style = K.Body, color = foreground)
    }
}
