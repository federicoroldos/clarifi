package com.clarifi.data.cloud

import com.github.jasync.sql.db.Connection
import com.github.jasync.sql.db.QueryResult
import com.github.jasync.sql.db.RowData
import com.github.jasync.sql.db.postgresql.PostgreSQLConnectionBuilder
import java.util.concurrent.TimeUnit

/** Anything the sync could not do, in words worth showing the user. */
class CloudException(message: String) : Exception(message)

/**
 * The same Postgres database the desktop talks to, over the same connection
 * string, using the same `clarifi_*` tables.
 *
 * The driver is jasync rather than pgjdbc. pgjdbc cannot open a connection under
 * ART at all: `PGStream.setMaxResultBuffer` calls `PGPropertyMaxResultBufferParser.
 * parseProperty` on every connection, which calls `adjustResultSize`, which touches
 * `java.lang.management.ManagementFactory` - a class Android does not have, and no
 * connection property avoids that path. jasync implements the wire protocol itself,
 * including the SCRAM-SHA-256 that Supabase requires.
 */
class PostgresCloud(private val dsn: String) {

    /**
     * Runs [block] on a fresh connection and always closes it.
     *
     * A phone loses its network constantly; a pool held across the app's lifetime
     * would spend most of it holding dead sockets. Sync is a rare, manual action,
     * so a connection per sync is the honest shape.
     */
    private fun <T> connected(block: (Connection) -> T): T {
        // Throwable, not Exception: Android lacks `javax.security.sasl`, so a bad
        // password can surface as a NoClassDefFoundError from inside SCRAM's error
        // path rather than as a normal failure. Letting that escape would crash the
        // app on a typo.
        val connection = try {
            PostgreSQLConnectionBuilder.createConnectionPool(connectionUrl(dsn))
        } catch (e: Throwable) {
            throw CloudException(explain(e))
        }
        try {
            return block(connection)
        } catch (e: CloudException) {
            throw e
        } catch (e: Throwable) {
            throw CloudException(explain(e))
        } finally {
            runCatching { connection.disconnect().get(10, TimeUnit.SECONDS) }
        }
    }

    /** Cheapest statement that proves host, credentials and TLS all work. */
    fun ping() = connected { it.query("SELECT 1") }

    fun selectAll(table: String): List<RowData> =
        connected { connection -> connection.query("SELECT * FROM \"$table\"").rows }

    /**
     * Replaces the whole cloud database in one transaction, exactly like the
     * desktop's `_pg_from_wb`: either every table is the phone's, or none is.
     */
    fun replaceAll(tables: List<TableWrite>) = connected { connection ->
        connection.inTransaction { tx ->
            ensureSchema(tx)
            tables.forEach { table ->
                tx.sendQuery("TRUNCATE TABLE \"${table.name}\"").get(TIMEOUT, TimeUnit.SECONDS)
                table.rows.chunked(BATCH).forEach { chunk ->
                    val statement = insertStatement(table, chunk.size)
                    val values = chunk.flatMap { row -> table.columns.map { row[it] } }
                    tx.sendPreparedStatement(statement, values).get(TIMEOUT, TimeUnit.SECONDS)
                }
            }
            java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }.get(TRANSACTION_TIMEOUT, TimeUnit.SECONDS)
    }

    /** Creates whatever is missing, so the phone can set the cloud up on its own. */
    fun ensureSchema() = connected { connection ->
        connection.inTransaction { tx ->
            ensureSchema(tx)
            java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }.get(TIMEOUT, TimeUnit.SECONDS)
    }

    private fun ensureSchema(tx: Connection) {
        CloudSchema.TABLES.forEach { table ->
            val columns = table.columns.joinToString(", ") { "\"$it\" ${table.type(it)}" }
            tx.sendQuery("CREATE TABLE IF NOT EXISTS \"${table.name}\" ($columns)")
                .get(TIMEOUT, TimeUnit.SECONDS)
            // Older projects predate the transfer columns; the desktop widens the
            // table the same way rather than asking the user to migrate by hand.
            table.columns.forEach { column ->
                tx.sendQuery(
                    "ALTER TABLE \"${table.name}\" ADD COLUMN IF NOT EXISTS " +
                        "\"$column\" ${table.type(column)}"
                ).get(TIMEOUT, TimeUnit.SECONDS)
            }
            // Supabase publishes the public schema over a REST API, and Row Level
            // Security is the only thing gating it: without this, anyone holding
            // the project's anon key can read and rewrite someone's whole ledger
            // over HTTPS. No policies are added on purpose - no policy means the
            // API can see nothing, while ClariFi keeps full access because it
            // connects as the table owner, and owners bypass RLS. Mirrors
            // _pg_ensure_schema in app.py; change both together.
            tx.sendQuery("ALTER TABLE \"${table.name}\" ENABLE ROW LEVEL SECURITY")
                .get(TIMEOUT, TimeUnit.SECONDS)
        }
    }

    /**
     * Placeholders are `?`, not Postgres's own `$1`.
     *
     * jasync counts the `?`s itself and numbers them on the way out, so a statement
     * written in the wire syntax looks like it takes no parameters at all: a Push
     * died on `InsufficientParametersException: The query contains 0 parameters but
     * you gave it 6`, naming the first config rows it tried to write.
     */
    internal fun insertStatement(table: TableWrite, rows: Int): String {
        val columns = table.columns.joinToString(", ") { "\"$it\"" }
        val tuples = (1..rows).joinToString(", ") {
            table.columns.joinToString(", ", prefix = "(", postfix = ")") { "?" }
        }
        return "INSERT INTO \"${table.name}\" ($columns) VALUES $tuples"
    }

    private fun Connection.query(sql: String): QueryResult =
        sendQuery(sql).get(TIMEOUT, TimeUnit.SECONDS)

    /** Driver exceptions name internals; these are the failures a user can act on. */
    private fun explain(e: Throwable): String {
        val message = generateSequence(e) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()

        return when {
            "password authentication failed" in message || "authentication" in message ->
                "The database rejected those credentials. Check the password in the connection string."

            "unknownhost" in message || "unable to resolve" in message || "nodename" in message ->
                "That host does not resolve. Check the connection string."

            "timeout" in message || "timed out" in message ->
                "The database did not answer in time. Check the connection and try again."

            "does not exist" in message && "database" in message ->
                "That database does not exist on the server."

            "sasl" in message || "noclassdeffound" in message ->
                "The database rejected those credentials. Check the password in the connection string."

            else -> e.message ?: "The sync did not finish."
        }
    }

    companion object {
        private const val TIMEOUT = 30L
        private const val TRANSACTION_TIMEOUT = 120L
        private const val BATCH = 200

        /**
         * The desktop's `_parse_dsn` rules, as a URL jasync accepts: TLS is required
         * and the certificate is not verified, which is what pg8000 does on the
         * desktop with `sslmode=require`.
         */
        fun connectionUrl(dsn: String): String {
            val trimmed = dsn.trim()
            if (!trimmed.startsWith("postgres://") && !trimmed.startsWith("postgresql://")) {
                throw CloudException("The connection string must start with postgresql://")
            }
            return if ("sslmode=" in trimmed) {
                trimmed
            } else if ("?" in trimmed) {
                "$trimmed&sslmode=require"
            } else {
                "$trimmed?sslmode=require"
            }
        }

        /** Host and database, with the password removed, for the status line. */
        fun describe(dsn: String): String = runCatching {
            val authority = dsn.substringAfter("://").substringBefore('/')
            val host = authority.substringAfter('@', authority).substringBefore('?')
            val database = dsn.substringAfter("://").substringAfter('/', "").substringBefore('?')
            if (database.isBlank()) host else "$host/$database"
        }.getOrDefault("")
    }
}

/** One table's worth of rows, ready to be written. */
data class TableWrite(
    val name: String,
    val columns: List<String>,
    val rows: List<Map<String, Any?>>,
)
