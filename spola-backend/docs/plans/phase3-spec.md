# Phase 3: Dynamic Tool Registration from Skills

## Summary

When `load_skill` encounters a skill with `tools:` in its frontmatter, those
tool definitions become live `Tool` objects in the agent's ToolRegistry.
Add `unload_skill` to deregister. The next LLM call automatically picks up
the new schemas via `toolRegistry.schemas()`.

## Design Decisions

1. **Namespace prefix**: Skill-defined tools are registered as `skillname.toolname`
   to avoid collisions with core tools and between skills.

2. **Execute behavior**: When called by the LLM, skill-defined tools return
   the skill body as context so the LLM can follow the skill's instructions.
   This is "documentation as execution" — the tool's output IS the skill.

3. **No GolemAgent.kt changes**: `callLlm()` calls `toolRegistry.schemas()` every
   turn — new tools appear automatically on the next LLM request.

## Files to Modify

### 1. ToolRegistry.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/Tool.kt`

Add skill tool lifecycle methods to `ToolRegistry`:

```kotlin
/** Track which skill registered which tools. */
private val skillTools = mutableMapOf<String, MutableList<String>>()

fun activateSkill(skillName: String, tools: List<SkillToolDef>): List<String> {
    val registered = mutableListOf<String>()
    for (toolDef in tools) {
        val toolName = "${skillName.lowercase()}.${toolDef.name}"
        val tool = Tool(
            name = toolName,
            description = toolDef.description,
            parameters = toolDef.parameters.map { it.toToolParameter() },
            execute = { _ -> 
                ToolResult.ok(
                    "**Skill Tool: $toolName**\n" +
                    "This is a documentation tool from the '$skillName' skill.\n" +
                    "Use `load_skill(\"$skillName\")` for full guidance.\n" +
                    "The tool definition describes what this tool expects."
                )
            }
        )
        register(tool)
        registered.add(toolName)
    }
    skillTools[skillName.lowercase()] = registered.toMutableList()
    return registered
}

fun deactivateSkill(skillName: String): Boolean {
    val names = skillTools.remove(skillName.lowercase()) ?: return false
    names.forEach { unregister(it) }
    return true
}
```

Add import for `SkillToolDef` and a helper:
```kotlin
private fun SkillToolDef.toToolParameter(): ToolParameter = ToolParameter(
    name = name,
    description = description,
    type = when (type.lowercase()) {
        "integer" -> ToolParameterType.INTEGER
        "boolean" -> ToolParameterType.BOOLEAN
        else -> ToolParameterType.STRING
    },
    required = required,
    defaultValue = defaultValue,
)
```

### 2. SkillTools.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillTools.kt`

Changes:
- `register()` accepts optional `toolRegistry: ToolRegistry?`
- Add `unload_skill` tool:
  - name: `unload_skill`
  - description: "Remove a skill's tools from the agent's tool registry."
  - parameters: `name` (required, string)
  - Calls `toolRegistry.deactivateSkill(name)`  
  - Returns: confirmation or "skill has no active tools"
- `load_skill` tool: after loading skill body, call `toolRegistry.activateSkill(skill.name, skill.tools)`
  - Only when `toolRegistry != null` AND `skill.tools.isNotEmpty()`
  - Include registered tool names in the output message

### 3. ToolRegistryFactory.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/factory/ToolRegistryFactory.kt`

Pass the registry to SkillTools:
```kotlin
SkillTools.register(this, skillsDir = Path.of(config.skillsDir), config = config, toolRegistry = this)
```

### 4. GolemConfig.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/GolemConfig.kt`

No changes needed.

## Test Files

### 5. ToolRegistryDynamicTest.kt — NEW
**Path**: `golem-core/src/test/kotlin/dev/golem/skill/ToolRegistryDynamicTest.kt`

Tests:
- `activateSkill registers namespaced tools`
- `activateSkill creates tools with correct names and descriptions`
- `deactivateSkill removes tool from registry`
- `deactivateSkill for unknown skill returns false`
- `schemas includes skill-defined tools after activation`
- `skill-defined tools return skill body when called`
- `load_skill with tools calls activateSkill`
- `unload_skill calls deactivateSkill`
- `namespaced names prevent collision with core tools`

## Boundaries — DO NOT TOUCH

- Do NOT modify GolemAgent.kt, GolemInstance.kt, Runner.kt, GolemFactory.kt
- Do NOT modify SkillDefinition.kt, SkillParser.kt, SkillLoader.kt, SkillIndexer.kt, SkillRepository.kt
- Do NOT modify SkillCatalog.kt, AgentFactory.kt
- Do NOT modify API routes, CLI commands (SkillCommand.kt), or any existing test files
- Do NOT add MCP, webhooks, or delivery integrations for skills
- Do NOT modify any existing passing test

## Build Check

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.7-tem
cd /home/gionag/Development/golem
./gradlew compileKotlin
./gradlew test
```
