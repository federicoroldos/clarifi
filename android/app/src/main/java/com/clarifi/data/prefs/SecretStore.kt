package com.clarifi.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Device-only secrets, held behind a Keystore-backed master key.
 *
 * The desktop merely obfuscates its AI key in the workbook because it has nothing
 * better available; a phone does, so this uses it. The key is never exported,
 * never synced and never included in a backup.
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var aiApiKey: String?
        get() = prefs.getString(KEY_AI, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_AI) else putString(KEY_AI, value.trim())
            }.apply()
        }

    val hasAiKey: Boolean get() = aiApiKey != null

    /**
     * The Postgres connection string, the same one the desktop takes. It lives here
     * rather than in the ledger for the same reason the AI key does: it is a
     * credential, and credentials never travel in a backup or up to the cloud they
     * unlock. The desktop keeps its copy local too, in `cloud_config.json`.
     */
    var cloudDsn: String? by encrypted(KEY_CLOUD_DSN)

    private fun encrypted(name: String) = object : ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String? =
            prefs.getString(name, null)?.takeIf { it.isNotBlank() }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(name) else putString(name, value.trim())
            }.apply()
        }
    }

    private companion object {
        const val FILE = "clarifi_secrets"
        const val KEY_AI = "ai_api_key"
        const val KEY_CLOUD_DSN = "cloud_dsn"
    }
}
