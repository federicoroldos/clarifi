package com.clarifi.data.cloud

import com.clarifi.data.db.Account
import com.clarifi.data.db.ConfigEntry
import com.clarifi.data.db.FixedApplied
import com.clarifi.data.db.FixedPayment
import com.clarifi.data.db.Txn

/**
 * The cloud schema, one for one with the desktop's.
 *
 * Tables are `SHEETS` prefixed `clarifi_`; the column types come from
 * `_PG_INT_COLS` / `_PG_FLOAT_COLS` / `_PG_BOOL_COLS` in app.py. Types are keyed by
 * column because the same name differs across tables: `transactions.id` and
 * `fixed_payments.id` are INTEGER while **`accounts.id` is TEXT** (account ids are
 * strings like `usd` or `acct_3f4a8b2c`).
 */
object CloudSchema {

    data class Table(
        val name: String,
        val columns: List<String>,
        private val ints: Set<String> = emptySet(),
        private val floats: Set<String> = emptySet(),
        private val bools: Set<String> = emptySet(),
    ) {
        fun type(column: String): String = when (column) {
            in ints -> "INTEGER"
            in floats -> "DOUBLE PRECISION"
            in bools -> "BOOLEAN"
            else -> "TEXT"
        }
    }

    val ACCOUNTS = Table(
        name = "clarifi_accounts",
        columns = listOf("id", "bank", "currency", "balance", "created_at", "archived", "color"),
        floats = setOf("balance"),
        bools = setOf("archived"),
    )

    val TRANSACTIONS = Table(
        name = "clarifi_transactions",
        columns = listOf(
            "id", "date", "description", "amount", "category", "type", "account",
            "transfer_id", "counterpart", "transfer_dir",
        ),
        ints = setOf("id"),
        floats = setOf("amount"),
    )

    val FIXED_PAYMENTS = Table(
        name = "clarifi_fixed_payments",
        columns = listOf("id", "name", "amount", "account", "category", "day", "type"),
        ints = setOf("id", "day"),
        floats = setOf("amount"),
    )

    val FIXED_APPLIED = Table(
        name = "clarifi_fixed_applied",
        columns = listOf("payment_id", "year_month"),
        ints = setOf("payment_id"),
    )

    val CONFIG = Table(name = "clarifi_config", columns = listOf("key", "value"))

    /** Written and read in this order; the desktop's `SHEETS` order. */
    val TABLES = listOf(CONFIG, ACCOUNTS, TRANSACTIONS, FIXED_PAYMENTS, FIXED_APPLIED)
}

/**
 * Room rows in and out of the cloud's columns.
 *
 * Pure functions on plain maps, so the mapping can be tested without a database or
 * a network. A row written by the phone has to be a row the desktop reads back
 * without knowing which device wrote it, so **this and app.py change together.**
 */
object CloudRows {

    /** The desktop keeps its AI key in `config`; it must never travel to the cloud. */
    const val AI_KEY = "ai_api_key"

    fun accountRow(account: Account): Map<String, Any?> = mapOf(
        "id" to account.id,
        "bank" to account.bank,
        "currency" to account.currency,
        "balance" to account.balance,
        "created_at" to account.createdAt,
        "archived" to account.archived,
        "color" to account.color,
    )

    fun account(row: Map<String, Any?>) = Account(
        id = row.text("id"),
        bank = row.text("bank"),
        currency = row.text("currency").lowercase(),
        balance = row.number("balance"),
        createdAt = row.text("created_at"),
        archived = row.flag("archived"),
        color = row.text("color"),
    )

    fun txnRow(txn: Txn): Map<String, Any?> = mapOf(
        "id" to txn.id,
        "date" to txn.date,
        "description" to txn.description,
        "amount" to txn.amount,
        "category" to txn.category,
        "type" to txn.type,
        "account" to txn.account,
        "transfer_id" to txn.transferId,
        "counterpart" to txn.counterpart,
        "transfer_dir" to txn.transferDir,
    )

    fun txn(row: Map<String, Any?>) = Txn(
        id = row.number("id").toInt(),
        date = row.text("date"),
        description = row.text("description"),
        amount = row.number("amount"),
        category = row.text("category"),
        type = row.text("type"),
        account = row.text("account"),
        transferId = row.textOrNull("transfer_id"),
        counterpart = row.textOrNull("counterpart"),
        transferDir = row.textOrNull("transfer_dir"),
    )

    fun fixedRow(payment: FixedPayment): Map<String, Any?> = mapOf(
        "id" to payment.id,
        "name" to payment.name,
        "amount" to payment.amount,
        "account" to payment.account,
        "category" to payment.category,
        "day" to payment.day,
        "type" to payment.type,
    )

    fun fixed(row: Map<String, Any?>) = FixedPayment(
        id = row.number("id").toInt(),
        name = row.text("name"),
        amount = row.number("amount"),
        account = row.text("account"),
        category = row.text("category"),
        day = row.number("day").toInt().coerceIn(1, 31),
        type = row.text("type").ifBlank { "expense" },
    )

    fun appliedRow(applied: FixedApplied): Map<String, Any?> = mapOf(
        "payment_id" to applied.paymentId,
        "year_month" to applied.yearMonth,
    )

    fun applied(row: Map<String, Any?>) = FixedApplied(
        paymentId = row.number("payment_id").toInt(),
        yearMonth = row.text("year_month"),
    )

    fun configRow(entry: ConfigEntry): Map<String, Any?> = mapOf(
        "key" to entry.key,
        "value" to entry.value,
    )

    fun config(row: Map<String, Any?>) = ConfigEntry(
        key = row.text("key"),
        value = row.text("value"),
    )

    private fun Map<String, Any?>.text(column: String): String = this[column]?.toString().orEmpty()

    private fun Map<String, Any?>.textOrNull(column: String): String? =
        this[column]?.toString()?.takeIf { it.isNotBlank() }

    /**
     * A column can arrive as the driver's own type or, on a workbook that predates
     * the typed columns, as text. Anything unreadable is zero rather than a crash
     * that would strand the whole Pull.
     */
    private fun Map<String, Any?>.number(column: String): Double = when (val value = this[column]) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun Map<String, Any?>.flag(column: String): Boolean = when (val value = this[column]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> false
    }
}
