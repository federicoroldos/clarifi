package com.clarifi.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.clarifi.core.model.AccountColors
import com.clarifi.core.model.Categories
import com.clarifi.core.model.TransferDirection
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Currencies
import com.clarifi.core.money.Currency

/**
 * The local database is a column-for-column mirror of the desktop's five
 * worksheets (`SHEETS` in app.py), which are themselves mirrored by the
 * `clarifi_*` tables in Postgres.
 *
 * Keeping all three identical is what makes cloud push/pull and JSON
 * export/import straight copies instead of three separate mapping layers that
 * have to be kept in step. These classes are used directly as the app's models
 * - there is no second set of "domain" twins to translate to.
 */

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "bank") val bank: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "balance") val balance: Double,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "archived") val archived: Boolean,
    @ColumnInfo(name = "color") val color: String,
) {
    /** Never null in practice: unknown currencies fall back the way `_account_json` does. */
    val currencyMeta: Currency get() = Currencies.find(currency) ?: Currencies.UYU

    val displayColor: String get() = color.ifBlank { AccountColors.defaultFor(currency) }
}

@Entity(
    tableName = "transactions",
    indices = [
        Index("account"),
        Index("date"),
        Index("transfer_id"),
    ],
)
data class Txn(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Int,
    /** `YYYY-MM-DD`. */
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "description") val description: String,
    /** Always positive; the sign is implied by [type]. */
    @ColumnInfo(name = "amount") val amount: Double,
    @ColumnInfo(name = "category") val category: String,
    /** `fund` | `expense` | `transfer`. */
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "account") val account: String,
    /** Set on both legs of a transfer, null otherwise. */
    @ColumnInfo(name = "transfer_id") val transferId: String? = null,
    /** The other leg's account id. */
    @ColumnInfo(name = "counterpart") val counterpart: String? = null,
    /** `out` on the source leg, `in` on the destination leg. */
    @ColumnInfo(name = "transfer_dir") val transferDir: String? = null,
) {
    val txnType: TxnType get() = TxnType.from(type)
    val direction: TransferDirection? get() = TransferDirection.from(transferDir)
    val isTransfer: Boolean get() = txnType == TxnType.TRANSFER

    /** `2026-07-31` → `2026-07`. Used for every monthly rollup. */
    val monthKey: String get() = date.take(7)

    /**
     * How this row moved its account's balance. Adding it applies the
     * transaction; subtracting it reverses one, which is all delete and edit
     * need to stay correct.
     */
    val balanceDelta: Double
        get() = when {
            isTransfer -> if (direction == TransferDirection.OUT) -amount else amount
            txnType == TxnType.FUND -> amount
            else -> -amount
        }
}

@Entity(tableName = "fixed_payments", indices = [Index("account")])
data class FixedPayment(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "amount") val amount: Double,
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "category") val category: String,
    /** Day of the month it falls due, 1-31. */
    @ColumnInfo(name = "day") val day: Int,
    /** `fund` (a paycheck) or `expense` (rent, subscriptions). Never `transfer`. */
    @ColumnInfo(name = "type") val type: String,
) {
    val fixedType: TxnType get() = TxnType.fixedFrom(type)
}

/** One row per (payment, month) that has already been applied. */
@Entity(tableName = "fixed_applied", primaryKeys = ["payment_id", "year_month"])
data class FixedApplied(
    @ColumnInfo(name = "payment_id") val paymentId: Int,
    /** `YYYY-MM`. */
    @ColumnInfo(name = "year_month") val yearMonth: String,
)

/**
 * The desktop's key/value config sheet. Carried over verbatim - including the
 * legacy `balance_*` mirrors and the `fxrate_*` entries - so a push does not
 * drop rows another device still reads.
 */
@Entity(tableName = "config")
data class ConfigEntry(
    @PrimaryKey
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

/** Builds the pair of rows a transfer is made of. Both share [transferId]. */
fun transferLegs(
    outId: Int,
    inId: Int,
    transferId: String,
    date: String,
    source: Account,
    destination: Account,
    amountSent: Double,
    amountReceived: Double,
    note: String,
): Pair<Txn, Txn> {
    val outLeg = Txn(
        id = outId,
        date = date,
        description = note.ifBlank { "Transfer to ${destination.bank}" },
        amount = amountSent,
        category = Categories.TRANSFER,
        type = TxnType.TRANSFER.wire,
        account = source.id,
        transferId = transferId,
        counterpart = destination.id,
        transferDir = TransferDirection.OUT.wire,
    )
    val inLeg = Txn(
        id = inId,
        date = date,
        description = note.ifBlank { "Transfer from ${source.bank}" },
        amount = amountReceived,
        category = Categories.TRANSFER,
        type = TxnType.TRANSFER.wire,
        account = destination.id,
        transferId = transferId,
        counterpart = source.id,
        transferDir = TransferDirection.IN.wire,
    )
    return outLeg to inLeg
}
