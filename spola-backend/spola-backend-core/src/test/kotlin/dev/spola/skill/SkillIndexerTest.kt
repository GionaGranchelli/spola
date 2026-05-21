package dev.spola.skill

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SkillIndexerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reindex syncs SKILL md files to DB`() {
        writeSkill("devops", "runner", "# Usage\nRun it.")

        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            val result = SkillIndexer(tempDir, repository).reindex()

            assertThat(result.errors).isEmpty()
            assertThat(result.upserted).isEqualTo(1)
            assertThat(repository.get("runner")).isNotNull
        }
    }

    @Test
    fun `change detection hash mismatch triggers re-upsert`() {
        val skillFile = writeSkill("devops", "runner", "# Usage\nRun it.")

        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            val indexer = SkillIndexer(tempDir, repository)
            indexer.reindex()
            val firstHash = repository.get("runner")!!.bodyHash

            Files.writeString(skillFile, skillFile.readText().replace("Run it.", "Run it again."))

            val result = indexer.reindex()
            val secondHash = repository.get("runner")!!.bodyHash

            assertThat(result.upserted).isEqualTo(1)
            assertThat(secondHash).isNotEqualTo(firstHash)
        }
    }

    @Test
    fun `deleted file removes from DB`() {
        val skillFile = writeSkill("devops", "runner", "# Usage\nRun it.")

        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            val indexer = SkillIndexer(tempDir, repository)
            indexer.reindex()

            Files.delete(skillFile)
            val result = indexer.reindex()

            assertThat(result.deleted).isEqualTo(1)
            assertThat(repository.get("runner")).isNull()
        }
    }

    @Test
    fun `heading extraction creates FTS5 sections`() {
        writeSkill(
            "devops",
            "runner",
            """
                # Setup
                Prepare the environment.

                ## Running
                Running the workflow is safe here.
            """.trimIndent(),
        )

        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            SkillIndexer(tempDir, repository).reindex()

            val results = repository.search("running")
            assertThat(results.map { it.sectionTitle }).contains("Running")
        }
    }

    private fun writeSkill(category: String, name: String, body: String): Path {
        val skillDir = tempDir.resolve(category).resolve(name)
        Files.createDirectories(skillDir.resolve("references"))
        Files.writeString(skillDir.resolve("references").resolve("guide.md"), "Guide")
        val skillFile = skillDir.resolve("SKILL.md")
        Files.writeString(
            skillFile,
            """
            |---
            |name: $name
            |description: ${name.replaceFirstChar(Char::titlecase)} skill
            |category: $category
            |references:
            |  - references/guide.md
            |---
            |$body
            """.trimMargin(),
        )
        return skillFile
    }

    private fun Path.readText(): String = Files.readString(this)
}
