package dev.spola.jvm

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SqliteJvmProjectIndex(dbPath: String) : JvmProjectIndex {
    private val database = Database.connect(
        url = "jdbc:sqlite:$dbPath",
        driver = "org.sqlite.JDBC",
    )
    val symbols = SqliteSymbolIndex(database)
    private val scanner = GradleProjectScanner()

    init {
        val path = Paths.get(dbPath).toAbsolutePath()
        path.parent?.let { Files.createDirectories(it) }
        transaction(database) {
            SchemaUtils.create(ProjectScans, ProjectModules, ModuleSources, ModuleDependencies)
        }
    }

    object ProjectScans : Table("project_scans") {
        val id = integer("id").autoIncrement()
        val projectDir = varchar("project_dir", 2048)
        val scannedAt = long("scanned_at")

        override val primaryKey = PrimaryKey(id)
    }

    object ProjectModules : Table("project_modules") {
        val id = integer("id").autoIncrement()
        val scanId = integer("scan_id")
        val name = varchar("name", 256)
        val path = varchar("path", 2048)
        val isRoot = bool("is_root")
        val plugins = text("plugins")
        val javaVersion = varchar("java_version", 128).nullable()
        val kotlinVersion = varchar("kotlin_version", 128).nullable()

        override val primaryKey = PrimaryKey(id)
    }

    object ModuleSources : Table("module_sources") {
        val id = integer("id").autoIncrement()
        val moduleId = integer("module_id")
        val path = varchar("path", 2048)
        val sourceSet = varchar("source_set", 32)

        override val primaryKey = PrimaryKey(id)
    }

    object ModuleDependencies : Table("module_dependencies") {
        val id = integer("id").autoIncrement()
        val moduleId = integer("module_id")
        val dependency = text("dependency")

        override val primaryKey = PrimaryKey(id)
    }

    override suspend fun scan(projectDir: String): JvmProjectSnapshot {
        val root = Paths.get(projectDir).toAbsolutePath().normalize().toString()
        val snapshot = JvmProjectSnapshot(
            projectDir = root,
            scannedAt = System.currentTimeMillis(),
            modules = scanner.scan(root),
        )
        persistSnapshot(snapshot)
        return snapshot
    }

    suspend fun refreshChangedPaths(projectDir: String, changedPaths: List<Path>): JvmProjectSnapshot {
        val existing = getSnapshot() ?: return scan(projectDir)
        if (changedPaths.isEmpty()) return existing

        val normalizedChanges = changedPaths.map { it.toAbsolutePath().normalize() }
        val requiresFullRescan = normalizedChanges.any {
            val fileName = it.fileName?.toString()
            fileName == "build.gradle" || fileName == "build.gradle.kts" || fileName == "settings.gradle.kts"
        }
        if (requiresFullRescan) return scan(projectDir)

        val root = Path.of(existing.projectDir).toAbsolutePath().normalize()
        val touchedModules = existing.modules.filter { module ->
            val modulePath = Path.of(module.path).toAbsolutePath().normalize()
            normalizedChanges.any { it.startsWith(modulePath) }
        }
        if (touchedModules.isEmpty()) return existing

        val scannedAt = System.currentTimeMillis()
        transaction(database) {
            ProjectScans.update({ ProjectScans.projectDir eq existing.projectDir }) {
                it[ProjectScans.scannedAt] = scannedAt
            }
            touchedModules.forEach { module ->
                symbols.indexModuleInTransaction(module, root)
            }
            symbols.deduplicateInTransaction()
        }
        return existing.copy(scannedAt = scannedAt)
    }

    private fun persistSnapshot(snapshot: JvmProjectSnapshot) {
        // Single transaction: metadata + symbols are committed atomically
        transaction(database) {
            ProjectScans.deleteAll()
            ProjectModules.deleteAll()
            ModuleSources.deleteAll()
            ModuleDependencies.deleteAll()
            SqliteSymbolIndex.Symbols.deleteAll()

            val scanId = ProjectScans.insert {
                it[ProjectScans.projectDir] = snapshot.projectDir
                it[scannedAt] = snapshot.scannedAt
            } get ProjectScans.id

            snapshot.modules.forEach { module ->
                val moduleId = ProjectModules.insert {
                    it[ProjectModules.scanId] = scanId
                    it[name] = module.name
                    it[path] = module.path
                    it[isRoot] = module.isRoot
                    it[plugins] = module.plugins.joinToString("\n")
                    it[javaVersion] = module.javaVersion
                    it[kotlinVersion] = module.kotlinVersion
                } get ProjectModules.id
                ModuleSources.batchInsert(module.sourceDirs) {
                    this[ModuleSources.moduleId] = moduleId
                    this[ModuleSources.path] = it
                    this[ModuleSources.sourceSet] = "main"
                }
                ModuleSources.batchInsert(module.testDirs) {
                    this[ModuleSources.moduleId] = moduleId
                    this[ModuleSources.path] = it
                    this[ModuleSources.sourceSet] = "test"
                }
                ModuleDependencies.batchInsert(module.dependencies) {
                    this[ModuleDependencies.moduleId] = moduleId
                    this[ModuleDependencies.dependency] = it
                }
            }

            symbols.rebuildInTransaction(snapshot)
        }
    }

    override suspend fun getSnapshot(): JvmProjectSnapshot? {
        return transaction(database) {
            val scan = ProjectScans.selectAll().orderBy(ProjectScans.scannedAt, SortOrder.DESC).limit(1).singleOrNull()
                ?: return@transaction null
            val modules = ProjectModules.selectAll()
                .where { ProjectModules.scanId eq scan[ProjectScans.id] }
                .orderBy(ProjectModules.name, SortOrder.ASC)
                .map { moduleRow ->
                    val moduleId = moduleRow[ProjectModules.id]
                    val sources = ModuleSources.selectAll().where { ModuleSources.moduleId eq moduleId }.toList()
                    ProjectModule(
                        name = moduleRow[ProjectModules.name],
                        path = moduleRow[ProjectModules.path],
                        isRoot = moduleRow[ProjectModules.isRoot],
                        sourceDirs = sources.filter { it[ModuleSources.sourceSet] == "main" }.map { it[ModuleSources.path] },
                        testDirs = sources.filter { it[ModuleSources.sourceSet] == "test" }.map { it[ModuleSources.path] },
                        plugins = moduleRow[ProjectModules.plugins].lines().filter { it.isNotBlank() },
                        javaVersion = moduleRow[ProjectModules.javaVersion],
                        kotlinVersion = moduleRow[ProjectModules.kotlinVersion],
                        dependencies = ModuleDependencies.selectAll()
                            .where { ModuleDependencies.moduleId eq moduleId }
                            .map { it[ModuleDependencies.dependency] },
                    )
                }
            JvmProjectSnapshot(
                projectDir = scan[ProjectScans.projectDir],
                scannedAt = scan[ProjectScans.scannedAt],
                modules = modules,
            )
        }
    }

    override suspend fun clear() {
        transaction(database) {
            ProjectScans.deleteAll()
            ProjectModules.deleteAll()
            ModuleSources.deleteAll()
            ModuleDependencies.deleteAll()
            SqliteSymbolIndex.Symbols.deleteAll()
        }
    }

    suspend fun searchSymbols(query: String, kind: SymbolKind? = null, module: String? = null): List<SymbolLocation> =
        symbols.search(query, kind, module)

    suspend fun fileSymbols(path: String): List<SymbolLocation> = symbols.searchByFile(path)

    /**
     * Return a snapshot that matches [workdir], rescanning if the cached
     * snapshot was taken from a different directory.
     */
    suspend fun getFreshSnapshot(workdir: String): JvmProjectSnapshot {
        val cached = getSnapshot()
        if (cached != null && Paths.get(cached.projectDir).normalize() == Paths.get(workdir).normalize()) {
            return cached
        }
        return scan(workdir)
    }
}
