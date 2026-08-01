package com.clarifi.core.time

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Dates are stored as plain `YYYY-MM-DD` strings and months as `YYYY-MM`, exactly
 * as the desktop writes them. Sorting and "this month" checks are therefore
 * string operations - no parsing needed on the hot paths.
 */
object Dates {

    val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val MONTH_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
    private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val DISPLAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    private val SHORT_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)

    fun today(): String = LocalDate.now().format(ISO)

    fun currentMonth(): String = LocalDate.now().format(MONTH_KEY)

    fun todayDayOfMonth(): Int = LocalDate.now().dayOfMonth

    /** The `YYYY-MM-DD` cutoff used for the "last 30 days" figures. */
    fun daysAgo(days: Long): String = LocalDate.now().minusDays(days).format(ISO)

    fun parseOrNull(value: String?): LocalDate? =
        try {
            value?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it, ISO) }
        } catch (_: DateTimeParseException) {
            null
        }

    /** `2026-07-31` → `Jul 31, 2026`; falls back to the raw value if unparseable. */
    fun display(value: String?): String = parseOrNull(value)?.format(DISPLAY) ?: value.orEmpty()

    /** `2026-07` → `Jul 2026`, for section headers. */
    fun displayMonth(monthKey: String): String =
        parseOrNull("$monthKey-01")?.format(DISPLAY_MONTH) ?: monthKey

    /** `2026-07` → `Jul`, for chart axis labels. */
    fun shortMonth(monthKey: String): String =
        parseOrNull("$monthKey-01")?.format(SHORT_MONTH) ?: monthKey

    /** `1` → `1st`, used when describing which day a fixed payment falls on. */
    fun ordinal(day: Int): String {
        val suffix = if (day % 100 in 11..13) "th" else when (day % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }
}
