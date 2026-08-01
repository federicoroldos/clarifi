package com.clarifi.data.repo

import com.clarifi.core.model.AccountColors
import com.clarifi.data.db.Account
import com.clarifi.data.db.ClariFiDatabase
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * First-run seeding, mirroring the fresh-install branch of `init_data`: a brand
 * new ClariFi starts with a US Dollar and a Euro account, both empty.
 *
 * These two use the currency as their id (`usd`, `eur`) rather than an `acct_*`
 * id - the same legacy-style ids the desktop seeds - so a device that later
 * pulls from a desktop's cloud backup lines up instead of ending with duplicates.
 */
suspend fun ClariFiDatabase.seedIfEmpty() {
    val dao = accountDao()
    if (dao.allIds().isNotEmpty()) return

    val createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    dao.insertAll(
        listOf(
            defaultAccount("usd", "US Dollar Account", createdAt),
            defaultAccount("eur", "Euro Account", createdAt),
        )
    )
}

private fun defaultAccount(currency: String, bank: String, createdAt: String) = Account(
    id = currency,
    bank = bank,
    currency = currency,
    balance = 0.0,
    createdAt = createdAt,
    archived = false,
    color = AccountColors.defaultFor(currency),
)
