package dev.spola.skill

import dev.spola.SpolaConfig
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for [SkillTools]: tool registration, skill listing, loading.
 */
class SkillToolsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun writeSkill(dir: Path, name: String, category: String = "") {
        val skillDir = if (category.isNotBlank()) {
            val catDir = dir.resolve(category)
            java.nio.file.Files.createDirectories(catDir)
            catDir.resolve(name)
        } else {
            dir.resolve(name)
        }
        java.nio.file.Files.createDirectories(skillDir)
        skillDir.resolve("SKILL.md").toFile().writeText(
            """
            |---
            |name: $name
            |description: Skill $name description
            |category: $category
            |---
            |# $name Body
            |
            |Content for $name.
            |""".trimMargin()
        )
    }

    // ─── skills_list tests ────────────────────────────────────────────

    @Test
    fun `skills_list returns empty when no skills directory`() = runTest {
        val nonExistentDir = tempDir.resolve("nonexistent-skills")
        val registry = ToolRegistry()

        SkillTools.register(registry, skillsDir = nonExistentDir)
        val tool = registry.get("skills_list")
        assertThat(tool).isNotNull

        val result = tool!!.execute(emptyMap())
        assertThat(result.success).isTrue
        assertThat(result.output).contains("No skills installed")
    }

    @Test
    fun `skills_list returns categorized catalog`() = runTest {
        writeSkill(tempDir, "code-review", category = "devops")
        writeSkill(tempDir, "doc-writer", category = "devops")
        writeSkill(tempDir, "security-audit", category = "security")

        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        val tool = registry.get("skills_list")!!

        val result = tool.execute(emptyMap())

        assertThat(result.success).isTrue
        assertThat(result.output).contains("devops")
        assertThat(result.output).contains("security")
        assertThat(result.output).contains("code-review")
        assertThat(result.output).contains("doc-writer")
        assertThat(result.output).contains("security-audit")
    }

    @Test
    fun `skills_list with category filter`() = runTest {
        writeSkill(tempDir, "code-review", category = "devops")
        writeSkill(tempDir, "doc-writer", category = "devops")
        writeSkill(tempDir, "security-audit", category = "security")

        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        val tool = registry.get("skills_list")!!

        val result = tool.execute(mapOf("category" to "devops"))

        assertThat(result.success).isTrue
        assertThat(result.output).contains("code-review")
        assertThat(result.output).contains("doc-writer")
        assertThat(result.output).doesNotContain("security-audit")
    }

    // ─── load_skill tests ─────────────────────────────────────────────

    @Test
    fun `load_skill returns full body`() = runTest {
        writeSkill(tempDir, "test-skill", category = "devops")

        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        val tool = registry.get("load_skill")!!

        val result = tool.execute(mapOf("name" to "test-skill"))

        assertThat(result.success).isTrue
        assertThat(result.output).contains("## Skill: test-skill")
        assertThat(result.output).contains("# test-skill Body")
        assertThat(result.output).contains("Content for test-skill")
    }

    @Test
    fun `load_skill for nonexistent skill`() = runTest {
        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        val tool = registry.get("load_skill")!!

        val result = tool.execute(mapOf("name" to "nonexistent"))

        assertThat(result.success).isFalse
        assertThat(result.output).contains("Skill 'nonexistent' not found")
    }

    @Test
    fun `load_skill requires name parameter`() = runTest {
        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        val tool = registry.get("load_skill")!!

        val result = tool.execute(emptyMap())

        assertThat(result.success).isFalse
        assertThat(result.output).contains("name is required")
    }

    // ─── load_reference tests ─────────────────────────────────────────

    @Test
    fun `load_reference returns reference file`() = runTest {
        val skillDir = tempDir.resolve("devops").resolve("test-skill")
        java.nio.file.Files.createDirectories(skillDir)
        val refDir = skillDir.resolve("references")
        java.nio.file.Files.createDirectories(refDir)
        refDir.resolve("guide.md").toFile().writeText("# Reference Guide\nUseful info.")
        skillDir.resolve("SKILL.md").toFile().writeText(
            """
            |---
            |name: test-skill
            |description: A test skill
            |category: devops
            |references:
            |  - references/guide.md
            |---
            |# Body
            |""".trimMargin()
        )

        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        val tool = registry.get("load_reference")!!

        val result = tool.execute(mapOf("skill" to "test-skill", "file" to "references/guide.md"))

        assertThat(result.success).isTrue
        assertThat(result.output).contains("# Reference Guide")
        assertThat(result.output).contains("Useful info.")
    }

    @Test
    fun `explicit repository still populates in-memory catalog`() = runTest {
        val skillDir = tempDir.resolve("devops").resolve("repo-skill")
        java.nio.file.Files.createDirectories(skillDir)
        val refDir = skillDir.resolve("references")
        java.nio.file.Files.createDirectories(refDir)
        refDir.resolve("guide.md").toFile().writeText("# Repo Guide\nUseful info.")
        skillDir.resolve("SKILL.md").toFile().writeText(
            """
            |---
            |name: repo-skill
            |description: A skill loaded with an explicit repository
            |category: devops
            |references:
            |  - references/guide.md
            |---
            |# Body
            |""".trimMargin()
        )

        val registry = ToolRegistry()
        val repository = SkillRepository(tempDir.resolve("skills.db").toString())
        try {
            SkillTools.register(registry, skillsDir = tempDir, repository = repository)

            val listResult = registry.get("skills_list")!!.execute(mapOf("category" to "devops"))
            assertThat(listResult.success).isTrue
            assertThat(listResult.output).contains("repo-skill")

            val referenceResult = registry.get("load_reference")!!
                .execute(mapOf("skill" to "repo-skill", "file" to "references/guide.md"))
            assertThat(referenceResult.success).isTrue
            assertThat(referenceResult.output).contains("# Repo Guide")
            assertThat(referenceResult.output).contains("Useful info.")
        } finally {
            repository.close()
        }
    }

    @Test
    fun `load_reference with path traversal rejected`() = runTest {
        val skillDir = tempDir.resolve("test-skill")
        java.nio.file.Files.createDirectories(skillDir)
        skillDir.resolve("SKILL.md").toFile().writeText(
            """
            |---
            |name: test-skill
            |description: A test skill
            |---
            |# Body
            |""".trimMargin()
        )

        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        val tool = registry.get("load_reference")!!

        val result = tool.execute(mapOf("skill" to "test-skill", "file" to "../../etc/passwd"))

        assertThat(result.success).isFalse
        assertThat(result.output).contains("Path traversal")
    }

    @Test
    fun `load_reference only allows allowed directories`() = runTest {
        val skillDir = tempDir.resolve("test-skill")
        java.nio.file.Files.createDirectories(skillDir)
        skillDir.resolve("SKILL.md").toFile().writeText(
            """
            |---
            |name: test-skill
            |description: A test skill
            |---
            |# Body
            |""".trimMargin()
        )

        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        val tool = registry.get("load_reference")!!

        val result = tool.execute(mapOf("skill" to "test-skill", "file" to "secret.txt"))

        assertThat(result.success).isFalse
        assertThat(result.output).contains("Only files in references/")
    }

    // ─── skill_list alias tests ───────────────────────────────────────

    @Test
    fun `skill_list alias exists`() = runTest {
        val registry = ToolRegistry()
        SkillTools.register(registry, skillsDir = tempDir)
        assertThat(registry.get("skill_list")).isNotNull
        assertThat(registry.get("skills_list")).isNotNull
    }

    @Test
    fun `search_skills uses internal repository when caller does not provide one`() = runTest {
        val skillDir = tempDir.resolve("deploy-skill")
        java.nio.file.Files.createDirectories(skillDir)
        skillDir.resolve("SKILL.md").toFile().writeText(
            """
            |---
            |name: deploy-skill
            |description: Release automation
            |---
            |# Deploy
            |Deploying the service safely.
            |""".trimMargin()
        )

        val registry = ToolRegistry()
        val config = SpolaConfig(skillsDbPath = tempDir.resolve("skills.db").toString())
        SkillTools.register(registry, skillsDir = tempDir, config = config)
        val tool = registry.get("search_skills")!!

        val result = tool.execute(mapOf("query" to "safely"))

        assertThat(result.success).isTrue
        assertThat(result.output).contains("deploy-skill")
        assertThat(result.output).contains("— Deploy")
    }
}

private fun Path.resolve(vararg segments: String): Path {
    var result = this
    for (s in segments) result = result.resolve(s)
    return result
}
