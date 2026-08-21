package com.kdrive.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "kdrive_tv_prefs")

private val SERVER_URL = stringPreferencesKey("server_url")
private val DEVICE_KEY = stringPreferencesKey("device_key")

/** Persists the two things the TV app needs to talk to a KDrive server — the
 * same server URL + `x-kdrive-device-key` header scheme the web app's
 * non-browser clients already use (see lib/auth.js). */
class Prefs(private val context: Context) {

    val credentials: Flow<Credentials?> = context.dataStore.data.map { prefs ->
        val url = prefs[SERVER_URL]
        val key = prefs[DEVICE_KEY]
        if (url != null && key != null) Credentials(url, key) else null
    }

    suspend fun save(serverUrl: String, deviceKey: String) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_URL] = serverUrl.trimEnd('/')
            prefs[DEVICE_KEY] = deviceKey
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

data class Credentials(val serverUrl: String, val deviceKey: String)
