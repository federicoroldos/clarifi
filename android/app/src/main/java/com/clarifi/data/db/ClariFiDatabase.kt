package com.clarifi.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Account::class,
        Txn::class,
        FixedPayment::class,
        FixedApplied::class,
        ConfigEntry::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ClariFiDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun txnDao(): TxnDao
    abstract fun fixedDao(): FixedDao
    abstract fun configDao(): ConfigDao

    companion object {
        const val NAME = "clarifi.db"

        fun build(context: Context): ClariFiDatabase =
            Room.databaseBuilder(context.applicationContext, ClariFiDatabase::class.java, NAME)
                .build()
    }
}
