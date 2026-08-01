package com.clarifi.data.cloud

import android.content.Context
import androidx.room.withTransaction
import com.clarifi.data.backup.JsonBackup
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.prefs.SecretStore
import com.clarifi.data.prefs.SettingsStore
import com.github.jasync.sql.db.RowData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a Push or a Pull moved, for the message the user reads afterwards. */
data class CloudCounts(val accounts: Int, val transactions: Int, val fixed: Int)

/**
 * Manual whole-database Push and Pull against the same Postgres the desktop uses,
 * from the same connection string.
 *
 * The strategy is the desktop's: last write wins, no row-level merge, nothing
 * automatic. A device that has been offline for a week will happily overwrite
 * newer cloud data on its next Push, which is exactly why neither side syncs on
 * its own.
 */
class CloudSync(
    private val context: Context,
    private val database: ClariFiDatabase,
    private val backup: JsonBackup,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
) {

    val isConfigured: Boolean get() = secrets.cloudDsn != null

    /** Host and database with the password stripped, for the status line. */
    val description: String get() = secrets.cloudDsn?.let(PostgresCloud::describe).orEmpty()

    val lastPush: String? get() = settings.lastPush
    val lastPull: String? get() = settings.lastPull

    /**
     * Validates the string against the real server before storing it, so a typo is
     * caught here instead of at the moment the user tries to Push.
     */
    suspend fun save(dsn: String) = withContext(Dispatchers.IO) {
        val trimmed = dsn.trim()
        PostgresCloud.connectionUrl(trimmed)   // rejects anything that is not a DSN
        PostgresCloud(trimmed).ping()
        secrets.cloudDsn = trimmed
    }

    fun forget() {
        secrets.cloudDsn = null
        settings.lastPush = null
        settings.lastPull = null
    }

    /**
     * Local overwrites cloud, in one transaction, mirroring `_pg_from_wb`. The
     * tables are created first if the project has never been synced.
     */
    suspend fun push(): CloudCounts = withContext(Dispatchers.IO) {
        val cloud = requireCloud()

        val accounts = database.accountDao().allAccounts()
        val txns = database.txnDao().allTxns()
        val payments = database.fixedDao().allPayments()
        val applied = database.fixedDao().allApplied()
        // Belt and braces: the AI key lives in the keystore, not in `config`, but the
        // desktop does keep it there and a pulled database could have carried it in.
        val config = database.configDao().all().filterNot { it.key == CloudRows.AI_KEY }

        cloud.replaceAll(
            listOf(
                write(CloudSchema.CONFIG, config.map(CloudRows::configRow)),
                write(CloudSchema.ACCOUNTS, accounts.map(CloudRows::accountRow)),
                write(CloudSchema.TRANSACTIONS, txns.map(CloudRows::txnRow)),
                write(CloudSchema.FIXED_PAYMENTS, payments.map(CloudRows::fixedRow)),
                write(CloudSchema.FIXED_APPLIED, applied.map(CloudRows::appliedRow)),
            )
        )

        settings.lastPush = stamp()
        CloudCounts(accounts.size, txns.size, payments.size)
    }

    /**
     * Cloud overwrites local. Everything is downloaded before anything local is
     * touched, and the current data is exported first, so a half-finished Pull
     * cannot leave the phone with neither copy.
     */
    suspend fun pull(): CloudCounts = withContext(Dispatchers.IO) {
        val cloud = requireCloud()
        cloud.ensureSchema()

        val accounts = cloud.selectAll(CloudSchema.ACCOUNTS.name).map { it.toMap(CloudSchema.ACCOUNTS) }
        val txns = cloud.selectAll(CloudSchema.TRANSACTIONS.name).map { it.toMap(CloudSchema.TRANSACTIONS) }
        val payments = cloud.selectAll(CloudSchema.FIXED_PAYMENTS.name).map { it.toMap(CloudSchema.FIXED_PAYMENTS) }
        val applied = cloud.selectAll(CloudSchema.FIXED_APPLIED.name).map { it.toMap(CloudSchema.FIXED_APPLIED) }
        val config = cloud.selectAll(CloudSchema.CONFIG.name).map { it.toMap(CloudSchema.CONFIG) }

        writeBackup()

        val accountRows = accounts.map(CloudRows::account)
        val txnRows = txns.map(CloudRows::txn)
        val paymentRows = payments.map(CloudRows::fixed)
        val appliedRows = applied.map(CloudRows::applied)
        val configRows = config.map(CloudRows::config).filterNot { it.key == CloudRows.AI_KEY }

        database.withTransaction {
            database.txnDao().clear()
            database.fixedDao().clearAppliedTable()
            database.fixedDao().clearPayments()
            database.accountDao().clear()
            database.configDao().clear()

            database.accountDao().insertAll(accountRows)
            database.txnDao().insertAll(txnRows)
            database.fixedDao().insertAllPayments(paymentRows)
            database.fixedDao().insertAllApplied(appliedRows)
            database.configDao().putAll(configRows)
        }

        settings.lastPull = stamp()
        CloudCounts(accountRows.size, txnRows.size, paymentRows.size)
    }

    private fun write(table: CloudSchema.Table, rows: List<Map<String, Any?>>) =
        TableWrite(table.name, table.columns, rows)

    /** A driver row, read by the column names the desktop wrote. */
    private fun RowData.toMap(table: CloudSchema.Table): Map<String, Any?> =
        table.columns.associateWith { column -> runCatching { this[column] }.getOrNull() }

    private fun requireCloud(): PostgresCloud {
        val dsn = secrets.cloudDsn ?: throw CloudException("No database is connected yet.")
        return PostgresCloud(dsn)
    }

    /** The equivalent of the desktop's timestamped copy of the workbook. */
    private suspend fun writeBackup(): File? = runCatching {
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val file = File(dir, "before-pull-${stamp().replace(":", "-")}.json")
        file.writeText(backup.export())
        // Two is enough to undo a mistake; more is just a growing pile of ledgers.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(2)?.forEach { it.delete() }
        file
    }.getOrNull()

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
