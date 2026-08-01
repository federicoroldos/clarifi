package com.clarifi.core.ids

import java.security.SecureRandom

/**
 * Id generation, matching the desktop byte for byte so ids stay unique after a
 * cloud push/pull merges rows written on different devices.
 *
 * Accounts get random hex ids rather than a counter precisely because two
 * offline devices must be able to create accounts without colliding.
 * Transactions and fixed payments keep the desktop's `max + 1` integer scheme.
 */
object Ids {

    private val random = SecureRandom()

    /** `acct_3f4a8b2c` - mirrors `_new_account_id`. */
    fun newAccountId(existing: Set<String>): String {
        while (true) {
            val candidate = "acct_" + hex(4)
            if (candidate !in existing) return candidate
        }
    }

    /** `tx_9c1d2e3f` - mirrors the transfer id built in `modern_transfer`. */
    fun newTransferId(): String = "tx_" + hex(4)

    /** Mirrors `_next_id`: one past the highest id currently in the table. */
    fun nextId(currentMax: Int?): Int = (currentMax ?: 0) + 1

    private fun hex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }
}
