package com.clarifi.data.prefs

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because the point of the class is the Keystore, which only exists
 * on a device.
 *
 * The case that matters is the one that shipped broken in 0.3.2: a
 * `clarifi_secrets.xml` that the current master key cannot open. Android's Auto
 * Backup restored the file onto a fresh install while the key stayed behind on
 * the old one, and building the store threw `AEADBadTagException` during startup,
 * so the app could not be opened at all. The file is now excluded from backups,
 * but a Keystore reset can still separate the two, and a credential store is
 * never worth an unopenable app.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clean() = context.deleteSharedPreferences(FILE).let { }

    @After
    fun tidy() = context.deleteSharedPreferences(FILE).let { }

    @Test
    fun keepsWhatItIsGiven() {
        SecretStore(context).aiApiKey = "gsk_example"
        assertEquals("gsk_example", SecretStore(context).aiApiKey)
    }

    @Test
    fun startsOverWhenThePrefsCannotBeDecrypted() {
        // A keyset no key can open, which is what a restored backup leaves behind.
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEYSET, "12a901deadbeefdeadbeefdeadbeef")
            .commit()

        val store = SecretStore(context)

        assertNull(store.aiApiKey)
        assertNull(store.cloudDsn)

        // And it is a working store afterwards, not a husk that swallows writes.
        store.aiApiKey = "gsk_after_recovery"
        assertEquals("gsk_after_recovery", SecretStore(context).aiApiKey)
    }

    private companion object {
        const val FILE = "clarifi_secrets"
        const val KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    }
}
