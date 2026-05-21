package dev.spola.jvm

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

class SqliteSymbolIndex(private val database: Database) {
    init {
        transaction(database) {
            SchemaUtils.create(Symbols)
        }
    }

    object Symbols : Table("symbols") {
        val id = integer("id").autoIncrement()
        val module = varchar("module", 256)
        val name = varchar("name", 512)
        val kind = varchar("kind", 64)
        val file = varchar("file", 2048)
        val line = integer("line")
        val column = integer("column")
        val visibility = varchar("visibility", 64).nullable()

        override val primaryKey = PrimaryKey(id)
    }

    suspend fun indexModule(module: ProjectModule, projectRoot: Path) {
        val symbols = extractModuleSymbols(module, projectRoot)
        transaction(database) {
            Symbols.deleteWhere { Symbols.module eq module.name }
            batchInsertSymbols(symbols)
        }
    }

    /** Index a module inside an existing transaction. Keeps the same contract but caller manages tx. */
    fun indexModuleInTransaction(module: ProjectModule, projectRoot: Path) {
        val symbols = extractModuleSymbols(module, projectRoot)
        Symbols.deleteWhere { Symbols.module eq module.name }
        batchInsertSymbols(symbols)
    }

    private fun batchInsertSymbols(symbols: List<SymbolLocation>) {
        Symbols.batchInsert(symbols) { symbol ->
            this[Symbols.module] = symbol.module
            this[Symbols.name] = symbol.name
            this[Symbols.kind] = symbol.kind.name
            this[Symbols.file] = symbol.file
            this[Symbols.line] = symbol.line
            this[Symbols.column] = symbol.column
            this[Symbols.visibility] = symbol.visibility
        }
    }

    /** Replace all symbols inside an existing transaction. */
    fun rebuildInTransaction(snapshot: JvmProjectSnapshot) {
        val root = Path.of(snapshot.projectDir).toAbsolutePath().normalize()
        snapshot.modules.forEach { indexModuleInTransaction(it, root) }
        deduplicateInTransaction()
    }

    suspend fun replaceAll(snapshot: JvmProjectSnapshot) {
        val root = Path.of(snapshot.projectDir).toAbsolutePath().normalize()
        transaction(database) {
            Symbols.deleteAll()
        }
        snapshot.modules.forEach { indexModule(it, root) }
        deduplicate()
    }

    suspend fun search(query: String, kind: SymbolKind? = null, module: String? = null): List<SymbolLocation> {
        val trimmed = query.trim()
        return transaction(database) {
            var op: Op<Boolean> = Symbols.name like "%$trimmed%"
            if (kind != null) op = op and (Symbols.kind eq kind.name)
            if (!module.isNullOrBlank()) op = op and (Symbols.module eq module)
            Symbols.selectAll()
                .where { op }
                .orderBy(Symbols.module to SortOrder.ASC, Symbols.file to SortOrder.ASC, Symbols.line to SortOrder.ASC)
                .limit(100)
                .map { rowToSymbol(it) }
        }
    }

    suspend fun searchByModule(module: String): List<SymbolLocation> {
        return transaction(database) {
            Symbols.selectAll()
                .where { Symbols.module eq module }
                .orderBy(Symbols.file to SortOrder.ASC, Symbols.line to SortOrder.ASC)
                .map { rowToSymbol(it) }
        }
    }

    suspend fun searchByFile(file: String): List<SymbolLocation> {
        return transaction(database) {
            Symbols.selectAll()
                .where { Symbols.file eq file }
                .orderBy(Symbols.line, SortOrder.ASC)
                .map { rowToSymbol(it) }
        }
    }

    suspend fun deduplicate() {
        transaction(database) {
            deduplicateInTransaction()
        }
    }

    /** Deduplicate inside an existing transaction. */
    fun deduplicateInTransaction() {
        val all = Symbols.selectAll().orderBy(Symbols.id, SortOrder.ASC).toList()
        val seen = mutableSetOf<String>()
        all.forEach { row ->
            val key = "${row[Symbols.module]}:${row[Symbols.name]}:${row[Symbols.kind]}:${row[Symbols.file]}:${row[Symbols.line]}"
            if (!seen.add(key)) {
                Symbols.deleteWhere { Symbols.id eq row[Symbols.id] }
            }
        }
    }

    private fun extractModuleSymbols(module: ProjectModule, projectRoot: Path): List<SymbolLocation> {
        val kotlinExtractor = KotlinSymbolExtractor()
        val javaExtractor = JavaSymbolExtractor()
        val dirs = (module.sourceDirs + module.testDirs).map { projectRoot.resolve(it).normalize() }
        return dirs.flatMap { dir ->
            if (!Files.isDirectory(dir)) return@flatMap emptyList()
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .flatMap { file ->
                        when (file.extension) {
                            "kt" -> kotlinExtractor.extract(file, module.name, projectRoot).stream()
                            "java" -> javaExtractor.extract(file, module.name, projectRoot).stream()
                            else -> emptyList<SymbolLocation>().stream()
                        }
                    }
                    .toList()
            }
        }
    }

    private fun rowToSymbol(row: ResultRow) = SymbolLocation(
        name = row[Symbols.name],
        kind = SymbolKind.valueOf(row[Symbols.kind]),
        file = row[Symbols.file],
        line = row[Symbols.line],
        column = row[Symbols.column],
        module = row[Symbols.module],
        visibility = row[Symbols.visibility],
    )
}
