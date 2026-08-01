package com.clarifi.data.backup

import androidx.room.withTransaction
import com.clarifi.core.model.AccountColors
import com.clarifi.core.model.Categories
import com.clarifi.core.model.ClariFiException
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Currencies
import com.clarifi.core.money.roundCurrency
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.db.ConfigEntry
import com.clarifi.data.db.FixedApplied
import com.clarifi.data.db.FixedPayment
import com.clarifi.data.db.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BackupSummary(val accounts: Int, val transactions: Int, val fixedPayments: Int)

/**
 * Whole-database backup in the desktop's `version: 2` JSON format.
 *
 * This is what makes the phone and the desktop interoperable while cloud sync is
 * on hold: a file exported from either side imports cleanly into the other. The
 * shape therefore has to match `modern_export` / `modern_import` exactly, down to
 * the `applied` map being keyed by month.
 *
 * The AI key is never included, on either platform.
 */
class JsonBackup(private val db: ClariFiDatabase) {

    suspend fun export(): String = withContext(Dispatchers.IO) {
        val accounts = db.accountDao().allAccounts()
        val txns = db.txnDao().allTxns()
        val fixed = db.fixedDao().allPayments()
        val applied = db.fixedDao().allApplied()
        val config = db.configDao().all()

        val appliedByMonth = JSONObject()
        applied.groupBy { it.yearMonth }.forEach { (month, entries) ->
            appliedByMonth.put(month, JSONArray().apply { entries.forEach { put(it.paymentId) } })
        }

        JSONObject()
            .put("version", 2)
            .put("exported_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .put("accounts", JSONArray().apply { accounts.forEach { put(it.toJson()) } })
            .put("config", JSONObject().apply { config.forEach { put(it.key, it.value) } })
            .put("txns", JSONArray().apply { txns.forEach { put(it.toJson()) } })
            .put("fixed", JSONArray().apply { fixed.forEach { put(it.toJson()) } })
            .put("applied", appliedByMonth)
            .toString(2)
    }

    /**
     * Replaces everything with the contents of a backup.
     *
     * Parsed and validated in full before a single row is touched, and then
     * written in one database transaction - a malformed file leaves the existing
     * data exactly as it was rather than half-replaced.
     */
    suspend fun import(text: String): BackupSummary {
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw ClariFiException("That file is not a ClariFi backup.")
        }

        val accounts = parseAccounts(root.optJSONArray("accounts"))
        if (accounts.isEmpty()) throw ClariFiException("The backup contains no valid accounts.")

        val accountIds = accounts.map { it.id }.toSet()
        val txns = parseTxns(root.optJSONArray("txns"), accountIds, accounts.first().id)
        val fixed = parseFixed(root.optJSONArray("fixed"), accountIds, accounts.first().id)
        val applied = parseApplied(root.optJSONObject("applied"), fixed.map { it.id }.toSet())
        val config = parseConfig(root.optJSONObject("config"))

        db.withTransaction {
            db.txnDao().clear()
            db.fixedDao().clearAppliedTable()
            db.fixedDao().clearPayments()
            db.accountDao().clear()
            db.configDao().clear()

            db.accountDao().insertAll(accounts)
            db.txnDao().insertAll(txns)
            db.fixedDao().insertAllPayments(fixed)
            db.fixedDao().insertAllApplied(applied)
            if (config.isNotEmpty()) db.configDao().putAll(config)
        }

        return BackupSummary(
            accounts = accounts.size,
            transactions = txns.size,
            fixedPayments = fixed.size,
        )
    }

    // ── writing ───────────────────────────────────────────────────────────────

    private fun Account.toJson() = JSONObject()
        .put("id", id)
        .put("bank", bank)
        .put("currency", currency)
        .put("balance", balance)
        .put("created_at", createdAt)
        .put("archived", archived)
        .put("color", displayColor)

    private fun Txn.toJson() = JSONObject()
        .put("id", id)
        .put("date", date)
        .put("description", description)
        .put("amount", amount)
        .put("category", category)
        .put("type", type)
        .put("account", account)
        .put("transfer_id", transferId ?: JSONObject.NULL)
        .put("counterpart", counterpart ?: JSONObject.NULL)
        .put("transfer_dir", transferDir ?: JSONObject.NULL)

    private fun FixedPayment.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("amount", amount)
        .put("account", account)
        .put("category", category)
        .put("day", day)
        .put("type", type)

    // ── reading ───────────────────────────────────────────────────────────────

    private fun parseAccounts(array: JSONArray?): List<Account> {
        val result = mutableListOf<Account>()
        val seen = mutableSetOf<String>()
        for (index in 0 until (array?.length() ?: 0)) {
            val row = array?.optJSONObject(index) ?: continue
            val id = row.optString("id").trim()
            val currency = Currencies.find(row.optString("currency")) ?: continue
            if (id.isEmpty() || !seen.add(id)) continue

            result += Account(
                id = id,
                bank = row.optString("bank").trim().ifEmpty { "Account" },
                currency = currency.id,
                balance = roundCurrency(currency, row.optDouble("balance", 0.0)),
                createdAt = row.optString("created_at").ifEmpty {
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                },
                archived = row.optBoolean("archived", false),
                color = row.optString("color").trim().ifEmpty { AccountColors.defaultFor(currency.id) },
            )
        }
        return result
    }

    /** Rows pointing at an account the backup does not contain are re-filed on the first one. */
    private fun parseTxns(array: JSONArray?, accountIds: Set<String>, fallback: String): List<Txn> {
        val result = mutableListOf<Txn>()
        val seenIds = mutableSetOf<Int>()
        var nextId = 1
        for (index in 0 until (array?.length() ?: 0)) {
            val row = array?.optJSONObject(index) ?: continue
            val account = row.optString("account").takeIf { it in accountIds } ?: fallback
            val type = row.optString("type").takeIf { value ->
                value in setOf(TxnType.FUND.wire, TxnType.EXPENSE.wire, TxnType.TRANSFER.wire)
            } ?: TxnType.EXPENSE.wire

            // Ids have to stay unique; a backup with duplicates gets renumbered
            // rather than rejected.
            var id = row.optInt("id", 0)
            if (id <= 0 || !seenIds.add(id)) {
                while (!seenIds.add(nextId)) nextId++
                id = nextId
            }
            nextId = maxOf(nextId, id + 1)

            val counterpart = row.optString("counterpart").takeIf { it.isNotEmpty() && it in accountIds }

            result += Txn(
                id = id,
                date = row.optString("date").takeIf { it.isNotBlank() } ?: Dates.today(),
                description = row.optString("description"),
                amount = kotlin.math.abs(row.optDouble("amount", 0.0)),
                category = Categories.normalize(row.optString("category")),
                type = type,
                account = account,
                transferId = row.optString("transfer_id").takeIf { it.isNotEmpty() && it != "null" },
                counterpart = counterpart,
                transferDir = row.optString("transfer_dir").takeIf { it == "out" || it == "in" },
            )
        }
        return result
    }

    private fun parseFixed(array: JSONArray?, accountIds: Set<String>, fallback: String): List<FixedPayment> {
        val result = mutableListOf<FixedPayment>()
        val seenIds = mutableSetOf<Int>()
        var nextId = 1
        for (index in 0 until (array?.length() ?: 0)) {
            val row = array?.optJSONObject(index) ?: continue

            var id = row.optInt("id", 0)
            if (id <= 0 || !seenIds.add(id)) {
                while (!seenIds.add(nextId)) nextId++
                id = nextId
            }
            nextId = maxOf(nextId, id + 1)

            result += FixedPayment(
                id = id,
                name = row.optString("name").trim().ifEmpty { "Fixed transaction" },
                amount = kotlin.math.abs(row.optDouble("amount", 0.0)),
                account = row.optString("account").takeIf { it in accountIds } ?: fallback,
                category = Categories.normalize(row.optString("category")),
                day = row.optInt("day", 1).coerceIn(1, 31),
                type = TxnType.fixedFrom(row.optString("type")).wire,
            )
        }
        return result
    }

    private fun parseApplied(applied: JSONObject?, knownIds: Set<Int>): List<FixedApplied> {
        val result = mutableListOf<FixedApplied>()
        val months = applied?.keys() ?: return result
        while (months.hasNext()) {
            val month = months.next()
            val ids = applied.optJSONArray(month) ?: continue
            for (index in 0 until ids.length()) {
                val paymentId = ids.optInt(index, -1)
                if (paymentId in knownIds) result += FixedApplied(paymentId, month)
            }
        }
        return result.distinct()
    }

    /** The AI key is local-only and never restored from a file, as on the desktop. */
    private fun parseConfig(config: JSONObject?): List<ConfigEntry> {
        val result = mutableListOf<ConfigEntry>()
        val keys = config?.keys() ?: return result
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "ai_api_key") continue
            result += ConfigEntry(key, config.opt(key)?.toString().orEmpty())
        }
        return result
    }
}
