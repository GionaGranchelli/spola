package dev.spola.skill

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for [SkillLoader]: loading skills from SKILL.md, listing, error handling.
 */
class SkillLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load skill from SKILL md with frontmatter`() {
        val content = """
            |---
            |name: code-review
            |description: Review code for security, style, and test coverage
            |category: devops
            |tags:
            |  - review
            |  - code-quality
            |---
            |# Code Review
            |
            |You are a code review expert. Analyze code for security vulnerabilities.
            |""".trimMargin()

        val skillDir = tempDir.resolve("code-review")
        createDir(skillDir)
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.toFile().writeText(content)

        val skill = SkillLoader.loadFromFile(skillFile)

        assertThat(skill).isNotNull
        assertThat(skill!!.name).isEqualTo("code-review")
        assertThat(skill.description).isEqualTo("Review code for security, style, and test coverage")
        assertThat(skill.category).isEqualTo("devops")
        assertThat(skill.tags).containsExactly("review", "code-quality")
        assertThat(skill.body).contains("code review expert")
    }

    @Test
    fun `uses directory name as fallback name`() {
        val content = """
            |---
            |description: No explicit name, uses dir
            |---
            |# Body
            |""".trimMargin()

        val skillDir = tempDir.resolve("my-custom-skill")
        createDir(skillDir)
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.toFile().writeText(content)

        val skill = SkillLoader.loadFromFile(skillFile)

        assertThat(skill).isNotNull
        assertThat(skill!!.name).isEqualTo("my-custom-skill")
        assertThat(skill.description).isEqualTo("No explicit name, uses dir")
    }

    @Test
    fun `handles empty body`() {
        val content = "---\nname: bodyless\n---"
        val skillDir = tempDir.resolve("bodyless")
        createDir(skillDir)
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.toFile().writeText(content)

        val skill = SkillLoader.loadFromFile(skillFile)
        assertThat(skill).isNotNull
        assertThat(skill!!.name).isEqualTo("bodyless")
        assertThat(skill.body).isEmpty()
    }

    @Test
    fun `missing opening frontmatter returns null`() {
        val content = "Just plain text with no frontmatter\n---"
        val skillDir = tempDir.resolve("broken")
        createDir(skillDir)
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.toFile().writeText(content)

        val skill = SkillLoader.loadFromFile(skillFile)
        assertThat(skill).isNull()
    }

    @Test
    fun `categorized layout parses category from directory structure`() {
        val content = """
            |---
            |name: docker-compose
            |description: Manage Docker Compose
            |---
            |# Docker Compose
            |""".trimMargin()

        val catDir = tempDir.resolve("devops")
        createDir(catDir)
        val skillDir = catDir.resolve("docker-compose")
        createDir(skillDir)
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.toFile().writeText(content)

        val skills = SkillLoader.loadFromDirectory(tempDir)

        assertThat(skills).hasSize(1)
        assertThat(skills[0].name).isEqualTo("docker-compose")
        assertThat(skills[0].category).isEqualTo("devops")
    }

    @Test
    fun `empty directory returns empty list`() {
        val skills = SkillLoader.loadFromDirectory(tempDir)
        assertThat(skills).isEmpty()
    }

    @Test
    fun `nonexistent directory returns empty list`() {
        val skills = SkillLoader.loadFromDirectory(tempDir.resolve("nonexistent"))
        assertThat(skills).isEmpty()
    }

    @Test
    fun `body preserves all markdown formatting`() {
        val content = """
            |---
            |name: docs
            |description: Documentation skill
            |---
            |# Title
            |
            |## Section
            |- List item 1
            |- List item 2
            |
            |```kotlin
            |val x = 1
            |```
            |
            |**bold** and *italic*
            |""".trimMargin()

        val skillDir = tempDir.resolve("docs")
        createDir(skillDir)
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.toFile().writeText(content)

        val skill = SkillLoader.loadFromFile(skillFile)

        assertThat(skill).isNotNull
        assertThat(skill!!.body).contains("# Title")
        assertThat(skill.body).contains("## Section")
        assertThat(skill.body).contains("- List item 1")
        assertThat(skill.body).contains("```kotlin")
        assertThat(skill.body).contains("**bold** and *italic*")
    }

    @Test
    fun `category from frontmatter overrides directory`() {
        val content = """
            |---
            |name: my-skill
            |description: A skill
            |category: frontmatter-cat
            |---
            |# Body
            |""".trimMargin()

        val catDir = tempDir.resolve("some-category")
        createDir(catDir)
        val skillDir = catDir.resolve("my-skill")
        createDir(skillDir)
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.toFile().writeText(content)

        val skill = SkillLoader.loadFromFile(skillFile)

        assertThat(skill).isNotNull
        // Frontmatter category should override directory-derived
        assertThat(skill!!.category).isEqualTo("frontmatter-cat")
    }

    @Test
    fun `default skills directory path`() {
        val defaultDir = SkillLoader.defaultSkillsDir
        assertThat(defaultDir.toString()).endsWith("/.spola/skills")
    }

    @Test
    fun `load skill from SKILL md with CRLF frontmatter`() {
        val content = "---\r\nname: windows-skill\r\ndescription: Works on CRLF\r\n---\r\n# Body\r\n"
        val skillDir = tempDir.resolve("windows-skill")
        createDir(skillDir)
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.toFile().writeText(content)

        val skill = SkillLoader.loadFromFile(skillFile)

        assertThat(skill).isNotNull
        assertThat(skill!!.name).isEqualTo("windows-skill")
        assertThat(skill.description).isEqualTo("Works on CRLF")
        assertThat(skill.body).contains("# Body")
    }
}

private fun createDir(path: Path) = java.nio.file.Files.createDirectories(path)
