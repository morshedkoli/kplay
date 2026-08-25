package com.kdrive.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import com.kdrive.tv.ui.components.ActionButton
import com.kdrive.tv.ui.theme.K

/** First-run screen: server URL + device key, the same
 * `x-kdrive-device-key` a non-browser client sends (lib/auth.js). Persisted
 * via Prefs once validated by the caller (a real API call, not just format
 * checking) — see MainActivity. */
@Composable
fun LoginScreen(
    error: String?,
    busy: Boolean,
    onSubmit: (serverUrl: String, deviceKey: String) -> Unit,
) {
    var serverUrl by remember { mutableStateOf("http://") }
    var deviceKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(K.Ink).padding(64.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("kPlay", style = K.Hero, color = K.TextPrimary)
        Text(
            "Enter your kPlay server address and device key.",
            style = K.Body,
            color = K.TextMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL (e.g. http://192.168.1.10:3000)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.width(480.dp),
        )

        OutlinedTextField(
            value = deviceKey,
            onValueChange = { deviceKey = it },
            label = { Text("Device key (KDRIVE_DEVICE_KEY)") },
            modifier = Modifier.width(480.dp).padding(top = 16.dp),
        )

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        }

        ActionButton(
            label = if (busy) "Checking…" else "Sign in",
            onClick = { onSubmit(serverUrl.trim(), deviceKey.trim()) },
            enabled = !busy && serverUrl.isNotBlank() && deviceKey.isNotBlank(),
            modifier = Modifier.padding(top = 24.dp).align(Alignment.Start),
        )
    }
}
