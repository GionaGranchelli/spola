package dev.spola.skill

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class SkillIndexer(
    private val skillsDir: Path,
    private val repository: SkillRepository,
) {
    /** Sync files to DB. Returns stats. */
    fun reindex(): IndexResult {
        if (!Files.isDirectory(skillsDir)) {
            val deleted = repository.getAll().onEach { repository.delete(it.name) }.size
            return IndexResult(upserted = 0, deleted = deleted, errors = emptyList())
        }

        val diskFiles = SkillLoader.discoverSkillFiles(skillsDir)
        val currentPaths = diskFiles.map { it.toAbsolutePath().normalize().toString() }.toSet()
        val errors = mutableListOf<String>()
        var upserted = 0

        for (file in diskFiles) {
            val content = try {
                Files.readString(file)
            } catch (e: Exception) {
                errors += "Failed to read $file: ${e.message}"
                continue
            }

            val skill = SkillLoader.loadFromFile(file, skillsDir)
            if (skill == null) {
                errors += "Failed to parse skill from $file"
                continue
            }

            val contentHash = hash(content)
            val existing = repository.get(skill.name)
            val normalizedPath = file.toAbsolutePath().normalize().toString()
            if (existing == null || existing.bodyHash != contentHash || existing.filePath != normalizedPath) {
                repository.upsert(
                    skill = skill,
                    filePath = normalizedPath,
                    bodyHash = contentHash,
                    files = discoverFiles(skill.name, file.parent),
                    sections = extractSections(skill.body),
                )
                upserted += 1
            }
        }

        val deletedRows = repository.getAll().filter { it.filePath !in currentPaths }
        deletedRows.forEach { repository.delete(it.name) }

        return IndexResult(upserted = upserted, deleted = deletedRows.size, errors = errors)
    }

    /** Hash a file's content for change detection. */
    private fun hash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun discoverFiles(skillName: String, skillDir: Path?): List<SkillFileRow> {
        if (skillDir == null || !Files.isDirectory(skillDir)) {
            return emptyList()
        }

        val mappings = mapOf(
            "references" to "reference",
            "templates" to "template",
            "scripts" to "script",
        )

        return mappings.flatMap { (directoryName, fileType) ->
            val root = skillDir.resolve(directoryName)
            if (!Files.isDirectory(root)) {
                emptyList()
            } else {
                Files.walk(root).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .map { path ->
                            SkillFileRow(
                                skillName = skillName,
                                fileType = fileType,
                                fileName = skillDir.relativize(path).toString().replace('\\', '/'),
                                filePath = path.toAbsolutePath().normalize().toString(),
                            )
                        }
                        .toList()
                }
            }
        }
    }
}

data class Section(val title: String, val body: String)

data class IndexResult(val upserted: Int, val deleted: Int, val errors: List<String>)
