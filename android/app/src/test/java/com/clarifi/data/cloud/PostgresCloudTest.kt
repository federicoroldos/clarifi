package com.clarifi.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PostgresCloudTest {

    private val cloud = PostgresCloud("postgresql://user:secret@host:5432/postgres")

    /**
     * jasync rewrites the placeholders itself, counting `?`s. Written in Postgres's
     * own `$1` syntax the statement looks like it takes no parameters, and every
     * Push died on `The query contains 0 parameters but you gave it 6`.
     */
    @Test
    fun `an insert uses one question mark per value and never numbers them`() {
        val table = TableWrite("clarifi_config", listOf("key", "value"), rows = emptyList())

        val sql = cloud.insertStatement(table, rows = 3)

        assertEquals(
            """INSERT INTO "clarifi_config" ("key", "value") VALUES (?, ?), (?, ?), (?, ?)""",
            sql,
        )
        assertEquals(6, sql.count { it == '?' })
        assertFalse(sql.contains('$'))
    }

    @Test
    fun `every column of every row gets a placeholder`() {
        val table = TableWrite("clarifi_transactions", CloudSchema.TRANSACTIONS.columns, rows = emptyList())

        val sql = cloud.insertStatement(table, rows = 200)

        assertEquals(CloudSchema.TRANSACTIONS.columns.size * 200, sql.count { it == '?' })
    }
}
