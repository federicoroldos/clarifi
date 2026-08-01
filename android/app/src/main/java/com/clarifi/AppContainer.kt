package com.clarifi

import android.content.Context
import com.clarifi.data.ai.AiClient
import com.clarifi.data.ai.ReceiptScanner
import com.clarifi.data.ai.StatementScanner
import com.clarifi.data.backup.JsonBackup
import com.clarifi.data.cloud.CloudSync
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.prefs.SecretStore
import com.clarifi.data.prefs.SettingsStore
import com.clarifi.data.repo.AccountRepository
import com.clarifi.data.repo.FixedRepository
import com.clarifi.data.repo.SummaryRepository
import com.clarifi.data.repo.TxnRepository
import com.clarifi.data.updates.ReleaseChecker

/**
 * The application's dependency graph, built once in [ClariFiApp].
 *
 * Everything is `by lazy` so nothing touches disk until the first screen that
 * needs it is opened - which keeps cold start fast and makes each dependency's
 * construction easy to trace when something goes wrong.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val secrets: SecretStore by lazy { SecretStore(appContext) }

    val database: ClariFiDatabase by lazy { ClariFiDatabase.build(appContext) }

    val accounts: AccountRepository by lazy { AccountRepository(database) }

    val txns: TxnRepository by lazy { TxnRepository(database, accounts) }

    val fixed: FixedRepository by lazy { FixedRepository(database, accounts, txns) }

    val summaries: SummaryRepository by lazy { SummaryRepository(accounts, txns, fixed) }

    val aiClient: AiClient by lazy { AiClient() }

    val receiptScanner: ReceiptScanner by lazy { ReceiptScanner(appContext, aiClient) }

    val statementScanner: StatementScanner by lazy { StatementScanner(appContext, aiClient) }

    val jsonBackup: JsonBackup by lazy { JsonBackup(database) }

    val releases: ReleaseChecker by lazy { ReleaseChecker(BuildConfig.VERSION_NAME) }

    val cloud: CloudSync by lazy { CloudSync(appContext, database, jsonBackup, secrets, settings) }
}
