package dev.spola.factory

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.spola.PostgresConfig
import javax.sql.DataSource

/**
 * Factory for creating a PostgreSQL [DataSource] backed by HikariCP connection pooling.
 *
 * Used by [WorkflowFactory] when [PostgresConfig.enabled] is true.
 */
object PostgresCheckpointDataSource {

    fun create(config: PostgresConfig): DataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.poolSize
            driverClassName = "org.postgresql.Driver"

            // Sensible defaults
            connectionTimeout = 30_000L
            idleTimeout = 600_000L
            maxLifetime = 1_800_000L
            minimumIdle = 2
            isAutoCommit = true

            // Pool naming for observability
            poolName = "spola-pg-checkpoint-pool"

            // PostgreSQL-specific optimizations
            addDataSourceProperty("ApplicationName", "Spola")
            addDataSourceProperty("socketTimeout", "30")
            addDataSourceProperty("connectTimeout", "10")
        }
        return HikariDataSource(hikariConfig)
    }
}
