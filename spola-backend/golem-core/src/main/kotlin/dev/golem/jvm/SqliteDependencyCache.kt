package dev.spola.jvm

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Paths

data class ModuleDependency(
    val moduleName: String,
    val dependency: String,
    val type: String,
)

class SqliteDependencyCache(dbPath: String) {
    private val database = Database.connect(
        url = "jdbc:sqlite:$dbPath",
        driver = "org.sqlite.JDBC",
    )

    init {
        val path = Paths.get(dbPath).toAbsolutePath()
        path.parent?.let { Files.createDirectories(it) }
        transaction(database) {
            SchemaUtils.create(DependencyCache)
        }
    }

    object DependencyCache : Table("dependency_cache") {
        val moduleName = varchar("module_name", 256)
        val dependency = varchar("dependency", 512)
        val type = varchar("type", 64)

        init {
            uniqueIndex(moduleName, dependency)
        }
    }

    fun replaceAll(dependencies: List<ModuleDependency>) {
        transaction(database) {
            DependencyCache.deleteAll()
            DependencyCache.batchInsert(dependencies.distinctBy { "${it.moduleName}:${it.dependency}" }) {
                this[DependencyCache.moduleName] = it.moduleName
                this[DependencyCache.dependency] = it.dependency
                this[DependencyCache.type] = it.type
            }
        }
    }

    fun list(): List<ModuleDependency> = transaction(database) {
        DependencyCache.selectAll().map {
            ModuleDependency(
                moduleName = it[DependencyCache.moduleName],
                dependency = it[DependencyCache.dependency],
                type = it[DependencyCache.type],
            )
        }
    }
}
