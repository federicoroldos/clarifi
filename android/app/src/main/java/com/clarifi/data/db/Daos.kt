package com.clarifi.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    /** Active accounts only, in creation order - the order the UI lists them in. */
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY created_at, bank")
    fun observeActive(): Flow<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY archived, created_at, bank")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY created_at, bank")
    suspend fun activeAccounts(): List<Account>

    @Query("SELECT * FROM accounts ORDER BY archived, created_at, bank")
    suspend fun allAccounts(): List<Account>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): Account?

    @Query("SELECT id FROM accounts")
    suspend fun allIds(): List<String>

    @Query("UPDATE accounts SET balance = :balance WHERE id = :id")
    suspend fun updateBalance(id: String, balance: Double)

    @Query("UPDATE accounts SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: Account)

    @Update
    suspend fun update(account: Account)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<Account>)

    @Query("DELETE FROM accounts")
    suspend fun clear()
}

@Dao
interface TxnDao {

    /** Newest first, matching the desktop's sort: date descending, then id descending. */
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC")
    fun observeAll(): Flow<List<Txn>>

    @Query("SELECT * FROM transactions WHERE account = :accountId ORDER BY date DESC, id DESC")
    fun observeForAccount(accountId: String): Flow<List<Txn>>

    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC")
    suspend fun allTxns(): List<Txn>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Int): Txn?

    @Query("SELECT * FROM transactions WHERE transfer_id = :transferId")
    suspend fun byTransferId(transferId: String): List<Txn>

    @Query("SELECT MAX(id) FROM transactions")
    suspend fun maxId(): Int?

    /**
     * Backs "undo a fixed payment": the desktop matches on name, account, type and
     * month rather than by id, and takes the most recent match.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE description = :description AND account = :accountId AND type = :type
          AND date LIKE :monthPrefix || '%'
        ORDER BY id DESC LIMIT 1
        """
    )
    suspend fun lastMatching(
        description: String,
        accountId: String,
        type: String,
        monthPrefix: String,
    ): Txn?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(txn: Txn)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(txns: List<Txn>)

    @Update
    suspend fun update(txn: Txn)

    @Delete
    suspend fun delete(txn: Txn)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("DELETE FROM transactions WHERE account = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Dao
interface FixedDao {

    @Query("SELECT * FROM fixed_payments ORDER BY day, name")
    fun observeAll(): Flow<List<FixedPayment>>

    @Query("SELECT * FROM fixed_applied")
    fun observeApplied(): Flow<List<FixedApplied>>

    @Query("SELECT * FROM fixed_payments ORDER BY day, name")
    suspend fun allPayments(): List<FixedPayment>

    @Query("SELECT * FROM fixed_applied")
    suspend fun allApplied(): List<FixedApplied>

    @Query("SELECT * FROM fixed_payments WHERE id = :id")
    suspend fun byId(id: Int): FixedPayment?

    @Query("SELECT id FROM fixed_payments WHERE account = :accountId")
    suspend fun idsForAccount(accountId: String): List<Int>

    @Query("SELECT MAX(id) FROM fixed_payments")
    suspend fun maxId(): Int?

    @Query("SELECT COUNT(*) FROM fixed_applied WHERE payment_id = :id AND year_month = :month")
    suspend fun appliedCount(id: Int, month: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(payment: FixedPayment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPayments(payments: List<FixedPayment>)

    @Update
    suspend fun update(payment: FixedPayment)

    @Query("DELETE FROM fixed_payments WHERE id = :id")
    suspend fun deletePayment(id: Int)

    @Query("DELETE FROM fixed_payments WHERE id IN (:ids)")
    suspend fun deletePayments(ids: List<Int>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markApplied(applied: FixedApplied)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllApplied(applied: List<FixedApplied>)

    @Query("DELETE FROM fixed_applied WHERE payment_id = :id AND year_month = :month")
    suspend fun clearApplied(id: Int, month: String)

    @Query("DELETE FROM fixed_applied WHERE payment_id = :id")
    suspend fun clearAllApplied(id: Int)

    @Query("DELETE FROM fixed_applied WHERE payment_id IN (:ids)")
    suspend fun clearAllAppliedFor(ids: List<Int>)

    @Query("DELETE FROM fixed_payments")
    suspend fun clearPayments()

    @Query("DELETE FROM fixed_applied")
    suspend fun clearAppliedTable()
}

@Dao
interface ConfigDao {

    @Query("SELECT * FROM config")
    fun observeAll(): Flow<List<ConfigEntry>>

    @Query("SELECT * FROM config")
    suspend fun all(): List<ConfigEntry>

    @Query("SELECT value FROM config WHERE `key` = :key")
    suspend fun value(key: String): String?

    @Query("SELECT * FROM config WHERE `key` LIKE :prefix || '%'")
    suspend fun withPrefix(prefix: String): List<ConfigEntry>

    @Upsert
    suspend fun put(entry: ConfigEntry)

    @Upsert
    suspend fun putAll(entries: List<ConfigEntry>)

    @Query("DELETE FROM config WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM config")
    suspend fun clear()
}
