package com.clarifi.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A supported currency. Ids are always lowercase, exactly as they are stored in
 * the workbook and in Postgres - `CURRENCIES` in app.py is the source of truth.
 */
data class Currency(
    val id: String,
    val code: String,
    val name: String,
    val symbol: String,
    val decimals: Int,
)

object Currencies {

    val KRW = Currency("krw", "KRW", "Korean Won", "₩", 0)
    val UYU = Currency("uyu", "UYU", "Uruguayan Peso", "\$U", 2)
    val USD = Currency("usd", "USD", "US Dollar", "US\$", 2)
    val EUR = Currency("eur", "EUR", "Euro", "€", 2)
    val ARS = Currency("ars", "ARS", "Argentine Peso", "AR\$", 2)

    /** Declaration order matches the desktop's CURRENCIES dict, which drives pickers. */
    val ALL: List<Currency> = listOf(KRW, UYU, USD, EUR, ARS)

    private val byId: Map<String, Currency> = ALL.associateBy { it.id }

    /** Lenient lookup used when reading stored data, which may predate a rename. */
    fun find(id: String?): Currency? = byId[id?.trim()?.lowercase()]

    /**
     * Strict lookup for user input, mirroring `_currency_id`.
     *
     * @throws IllegalArgumentException on an unknown or uppercase-only value.
     */
    fun require(id: String?): Currency =
        find(id) ?: throw IllegalArgumentException("unknown currency")
}

/**
 * Rounds to the currency's precision.
 *
 * Uses HALF_EVEN on the exact binary value of the double, which is what Python's
 * `round()` does in `round_currency`. HALF_UP would drift a cent away from the
 * desktop on ties, and the two apps share a database.
 */
fun roundCurrency(currency: Currency, value: Double): Double {
    if (value.isNaN() || value.isInfinite()) throw IllegalArgumentException("invalid amount")
    return BigDecimal(value).setScale(currency.decimals, RoundingMode.HALF_EVEN).toDouble()
}

fun roundCurrency(currencyId: String?, value: Double): Double =
    roundCurrency(Currencies.require(currencyId), value)

/**
 * Amount formatting, matching the `fmt`, `fmtShort` and `fmtChartY` helpers in
 * templates/index.html so numbers read identically on both platforms.
 */
object Money {

    private val symbols = DecimalFormatSymbols(Locale.US)

    /** e.g. `US$1,240.50`, `₩120,000`. */
    fun format(currency: Currency, value: Double): String {
        val pattern = if (currency.decimals > 0) "#,##0." + "0".repeat(currency.decimals) else "#,##0"
        return currency.symbol + DecimalFormat(pattern, symbols).format(value)
    }

    /** Signed variant used in lists: `+US$40.00` / `-US$40.00`. */
    fun formatSigned(currency: Currency, value: Double, positive: Boolean): String =
        (if (positive) "+" else "-") + format(currency, kotlin.math.abs(value))

    /** Compact form for tight spots: `1.2M`, `4.5k`, `230`. */
    fun formatShort(value: Double): String = when {
        kotlin.math.abs(value) >= 1_000_000 -> DecimalFormat("0.0", symbols).format(value / 1_000_000) + "M"
        kotlin.math.abs(value) >= 1_000 -> DecimalFormat("0.0", symbols).format(value / 1_000) + "k"
        else -> DecimalFormat("0", symbols).format(value)
    }

    /** Chart axis labels: keeps the symbol, drops the decimals. */
    fun formatAxis(currency: Currency, value: Double): String = when {
        kotlin.math.abs(value) >= 1_000_000 ->
            currency.symbol + DecimalFormat("0.0", symbols).format(value / 1_000_000) + "M"
        kotlin.math.abs(value) >= 10_000 ->
            currency.symbol + DecimalFormat("0", symbols).format(value / 1_000) + "k"
        else -> currency.symbol + DecimalFormat("#,##0", symbols).format(value)
    }
}
