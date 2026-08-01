package com.clarifi.data.cloud

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarifi.data.backup.JsonBackup
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.prefs.SecretStore
import com.clarifi.data.prefs.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live check against the real project: connect with the desktop's connection
 * string and Pull.
 *
 * Read-only as far as the cloud is concerned - it never pushes - so running it
 * cannot damage the data on the server.
 */
@RunWith(AndroidJUnit4::class)
class CloudPullLiveTest {

    private val dsn = System.getProperty("clarifi.dsn")
        ?: InstrumentationRegistry.getArguments().getString("dsn").orEmpty()

    @Test
    fun connectsAndPullsWithTheDesktopConnectionString() = runBlocking {
        if (dsn.isBlank()) {
            Log.i("CLOUD", "skipped: no dsn argument")
            return@runBlocking
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = ClariFiDatabase.build(context)
        val sync = CloudSync(
            context = context,
            database = database,
            backup = JsonBackup(database),
            secrets = SecretStore(context),
            settings = SettingsStore(context),
        )

        sync.save(dsn)
        Log.i("CLOUD", "connected to ${sync.description}")

        val counts = sync.pull()
        Log.i("CLOUD", "pulled accounts=${counts.accounts} txns=${counts.transactions} fixed=${counts.fixed}")

        val accounts = database.accountDao().allAccounts()
        accounts.take(8).forEach {
            Log.i("CLOUD", "  ${it.id} | ${it.bank} | ${it.currency} | ${it.balance} | archived=${it.archived}")
        }
        val txns = database.txnDao().allTxns()
        Log.i("CLOUD", "local now holds ${accounts.size} accounts and ${txns.size} transactions")
        txns.take(3).forEach {
            Log.i("CLOUD", "  txn ${it.id} ${it.date} ${it.type} ${it.amount} ${it.category} ${it.account}")
        }

        assertTrue("the cloud returned no accounts", accounts.isNotEmpty())
    }
}
