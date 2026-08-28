package com.kdrive.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

private val Context.dataStore by preferencesDataStore(name = "kdrive_tv_prefs")

private val SERVER_URL = stringPreferencesKey("server_url")
private val DEVICE_KEY = stringPreferencesKey("device_key")
private val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
private val PIN_HASH = stringPreferencesKey("pin_hash")
private val PIN_SALT = stringPreferencesKey("pin_salt")

/** Digits in the app-lock PIN. Four is what a television remote can enter
 * without becoming a chore, and this lock is a door, not a vault. */
const val PIN_LENGTH = 4

/**
 * How many times the PIN is re-hashed.
 *
 * A four-digit PIN is ten thousand possibilities, so anyone who can read this
 * file can find the PIN whatever we do — the honest goal is to make the
 * stored value useless at a glance and to cost a brute-forcer real time, not
 * to pretend this is a vault. Fifty thousand rounds is a few milliseconds
 * once and hours across a keyspace.
 */
private const val PIN_HASH_ROUNDS = 50_000

/**
 * Persists what the TV app needs between launches: the server it talks to,
 * and the app-lock settings.
 *
 * Server credentials are the same URL + `x-kdrive-device-key` header scheme
 * the web app's non-browser clients use (see lib/auth.js). A build with the
 * server baked in never writes them — see MainActivity.
 */
class Prefs(private val context: Context) {

    val credentials: Flow<Credentials?> = context.dataStore.data.map { prefs ->
        val url = prefs[SERVER_URL]
        val key = prefs[DEVICE_KEY]
        if (url != null && key != null) Credentials(url, key) else null
    }

    /**
     * Whether the app asks for a PIN on launch.
     *
     * Both halves must be present. A flag on its own with no stored hash
     * would lock the app behind a PIN that does not exist, which nothing
     * could then open.
     */
    val lockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[LOCK_ENABLED] == true && prefs[PIN_HASH] != null && prefs[PIN_SALT] != null
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

    /** Turns the lock on and stores the PIN. Replacing an existing PIN uses
     * this too — a fresh salt each time, so the same PIN never hashes twice
     * to the same value. */
    suspend fun setPin(pin: String) = withContext(Dispatchers.Default) {
        require(pin.length == PIN_LENGTH) { "PIN must be $PIN_LENGTH digits" }
        val salt = newSalt()
        val hash = hashPin(pin, salt)
        context.dataStore.edit { prefs ->
            prefs[PIN_SALT] = salt
            prefs[PIN_HASH] = hash
            prefs[LOCK_ENABLED] = true
        }
    }

    /** Turns the lock off and forgets the PIN. The caller is expected to have
     * verified the current one first — a lock anyone can switch off is not a
     * lock. */
    suspend fun clearPin() {
        context.dataStore.edit { prefs ->
            prefs.remove(PIN_HASH)
            prefs.remove(PIN_SALT)
            prefs[LOCK_ENABLED] = false
        }
    }

    /** True when this is the stored PIN. False when it isn't, and when no PIN
     * is stored at all. */
    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val prefs = context.dataStore.data.first()
        val salt = prefs[PIN_SALT] ?: return@withContext false
        val stored = prefs[PIN_HASH] ?: return@withContext false
        constantTimeEquals(stored, hashPin(pin, salt))
    }
}

private fun newSalt(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.toHex()
}

private fun hashPin(pin: String, salt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var value = (salt + pin).toByteArray(Charsets.UTF_8)
    repeat(PIN_HASH_ROUNDS) {
        digest.reset()
        value = digest.digest(value)
    }
    return value.toHex()
}

/** Compares without returning early, so the time taken says nothing about how
 * much of the value matched. */
private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

data class Credentials(val serverUrl: String, val deviceKey: String) {
    companion object {
        /**
         * Reads saved credentials on the calling thread.
         *
         * For the download service, which the system may construct after a
         * reboot with no activity around to hand it anything. Everything else
         * observes the flow above; this exists because a DataSource factory
         * has to produce headers synchronously and there is nothing to
         * suspend into.
         *
         * A build with the server baked in never reaches this — see
         * MainActivity, which publishes those credentials at launch.
         */
        fun loadBlocking(context: Context): Credentials? = runCatching {
            runBlocking(Dispatchers.IO) { Prefs(context.applicationContext).credentials.first() }
        }.getOrNull()
    }
}
