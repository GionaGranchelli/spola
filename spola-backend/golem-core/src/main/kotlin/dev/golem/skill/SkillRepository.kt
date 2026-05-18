package dev.spola.skill

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SkillRepository(dbPath: String) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(SkillRepository::class.java)
    private val database = Database.connect(
        url = "jdbc:sqlite:$dbPath",
        driver = "org.sqlite.JDBC",
    )

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    init {
        val path = Path.of(dbPath).toAbsolutePath()
        path.parent?.let(Files::createDirectories)

        transaction(database) {
            SchemaUtils.create(SkillsTable, SkillFilesTable)
            exec(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS skill_sections USING fts5(
                    skill_name,
                    section_title,
                    section_body,
                    tokenize='porter unicode61'
                )
                """.trimIndent(),
            )
        }
    }

    object SkillsTable : Table("skills") {
        val name = text("name")
        val description = text("description")
        val category = text("category").default("")
        val tags = text("tags").default("[]")
        val tools = text("tools").default("[]")
        val references = text("references").default("[]")
        val body = text("body").default("")
        val bodyHash = text("body_hash")
        val filePath = text("file_path")
        val useCount = integer("use_count").default(0)
        val lastUsed = text("last_used").nullable()
        val pinned = bool("pinned").default(false)

        override val primaryKey = PrimaryKey(name)
    }

    object SkillFilesTable : Table("skill_files") {
        val skillName = text("skill_name").references(SkillsTable.name, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
        val fileType = text("file_type")
        val fileName = text("file_name")
        val filePath = text("file_path")

        override val primaryKey = PrimaryKey(skillName, fileType, fileName)
    }

    internal fun upsert(
        skill: SkillDefinition,
        filePath: String,
        bodyHash: String,
        files: List<SkillFileRow>,
        sections: List<Section>,
    ): SkillDefinition {
        transaction(database) {
            val existing = SkillsTable.selectAll().where { SkillsTable.name eq skill.name }.singleOrNull()
            if (existing == null) {
                SkillsTable.insert {
                    it[name] = skill.name
                    it[description] = skill.description
                    it[category] = skill.category
                    it[tags] = encode(skill.tags)
                    it[tools] = encode(skill.tools)
                    it[references] = encode(skill.references)
                    it[body] = skill.body
                    it[SkillsTable.bodyHash] = bodyHash
                    it[SkillsTable.filePath] = filePath
                }
            } else {
                SkillsTable.update({ SkillsTable.name eq skill.name }) {
                    it[description] = skill.description
                    it[category] = skill.category
                    it[tags] = encode(skill.tags)
                    it[tools] = encode(skill.tools)
                    it[references] = encode(skill.references)
                    it[body] = skill.body
                    it[SkillsTable.bodyHash] = bodyHash
                    it[SkillsTable.filePath] = filePath
                }
            }

            SkillFilesTable.deleteWhere { SkillFilesTable.skillName eq skill.name }
            files.forEach { file ->
                SkillFilesTable.insert {
                    it[skillName] = skill.name
                    it[fileType] = file.fileType
                    it[fileName] = file.fileName
                    it[SkillFilesTable.filePath] = file.filePath
                }
            }

            val safeSkillName = skill.name.replace("'", "''")
            exec("DELETE FROM skill_sections WHERE skill_name = '$safeSkillName'")
            val normalizedSections = if (sections.isEmpty()) {
                listOf(Section(title = "Overview", body = ""))
            } else {
                sections
            }
            normalizedSections.forEach { section ->
                val escapedBody = section.body.replace("'", "''")
                val escapedTitle = section.title.replace("'", "''")
                exec("INSERT INTO skill_sections(skill_name, section_title, section_body) VALUES ('$safeSkillName', '$escapedTitle', '$escapedBody')")
            }
        }

        return skill
    }

    fun delete(name: String) {
        transaction(database) {
            val safeName = name.replace("'", "''")
            exec("DELETE FROM skill_sections WHERE skill_name = '$safeName'")
            SkillFilesTable.deleteWhere { skillName eq name }
            SkillsTable.deleteWhere { SkillsTable.name eq name }
        }
    }

    fun get(name: String): SkillRow? = transaction(database) {
        SkillsTable.selectAll()
            .where { SkillsTable.name eq name.lowercase() }
            .singleOrNull()
            ?.let(::rowToSkill)
    }

    fun getAll(): List<SkillRow> = transaction(database) {
        SkillsTable.selectAll().map(::rowToSkill)
    }

    fun search(query: String, limit: Int = 10): List<SearchResult> {
        if (query.isBlank()) {
            return emptyList()
        }

        val escapedQuery = query.replace("'", "''")
        val sql = """
            SELECT skill_name,
                   section_title,
                   snippet(skill_sections, 2, '', '', '...', 32) AS snippet
            FROM skill_sections
            WHERE skill_sections MATCH '$escapedQuery'
            ORDER BY bm25(skill_sections)
            LIMIT ${limit.coerceAtLeast(1)}
        """.trimIndent()

        return transaction(database) {
            val results = mutableListOf<SearchResult>()
            try {
                exec(sql) { rs ->
                    while (rs.next()) {
                        results += SearchResult(
                            skillName = rs.getString("skill_name"),
                            sectionTitle = rs.getString("section_title"),
                            snippet = rs.getString("snippet") ?: "",
                        )
                    }
                }
                results
            } catch (e: Exception) {
                logger.warn("Failed skill FTS search for query '{}': {}", query, e.message)
                emptyList()
            }
        }
    }

    fun recordUsage(name: String) {
        val now = LocalDateTime.now().format(formatter)
        transaction(database) {
            val safeName = name.replace("'", "''")
            val safeNow = now.replace("'", "''")
            exec("UPDATE skills SET use_count = use_count + 1, last_used = '$safeNow' WHERE name = '$safeName'")
        }
    }

    fun getUsageStats(name: String): UsageStats? = transaction(database) {
        SkillsTable.selectAll()
            .where { SkillsTable.name eq name.lowercase() }
            .singleOrNull()
            ?.let {
                UsageStats(
                    useCount = it[SkillsTable.useCount],
                    lastUsed = it[SkillsTable.lastUsed],
                    pinned = it[SkillsTable.pinned],
                )
            }
    }

    fun listFiles(skillName: String): List<SkillFileRow> = transaction(database) {
        SkillFilesTable.selectAll()
            .where { SkillFilesTable.skillName eq skillName.lowercase() }
            .map {
                SkillFileRow(
                    skillName = it[SkillFilesTable.skillName],
                    fileType = it[SkillFilesTable.fileType],
                    fileName = it[SkillFilesTable.fileName],
                    filePath = it[SkillFilesTable.filePath],
                )
            }
    }

    fun hasFile(skillName: String, fileName: String): Boolean = transaction(database) {
        SkillFilesTable.selectAll()
            .where { (SkillFilesTable.skillName eq skillName.lowercase()) and (SkillFilesTable.fileName eq fileName) }
            .any()
    }

    override fun close() {
        TransactionManager.closeAndUnregister(database)
    }

    private fun rowToSkill(row: ResultRow): SkillRow = SkillRow(
        name = row[SkillsTable.name],
        description = row[SkillsTable.description],
        category = row[SkillsTable.category],
        tags = decodeList(row[SkillsTable.tags]),
        tools = decodeTools(row[SkillsTable.tools]),
        references = decodeList(row[SkillsTable.references]),
        body = row[SkillsTable.body],
        bodyHash = row[SkillsTable.bodyHash],
        filePath = row[SkillsTable.filePath],
        useCount = row[SkillsTable.useCount],
        lastUsed = row[SkillsTable.lastUsed],
        pinned = row[SkillsTable.pinned],
    )

    private fun encode(value: Any): String = mapper.writeValueAsString(value)

    private fun decodeList(json: String): List<String> = runCatching {
        mapper.readValue(json, object : TypeReference<List<String>>() {})
    }.getOrDefault(emptyList())

    private fun decodeTools(json: String): List<SkillToolDef> = runCatching {
        mapper.readValue(json, object : TypeReference<List<SkillToolDef>>() {})
    }.getOrElse {
        decodeList(json).map { SkillToolDef(name = it, description = "") }
    }

    companion object {
        private val mapper = jacksonObjectMapper()
    }
}

data class SkillRow(
    val name: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    val tools: List<SkillToolDef>,
    val references: List<String>,
    val body: String,
    val bodyHash: String,
    val filePath: String,
    val useCount: Int,
    val lastUsed: String?,
    val pinned: Boolean,
)

data class SearchResult(
    val skillName: String,
    val sectionTitle: String,
    val snippet: String,
)

data class UsageStats(
    val useCount: Int,
    val lastUsed: String?,
    val pinned: Boolean,
)

data class SkillFileRow(
    val skillName: String,
    val fileType: String,
    val fileName: String,
    val filePath: String,
)
