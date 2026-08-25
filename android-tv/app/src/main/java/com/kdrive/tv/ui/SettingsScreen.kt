package com.kdrive.tv.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kdrive.tv.data.Credentials
import com.kdrive.tv.data.Prefs
import com.kdrive.tv.ui.theme.K
import kotlinx.coroutines.launch

/**
 * Where the PIN flow currently is.
 *
 * Changing a PIN and turning the lock off both start by proving you know the
 * current one, so `Verify` carries what to do once that succeeds. Setting one
 * is always two entries — a mistyped PIN you cannot see is a locked app you
 * cannot open.
 */
private sealed interface PinFlow {
    object None : PinFlow
    data class Verify(val then: AfterVerify) : PinFlow
    object Create : PinFlow
    data class Confirm(val first: String) : PinFlow
}

private enum class AfterVerify { Disable, Change }

/**
 * Settings.
 *
 * One screen, because there is one thing to configure. The app lock is a
 * household lock — it keeps the kids' film choices off the television, and
 * nothing more; the comment on Prefs.hashPin is honest about what a
 * four-digit PIN is worth against someone holding the device.
 */
@Composable
fun SettingsScreen(
    prefs: Prefs,
    credentials: Credentials,
    versionName: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val locked by prefs.lockEnabled.collectAsState(initial = false)

    var flow by remember { mutableStateOf<PinFlow>(PinFlow.None) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val firstRow = remember { FocusRequester() }
    LaunchedEffect(flow) {
        if (flow is PinFlow.None) runCatching { firstRow.requestFocus() }
    }

    fun finish(message: String) {
        flow = PinFlow.None
        error = null
        notice = message
    }

    when (val current = flow) {
        is PinFlow.None -> Unit

        is PinFlow.Verify -> {
            PinScreen(
                purpose = PinPurpose.Verify,
                error = error,
                onCancel = { flow = PinFlow.None; error = null },
            ) { entered ->
                scope.launch {
                    if (!prefs.verifyPin(entered)) {
                        error = "That is not your PIN."
                        return@launch
                    }
                    error = null
                    when (current.then) {
                        AfterVerify.Disable -> {
                            prefs.clearPin()
                            finish("App lock turned off.")
                        }

                        AfterVerify.Change -> flow = PinFlow.Create
                    }
                }
            }
            return
        }

        is PinFlow.Create -> {
            PinScreen(
                purpose = PinPurpose.Create,
                error = error,
                onCancel = { flow = PinFlow.None; error = null },
            ) { entered ->
                error = null
                flow = PinFlow.Confirm(entered)
            }
            return
        }

        is PinFlow.Confirm -> {
            PinScreen(
                purpose = PinPurpose.Confirm,
                error = error,
                onCancel = { flow = PinFlow.None; error = null },
            ) { entered ->
                if (entered != current.first) {
                    // Back to the first entry, not the second: the user has
                    // two different PINs in mind and needs to start over.
                    error = "Those did not match. Start again."
                    flow = PinFlow.Create
                    return@PinScreen
                }
                scope.launch {
                    prefs.setPin(entered)
                    finish("App lock is on.")
                }
            }
            return
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(K.Ink)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.key == Key.Back || event.key == Key.Escape) {
                    onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(
            Modifier
                .padding(horizontal = K.Gutter, vertical = 44.dp)
                .width(720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Settings", style = K.PageTitle, color = K.TextPrimary)

            if (notice != null) {
                Text(notice!!, style = K.Body, color = K.Accent)
            }

            SettingRow(
                title = "App lock",
                detail = if (locked) {
                    "On. kPlay asks for your PIN each time it opens."
                } else {
                    "Off. Anyone who turns on the television can open kPlay."
                },
                action = if (locked) "TURN OFF" else "TURN ON",
                modifier = Modifier.focusRequester(firstRow),
                onClick = {
                    notice = null
                    flow = if (locked) PinFlow.Verify(AfterVerify.Disable) else PinFlow.Create
                },
            )

            if (locked) {
                SettingRow(
                    title = "Change PIN",
                    detail = "Prove the current one, then pick a new one.",
                    action = "CHANGE",
                    onClick = {
                        notice = null
                        flow = PinFlow.Verify(AfterVerify.Change)
                    },
                )
            }

            Text(
                "Server",
                style = K.Eyebrow,
                color = K.TextFaint,
                modifier = Modifier.padding(top = 18.dp),
            )
            // The URL, never the device key. The key is the whole of this
            // app's authority over the server, and a television is the last
            // screen you want it printed on.
            InfoRow("Address", credentials.serverUrl)
            InfoRow("Version", versionName)

            Text(
                "BACK  return to the library",
                style = K.Eyebrow,
                color = K.TextFaint,
                modifier = Modifier.padding(top = 22.dp),
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    detail: String,
    action: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier
            .fillMaxWidth()
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
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(title, style = K.Section, color = K.TextPrimary)
            Text(detail, style = K.Body, color = K.TextMuted)
        }
        Text(action, style = K.Eyebrow, color = if (focused) K.Accent else K.TextFaint)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(label, style = K.Body, color = K.TextFaint, modifier = Modifier.width(120.dp))
        Text(
            value,
            style = K.Body,
            color = K.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
