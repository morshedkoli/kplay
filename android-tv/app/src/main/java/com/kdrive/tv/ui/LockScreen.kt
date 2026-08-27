package com.kdrive.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.kdrive.tv.data.PIN_LENGTH
import com.kdrive.tv.ui.components.LogoBadge
import com.kdrive.tv.ui.theme.K
import kotlinx.coroutines.delay

/** What the screen is asking for, which is the only thing that differs
 * between unlocking, setting a new PIN, and confirming it. */
enum class PinPurpose(val title: String, val hint: String) {
    Unlock("Enter your PIN", "kPlay is locked."),
    Verify("Enter your current PIN", "Confirm it is you before changing the lock."),
    Create("Choose a PIN", "Four digits. You will need it every time the app opens."),
    Confirm("Enter it again", "Just to be sure the first one was what you meant."),
}

/**
 * Four-digit PIN entry.
 *
 * The keypad is on screen because a television remote cannot be relied on to
 * have number keys — plenty ship with nothing but a D-pad. Remotes that do
 * have them still work: the digit keys are handled directly, so anyone with
 * a full remote never has to walk the grid.
 *
 * `error` is passed in rather than owned here, because whether a PIN was
 * wrong is the caller's business: unlocking checks it against storage,
 * confirming checks it against the previous entry.
 */
@Composable
fun PinScreen(
    purpose: PinPurpose,
    error: String? = null,
    onCancel: (() -> Unit)? = null,
    onComplete: (String) -> Unit,
) {
    var entered by remember(purpose) { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }

    // Clearing on a new error is what lets the user simply try again: the
    // dots empty themselves the moment the caller rejects an entry.
    LaunchedEffect(error) {
        if (error != null) {
            delay(120)
            entered = ""
        }
    }

    fun press(digit: Char) {
        if (entered.length >= PIN_LENGTH) return
        entered += digit
        if (entered.length == PIN_LENGTH) onComplete(entered)
    }

    fun backspace() {
        entered = entered.dropLast(1)
    }

    LaunchedEffect(purpose) { runCatching { firstKey.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(K.Ink)
            // Preview, not onKeyEvent: the keypad buttons below are focusable,
            // and without intercepting first, a remote's digit keys would be
            // swallowed by whichever button happened to hold focus.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = DIGIT_KEYS[event.key]
                when {
                    digit != null -> { press(digit); true }
                    event.key == Key.Delete || event.key == Key.Backspace -> { backspace(); true }
                    event.key == Key.Back || event.key == Key.Escape -> {
                        // Back rubs out a digit while there is one to rub out,
                        // so a misfire costs one press rather than the whole
                        // entry.
                        if (entered.isNotEmpty()) {
                            backspace()
                            true
                        } else if (onCancel != null) {
                            onCancel()
                            true
                        } else {
                            // Nothing to cancel to on the launch lock: there is
                            // no screen behind this one to go back to.
                            true
                        }
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Side by side, not stacked. A television is wide and short — 540dp
        // of height at 1080p — and a stacked identity block plus a four-row
        // keypad runs off the bottom of the screen.
        Row(
            horizontalArrangement = Arrangement.spacedBy(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LogoBadge(size = 44.dp)

                Text(purpose.title, style = K.PageTitle, color = K.TextPrimary)
                Text(purpose.hint, style = K.Body, color = K.TextMuted)

                Dots(filled = entered.length, error = error != null)

                // Reserved whether or not there is an error, so nothing on
                // either side shifts the first time one appears.
                Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
                    if (error != null) Text(error, style = K.Body, color = K.Accent)
                }

                Text(
                    if (onCancel != null) "BACK  delete a digit, then cancel"
                    else "BACK  delete a digit",
                    style = K.Eyebrow,
                    color = K.TextFaint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Keypad(
                firstKeyFocusRequester = firstKey,
                onDigit = ::press,
                onBackspace = ::backspace,
            )
        }
    }
}

/** Number keys as they arrive from a remote's keypad, plus the numeric-pad
 * variants some send instead. */
private val DIGIT_KEYS: Map<Key, Char> = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3',
    Key.NumPad4 to '4', Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7',
    Key.NumPad8 to '8', Key.NumPad9 to '9',
)

@Composable
private fun Dots(filled: Int, error: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(vertical = 14.dp),
    ) {
        repeat(PIN_LENGTH) { index ->
            val on = index < filled
            val scale by animateFloatAsState(
                targetValue = if (on) 1f else 0.75f,
                animationSpec = tween(120),
                label = "pinDot",
            )
            Box(
                Modifier
                    .size((16 * scale).dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> K.Accent
                            on -> K.TextPrimary
                            else -> K.SurfaceHi
                        }
                    ),
            )
        }
    }
}

/**
 * Three columns of digits with zero and delete on the last row.
 *
 * Laid out as a real grid of focusable cells so D-pad focus search has
 * something geometric to work with — a wrapped flow would give the remote
 * unpredictable diagonal jumps.
 */
@Composable
private fun Keypad(
    firstKeyFocusRequester: FocusRequester,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("123", "456", "789").forEachIndexed { row, digits ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                digits.forEachIndexed { column, digit ->
                    KeyCell(
                        label = digit.toString(),
                        onClick = { onDigit(digit) },
                        modifier = if (row == 0 && column == 0) {
                            Modifier.focusRequester(firstKeyFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer68()
            KeyCell(label = "0", onClick = { onDigit('0') })
            KeyCell(label = "⌫", onClick = onBackspace)
        }
    }
}

/** Holds the bottom-left cell open so 0 stays under 8 rather than sliding
 * left, which is where every keypad in the world puts it. */
@Composable
private fun Spacer68() = Box(Modifier.size(68.dp))

@Composable
private fun KeyCell(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier
            .size(68.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) K.SurfaceHi else K.Surface)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) K.Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = K.PageTitle, color = if (focused) K.TextPrimary else K.TextMuted)
    }
}
