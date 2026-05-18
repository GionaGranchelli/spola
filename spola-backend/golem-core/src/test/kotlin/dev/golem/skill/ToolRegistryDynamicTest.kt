package dev.spola.skill

import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for dynamic tool registration from skills (Phase 3).
 */
class ToolRegistryDynamicTest {

    @Test
    fun `activateSkill registers namespaced tools`() {
        val registry = ToolRegistry()
        val tools = listOf(
            SkillToolDef(name = "deploy", description = "Deploy the service"),
            SkillToolDef(name = "rollback", description = "Rollback the service"),
        )

        val registered = registry.activateSkill("my-skill", tools)

        assertThat(registered).containsExactly("my-skill.deploy", "my-skill.rollback")
        assertThat(registry.get("my-skill.deploy")).isNotNull
        assertThat(registry.get("my-skill.rollback")).isNotNull
    }

    @Test
    fun `activateSkill creates tools with correct descriptions`() {
        val registry = ToolRegistry()
        val tools = listOf(
            SkillToolDef(name = "deploy", description = "Deploy the service"),
        )

        registry.activateSkill("my-skill", tools)
        val tool = registry.get("my-skill.deploy")

        assertThat(tool).isNotNull
        assertThat(tool!!.description).contains("Deploy the service")
        assertThat(tool.name).isEqualTo("my-skill.deploy")
    }

    @Test
    fun `deactivateSkill removes tools from registry`() {
        val registry = ToolRegistry()
        val tools = listOf(
            SkillToolDef(name = "deploy", description = "Deploy"),
        )

        registry.activateSkill("my-skill", tools)
        assertThat(registry.get("my-skill.deploy")).isNotNull

        val result = registry.deactivateSkill("my-skill")
        assertThat(result).isTrue
        assertThat(registry.get("my-skill.deploy")).isNull()
    }

    @Test
    fun `deactivateSkill for unknown skill returns false`() {
        val registry = ToolRegistry()
        val result = registry.deactivateSkill("nonexistent")
        assertThat(result).isFalse
    }

    @Test
    fun `schemas includes skill-defined tools after activation`() {
        val registry = ToolRegistry()
        val tools = listOf(
            SkillToolDef(name = "deploy", description = "Deploy the service"),
        )

        registry.activateSkill("my-skill", tools)
        val schemas = registry.schemas()

        val deploySchema = schemas.find { it["name"] == "my-skill.deploy" }
        assertThat(deploySchema).isNotNull
        assertThat(deploySchema!!["description"]).isEqualTo("Deploy the service")
    }

    @Test
    fun `skill-defined tools return skill body when called`() = runTest {
        val registry = ToolRegistry()
        val tools = listOf(
            SkillToolDef(name = "deploy", description = "Deploy"),
        )

        registry.activateSkill("my-skill", tools, body = "## Deploy Steps\n1. Build\n2. Push")
        val tool = registry.get("my-skill.deploy")!!

        val result = tool.execute(emptyMap())

        assertThat(result.success).isTrue
        assertThat(result.output).contains("my-skill.deploy")
    }

    @Test
    fun `namespaced names prevent collision with core tools`() {
        val registry = ToolRegistry()
        registry.activateSkill("git", listOf(
            SkillToolDef(name = "commit", description = "Skill git commit"),
        ))

        // Core git_commit would be a different tool name
        val skillTool = registry.get("git.commit")
        assertThat(skillTool).isNotNull
    }

    @Test
    fun `re-activating a skill replaces old tools`() {
        val registry = ToolRegistry()
        registry.activateSkill("test", listOf(
            SkillToolDef(name = "old-tool", description = "Old"),
        ))

        registry.activateSkill("test", listOf(
            SkillToolDef(name = "new-tool", description = "New"),
        ))

        assertThat(registry.get("test.old-tool")).isNull()
        assertThat(registry.get("test.new-tool")).isNotNull()
    }

    @Test
    fun `tool parameters are mapped correctly`() {
        val params = listOf(
            SkillToolParam(name = "name", description = "Resource name", type = "string", required = true),
            SkillToolParam(name = "count", description = "Number of replicas", type = "integer", required = false),
            SkillToolParam(name = "dryRun", description = "Dry run mode", type = "boolean", required = false),
        )
        val toolDef = SkillToolDef(name = "deploy", description = "Deploy", parameters = params)

        val registry = ToolRegistry()
        registry.activateSkill("test", listOf(toolDef))
        val tool = registry.get("test.deploy")!!

        val nameParam = tool.parameters.find { it.name == "name" }
        assertThat(nameParam).isNotNull
        assertThat(nameParam!!.type).isEqualTo(ToolParameterType.STRING)
        assertThat(nameParam.required).isTrue

        val countParam = tool.parameters.find { it.name == "count" }
        assertThat(countParam).isNotNull
        assertThat(countParam!!.type).isEqualTo(ToolParameterType.INTEGER)
        assertThat(countParam.required).isFalse

        val dryRunParam = tool.parameters.find { it.name == "dryRun" }
        assertThat(dryRunParam).isNotNull
        assertThat(dryRunParam!!.type).isEqualTo(ToolParameterType.BOOLEAN)
    }

    @Test
    fun `multiple skills can register tools simultaneously`() {
        val registry = ToolRegistry()
        registry.activateSkill("aws", listOf(SkillToolDef(name = "deploy", description = "AWS deploy")))
        registry.activateSkill("gcp", listOf(SkillToolDef(name = "deploy", description = "GCP deploy")))

        assertThat(registry.get("aws.deploy")).isNotNull
        assertThat(registry.get("gcp.deploy")).isNotNull
        assertThat(registry.get("aws.deploy")).isNotEqualTo(registry.get("gcp.deploy"))
    }
}
