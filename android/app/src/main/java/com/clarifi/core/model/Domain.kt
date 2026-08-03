package com.clarifi.core.model

/**
 * Every user-facing rule violation. The messages are the same ones the desktop
 * returns in its `{'ok': false, 'error': …}` payloads, so behaviour stays
 * recognisable across platforms.
 */
class ClariFiException(message: String) : Exception(message)

/**
 * The only three transaction types that exist. They are persisted as these exact
 * lowercase strings and compared as literals by the desktop app and by Postgres.
 */
enum class TxnType(val wire: String) {
    FUND("fund"),
    EXPENSE("expense"),
    TRANSFER("transfer");

    companion object {
        fun from(value: String?): TxnType =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: EXPENSE

        /** Fixed payments may only be income or expense, never a transfer. */
        fun fixedFrom(value: String?): TxnType =
            when (value?.trim()?.lowercase()) {
                FUND.wire -> FUND
                else -> EXPENSE
            }
    }
}

/** Which leg of a transfer pair a row represents. */
enum class TransferDirection(val wire: String) {
    OUT("out"),
    IN("in");

    companion object {
        fun from(value: String?): TransferDirection? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** Expense categories, in the desktop's order (`CATEGORIES` in app.py). */
object Categories {
    const val OTHERS = "Others"

    /** The placeholder category transfer rows carry; not a real spending category. */
    const val TRANSFER = "Transfer"

    val ALL: List<String> = listOf(
        "Supermarket", "Food", "Transport", "Rent", "Utilities", "Services",
        "Subscriptions", "Health", "Fitness", "Shopping", "Games", "Hanging out",
        "Travel", "Education", "Pets", "Gifts", "Taxes", OTHERS,
    )

    /** `CAT_ICONS` from templates/index.html, so a category reads the same on both. */
    private val EMOJI: Map<String, String> = mapOf(
        "Supermarket" to "🛒",
        "Food" to "🍔",
        "Transport" to "🚌",
        "Rent" to "🏠",
        "Utilities" to "💡",
        "Services" to "📱",
        "Subscriptions" to "🔁",
        "Health" to "💊",
        "Fitness" to "🏋️",
        "Shopping" to "🛍️",
        "Games" to "🎮",
        "Hanging out" to "🍻",
        "Travel" to "✈️",
        "Education" to "📚",
        "Pets" to "🐾",
        "Gifts" to "🎁",
        "Taxes" to "🧾",
        OTHERS to "📦",
    )

    /** The bullet the web falls back to in a category list it does not recognise. */
    fun emoji(category: String?): String = EMOJI[category] ?: "•"

    /** The web's fallback on a transaction row, where a bullet would look broken. */
    fun rowEmoji(category: String?): String = EMOJI[category] ?: "💳"

    fun normalize(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: OTHERS
}

/** Account colours, shared with the desktop's picker and default assignment. */
object AccountColors {
    val PRESETS = listOf(
        "#4a90f8", "#32d74b", "#bf5af2", "#5ac8fa",
        "#ff9f0a", "#ff453a", "#ff6b6b", "#ffd60a",
    )

    private val byCurrency = mapOf(
        "uyu" to "#4a90f8",
        "usd" to "#32d74b",
        "krw" to "#bf5af2",
        "eur" to "#5ac8fa",
        "ars" to "#ff9f0a",
    )

    fun defaultFor(currencyId: String): String = byCurrency[currencyId] ?: "#4a90f8"
}
