package dev.spola.sqlite

import kotlinx.coroutines.delay
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager
import java.sql.SQLException

object SqliteStoreSupport {
    fun connectSqliteDatabase(dbPath: String, busyTimeoutMs: Int = 5_000): Database {
        val path = Paths.get(dbPath).toAbsolutePath()
        path.parent?.let(Files::createDirectories)

        return Database.connect(
            getNewConnection = {
                DriverManager.getConnection("jdbc:sqlite:$path").apply {
                    autoCommit = true
                    createStatement().use { stmt ->
                        stmt.execute("PRAGMA journal_mode=WAL")
                        stmt.execute("PRAGMA busy_timeout=$busyTimeoutMs")
                    }
                }
            },
        )
    }

    suspend fun <T> retryingTransaction(
        database: Database,
        maxAttempts: Int = 5,
        initialBackoffMs: Long = 25,
        block: Transaction.() -> T,
    ): T {
        var attempt = 0
        var backoff = initialBackoffMs
        var lastError: Exception? = null

        while (attempt < maxAttempts) {
            try {
                return transaction(database) { block() }
            } catch (e: ExposedSQLException) {
                if (!isBusyOrLocked(e)) throw e
                lastError = e
            } catch (e: SQLException) {
                if (!isBusyOrLocked(e)) throw e
                lastError = e
            }

            attempt++
            if (attempt >= maxAttempts) break

            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(1_000)
        }

        throw lastError ?: IllegalStateException("Transaction failed after $maxAttempts attempts")
    }

    private fun isBusyOrLocked(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
            .lowercase()
        return "database is locked" in message || "database is busy" in message
    }
}
