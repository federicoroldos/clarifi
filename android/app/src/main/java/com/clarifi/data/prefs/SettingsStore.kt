package com.clarifi.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.clarifi.ui.theme.ThemeMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Device-local preferences that are *not* part of the ledger: the theme choice
 * and, later, the AI key and cloud connection string.
 *
 * These deliberately never travel through the cloud sync or a JSON export - the
 * desktop keeps its API key local too.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.from(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** Emits immediately with the current value, then on every change. */
    val themeModeFlow: Flow<ThemeMode> = keyFlow(KEY_THEME) { themeMode }

    /**
     * Whether the walkthrough has already run. Read once at startup, so a fresh
     * install gets the tour and every later launch goes straight to the dashboard.
     */
    var walkthroughSeen: Boolean
        get() = prefs.getBoolean(KEY_WALKTHROUGH_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_WALKTHROUGH_SEEN, value).apply()

    /** When this device last overwrote the cloud, and last let the cloud overwrite it. */
    var lastPush: String?
        get() = prefs.getString(KEY_LAST_PUSH, null)
        set(value) = prefs.edit().putString(KEY_LAST_PUSH, value).apply()

    var lastPull: String?
        get() = prefs.getString(KEY_LAST_PULL, null)
        set(value) = prefs.edit().putString(KEY_LAST_PULL, value).apply()

    private fun <T> keyFlow(key: String, read: () -> T): Flow<T> = callbackFlow {
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        const val FILE = "clarifi_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_WALKTHROUGH_SEEN = "walkthrough_seen"
        const val KEY_LAST_PUSH = "cloud_last_push"
        const val KEY_LAST_PULL = "cloud_last_pull"
    }
}
