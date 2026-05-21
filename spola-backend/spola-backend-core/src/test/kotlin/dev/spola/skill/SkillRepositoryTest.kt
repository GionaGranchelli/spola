package dev.spola.skill

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest

class SkillRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `inserts and retrieves skill`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            val skill = skill(
                name = "runner",
                body = "# Usage\nThis skill helps with running builds.",
            )

            repository.upsert(skill, "/tmp/runner/SKILL.md", skillFileHash(skill), emptyList(), extractSections(skill.body))

            val row = repository.get("runner")
            assertThat(row).isNotNull
            assertThat(row!!.name).isEqualTo("runner")
            assertThat(row.description).isEqualTo("Runner skill")
            assertThat(row.tags).containsExactly("build")
        }
    }

    @Test
    fun `upsert updates existing skill by name`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            repository.upsert(
                skill(name = "runner", description = "Old", body = "# One\nOld"),
                "/tmp/runner/SKILL.md",
                skillFileHash(skill(name = "runner", description = "Old", body = "# One\nOld")),
                emptyList(),
                extractSections("# One\nOld"),
            )
            repository.upsert(
                skill(name = "runner", description = "New", body = "# Two\nNew"),
                "/tmp/runner/SKILL.md",
                skillFileHash(skill(name = "runner", description = "New", body = "# Two\nNew")),
                emptyList(),
                extractSections("# Two\nNew"),
            )

            val row = repository.get("runner")
            assertThat(row!!.description).isEqualTo("New")
            assertThat(row.body).contains("New")
        }
    }

    @Test
    fun `delete removes skill`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            val skill = skill(name = "runner")
            repository.upsert(skill, "/tmp/runner/SKILL.md", skillFileHash(skill), emptyList(), extractSections(skill.body))

            repository.delete("runner")

            assertThat(repository.get("runner")).isNull()
        }
    }

    @Test
    fun `search returns matching sections`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            repository.upsert(
                skill(
                    name = "deploy",
                    body = """
                        # Setup
                        Install dependencies.

                        ## Running
                        Run the deployment workflow safely.
                    """.trimIndent(),
                ),
                "/tmp/deploy/SKILL.md",
                skillFileHash(
                    skill(
                        name = "deploy",
                        body = """
                            # Setup
                            Install dependencies.

                            ## Running
                            Run the deployment workflow safely.
                        """.trimIndent(),
                    ),
                ),
                emptyList(),
                extractSections(
                    """
                        # Setup
                        Install dependencies.

                        ## Running
                        Run the deployment workflow safely.
                    """.trimIndent(),
                ),
            )

            val results = repository.search("deployment", limit = 5)

            assertThat(results).isNotEmpty
            assertThat(results.first().skillName).isEqualTo("deploy")
            assertThat(results.first().sectionTitle).isEqualTo("Running")
        }
    }

    @Test
    fun `search with FTS5 stemming works`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            repository.upsert(
                skill(
                    name = "runner",
                    body = "# Workflow\nThis skill is about running services in production.",
                ),
                "/tmp/runner/SKILL.md",
                skillFileHash(
                    skill(
                        name = "runner",
                        body = "# Workflow\nThis skill is about running services in production.",
                    ),
                ),
                emptyList(),
                extractSections("# Workflow\nThis skill is about running services in production."),
            )

            val results = repository.search("run")

            assertThat(results).isNotEmpty
            assertThat(results.first().skillName).isEqualTo("runner")
        }
    }

    @Test
    fun `malformed FTS5 query returns empty list`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            val skill = skill(name = "runner", body = "# Usage\nRun it.")
            repository.upsert(skill, "/tmp/runner/SKILL.md", skillFileHash(skill), emptyList(), extractSections(skill.body))

            val results = repository.search("*")

            assertThat(results).isEmpty()
        }
    }

    @Test
    fun `upsert preserves empty sections between consecutive headings`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            repository.upsert(
                skill(
                    name = "deploy",
                    body = """
                        # Setup
                        ## Running
                        Run the deployment workflow safely.
                    """.trimIndent(),
                ),
                "/tmp/deploy/SKILL.md",
                skillFileHash(
                    skill(
                        name = "deploy",
                        body = """
                            # Setup
                            ## Running
                            Run the deployment workflow safely.
                        """.trimIndent(),
                    ),
                ),
                emptyList(),
                extractSections(
                    """
                        # Setup
                        ## Running
                        Run the deployment workflow safely.
                    """.trimIndent(),
                ),
            )

            val results = repository.search("setup")

            assertThat(results).isNotEmpty
            assertThat(results.first().sectionTitle).isEqualTo("Setup")
        }
    }

    @Test
    fun `recordUsage increments count`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            val skill = skill(name = "runner")
            repository.upsert(skill, "/tmp/runner/SKILL.md", skillFileHash(skill), emptyList(), extractSections(skill.body))

            repository.recordUsage("runner")
            repository.recordUsage("runner")

            val stats = repository.getUsageStats("runner")
            assertThat(stats).isNotNull
            assertThat(stats!!.useCount).isEqualTo(2)
            assertThat(stats.lastUsed).isNotNull
        }
    }

    @Test
    fun `getAll returns all skills`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            val first = skill(name = "one")
            val second = skill(name = "two")
            repository.upsert(first, "/tmp/one/SKILL.md", skillFileHash(first), emptyList(), extractSections(first.body))
            repository.upsert(second, "/tmp/two/SKILL.md", skillFileHash(second), emptyList(), extractSections(second.body))

            assertThat(repository.getAll()).hasSize(2)
        }
    }

    @Test
    fun `get returns null for nonexistent skill`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            assertThat(repository.get("missing")).isNull()
        }
    }

    @Test
    fun `empty DB search returns empty list`() {
        SkillRepository(tempDir.resolve("skills.db").toString()).use { repository ->
            assertThat(repository.search("missing")).isEmpty()
        }
    }

    private fun skill(
        name: String = "runner",
        description: String = "Runner skill",
        body: String = "# Overview\nBody",
    ) = SkillDefinition(
        name = name,
        description = description,
        category = "devops",
        tags = listOf("build"),
        references = listOf("references/guide.md"),
        body = body,
    )

    private fun skillFileHash(skill: SkillDefinition): String = sha256(
        """
        ---
        name: ${skill.name}
        description: ${skill.description}
        category: ${skill.category}
        tags:
          - ${skill.tags.joinToString("\n  - ")}
        references:
          - ${skill.references.joinToString("\n  - ")}
        ---
        ${skill.body}
        """.trimIndent(),
    )

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
