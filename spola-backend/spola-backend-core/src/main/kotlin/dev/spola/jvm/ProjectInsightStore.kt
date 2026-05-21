package dev.spola.jvm

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Paths

data class ProjectInsight(
    val module: String?,
    val symbol: String?,
    val key: String,
    val value: String,
)

class ProjectInsightStore(dbPath: String) {
    private val database = Database.connect(
        url = "jdbc:sqlite:$dbPath",
        driver = "org.sqlite.JDBC",
    )

    init {
        val path = Paths.get(dbPath).toAbsolutePath()
        path.parent?.let { Files.createDirectories(it) }
        transaction(database) {
            SchemaUtils.create(ProjectInsights)
        }
    }

    object ProjectInsights : Table("project_insights") {
        val id = integer("id").autoIncrement()
        val module = varchar("module", 256).nullable()
        val symbol = varchar("symbol", 512).nullable()
        val key = varchar("key", 256)
        val value = text("value")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)
    }

    fun save(module: String?, symbol: String?, key: String, value: String) {
        transaction(database) {
            val now = System.currentTimeMillis()
            val existing = ProjectInsights.selectAll().where {
                (ProjectInsights.module eq module) and
                    (ProjectInsights.symbol eq symbol) and
                    (ProjectInsights.key eq key)
            }.singleOrNull()
            if (existing == null) {
                ProjectInsights.insert {
                    it[ProjectInsights.module] = module
                    it[ProjectInsights.symbol] = symbol
                    it[ProjectInsights.key] = key
                    it[ProjectInsights.value] = value
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            } else {
                ProjectInsights.update({ ProjectInsights.id eq existing[ProjectInsights.id] }) {
                    it[ProjectInsights.value] = value
                    it[updatedAt] = now
                }
            }
        }
    }

    fun search(module: String?, symbol: String?, key: String?): List<ProjectInsight> {
        return transaction(database) {
            ProjectInsights.selectAll()
                .where { buildSearchPredicate(module, symbol, key) }
                .orderBy(ProjectInsights.updatedAt, SortOrder.DESC)
                .map(::rowToInsight)
        }
    }

    fun delete(module: String?, symbol: String?, key: String): Int {
        return transaction(database) {
            ProjectInsights.deleteWhere {
                (ProjectInsights.module eq module) and
                    (ProjectInsights.symbol eq symbol) and
                    (ProjectInsights.key eq key)
            }
        }
    }

    private fun buildSearchPredicate(module: String?, symbol: String?, key: String?) =
        Op.build {
            var predicate: Op<Boolean> = ProjectInsights.id greaterEq 0
            if (module != null) predicate = predicate and (ProjectInsights.module eq module)
            if (symbol != null) predicate = predicate and (ProjectInsights.symbol eq symbol)
            if (key != null) predicate = predicate and (ProjectInsights.key eq key)
            predicate
        }

    private fun rowToInsight(row: ResultRow) = ProjectInsight(
        module = row[ProjectInsights.module],
        symbol = row[ProjectInsights.symbol],
        key = row[ProjectInsights.key],
        value = row[ProjectInsights.value],
    )
}
