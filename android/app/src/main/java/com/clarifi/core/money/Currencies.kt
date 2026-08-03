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
    /** ISO 3166 country the UI draws a flag for. `region` in the desktop's dict. */
    val region: String,
) {
    /**
     * The country flag, built from the region's two regional indicator symbols.
     * Android has the glyphs, so there is no artwork to keep in step; the desktop
     * draws the same flags as SVG because Windows has no flag glyphs at all.
     */
    val flag: String
        get() = region.map { Character.toChars(0x1F1A5 + it.code).concatToString() }.joinToString("")
}

object Currencies {

    val USD = Currency("usd", "USD", "US Dollar", "US\$", 2, "US")
    val EUR = Currency("eur", "EUR", "Euro", "€", 2, "EU")
    val UYU = Currency("uyu", "UYU", "Uruguayan Peso", "\$U", 2, "UY")
    val ARS = Currency("ars", "ARS", "Argentine Peso", "AR\$", 2, "AR")
    val KRW = Currency("krw", "KRW", "Korean Won", "₩", 0, "KR")

    /**
     * Declaration order matches the desktop's CURRENCIES dict, which drives both
     * pickers: the five the app shipped with first, then the widely used ones.
     */
    val ALL: List<Currency> = listOf(
        USD, EUR, UYU, ARS, KRW,
        Currency("gbp", "GBP", "British Pound", "£", 2, "GB"),
        Currency("jpy", "JPY", "Japanese Yen", "¥", 0, "JP"),
        Currency("cny", "CNY", "Chinese Yuan", "CN¥", 2, "CN"),
        Currency("chf", "CHF", "Swiss Franc", "CHF ", 2, "CH"),
        Currency("cad", "CAD", "Canadian Dollar", "CA\$", 2, "CA"),
        Currency("aud", "AUD", "Australian Dollar", "A\$", 2, "AU"),
        Currency("nzd", "NZD", "New Zealand Dollar", "NZ\$", 2, "NZ"),
        Currency("brl", "BRL", "Brazilian Real", "R\$", 2, "BR"),
        Currency("mxn", "MXN", "Mexican Peso", "MX\$", 2, "MX"),
        Currency("clp", "CLP", "Chilean Peso", "CL\$", 0, "CL"),
        Currency("cop", "COP", "Colombian Peso", "CO\$", 2, "CO"),
        Currency("pen", "PEN", "Peruvian Sol", "S/", 2, "PE"),
        Currency("inr", "INR", "Indian Rupee", "₹", 2, "IN"),
        Currency("sgd", "SGD", "Singapore Dollar", "S\$", 2, "SG"),
        Currency("hkd", "HKD", "Hong Kong Dollar", "HK\$", 2, "HK"),
        Currency("sek", "SEK", "Swedish Krona", "kr", 2, "SE"),
        Currency("nok", "NOK", "Norwegian Krone", "kr", 2, "NO"),
        Currency("dkk", "DKK", "Danish Krone", "kr", 2, "DK"),
        Currency("pln", "PLN", "Polish Zloty", "zł", 2, "PL"),
        Currency("czk", "CZK", "Czech Koruna", "Kč", 2, "CZ"),
        Currency("huf", "HUF", "Hungarian Forint", "Ft", 2, "HU"),
        Currency("try", "TRY", "Turkish Lira", "₺", 2, "TR"),
        Currency("rub", "RUB", "Russian Ruble", "₽", 2, "RU"),
        Currency("uah", "UAH", "Ukrainian Hryvnia", "₴", 2, "UA"),
        Currency("zar", "ZAR", "South African Rand", "R", 2, "ZA"),
        Currency("ils", "ILS", "Israeli Shekel", "₪", 2, "IL"),
        Currency("aed", "AED", "UAE Dirham", "AED ", 2, "AE"),
        Currency("sar", "SAR", "Saudi Riyal", "SAR ", 2, "SA"),
        Currency("thb", "THB", "Thai Baht", "฿", 2, "TH"),
        Currency("php", "PHP", "Philippine Peso", "₱", 2, "PH"),
        Currency("idr", "IDR", "Indonesian Rupiah", "Rp", 2, "ID"),
        Currency("vnd", "VND", "Vietnamese Dong", "₫", 0, "VN"),
    )

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
