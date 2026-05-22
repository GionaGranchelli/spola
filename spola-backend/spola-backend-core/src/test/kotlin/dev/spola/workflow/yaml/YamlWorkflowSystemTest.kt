package dev.spola.workflow.yaml

import dev.spola.SpolaConfig
import dev.spola.config.MetricsConfig
import dev.spola.factory.WorkflowFactory
import dev.spola.workflow.SpolaState
import dev.spola.workflow.NewWorkflowExecution
import dev.spola.workflow.SqliteWorkflowExecutionStore
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowExecutionStatus
import dev.spola.workflow.WorkflowTemplateRegistry
import dev.spola.workflow.registerBuiltInTemplates
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.orchestration.StopPolicy
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowGateRejectedException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class YamlWorkflowSystemTest {

    @Test
    fun `parse valid code-review yaml`() {
        val yaml = """
            |name: test-workflow
            |version: "1"
            |description: "A test workflow"
            |steps:
            |  - id: step-1
            |    type: ai
            |    goal: "Do something"
            |    persona: "You are a test assistant."
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def, "Valid YAML should parse successfully")
        assertEquals("test-workflow", def.name)
        assertEquals("1", def.version)
        assertEquals(1, def.steps.size)
        assertEquals("step-1", def.steps[0].id)
        assertEquals("ai", def.steps[0].type)
        assertEquals("Do something", def.steps[0].goal)
    }

    @Test
    fun `parse yaml with done conditions`() {
        val yaml = """
            |name: done-test
            |steps:
            |  - id: analyze
            |    type: ai
            |    goal: "Analyze code"
            |    done:
            |      - condition: output_has_content
            |      - condition: output_contains
            |        value: "completed"
            |done:
            |  - condition: all_steps_passed
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(1, def.steps.size)
        assertEquals(2, def.steps[0].done.size)
        assertEquals("output_has_content", def.steps[0].done[0].condition)
        assertEquals("output_contains", def.steps[0].done[1].condition)
        assertEquals("completed", def.steps[0].done[1].value)
        assertEquals(1, def.done.size)
    }

    @Test
    fun `parse yaml with params`() {
        val yaml = """
            |name: param-test
            |params:
            |  files:
            |    type: string
            |    description: File glob
            |    default: "**/*.kt"
            |  verbose:
            |    type: boolean
            |    default: false
            |steps:
            |  - id: scan
            |    type: ai
            |    goal: "Scan {{params.files}}"
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(2, def.params.size)
        assertTrue(def.params.containsKey("files"))
        assertEquals("**/*.kt", def.params["files"]?.default)
        assertEquals(false, def.params["verbose"]?.default)
    }

    @Test
    fun `reject yaml without name`() {
        val yaml = """
            |steps:
            |  - id: step-1
            |    type: ai
            |    goal: "test"
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertEquals(null, def, "YAML without 'name' should fail")
    }

    @Test
    fun `reject yaml without steps`() {
        val yaml = """
            |name: no-steps
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertEquals(null, def, "YAML without 'steps' should fail")
    }

    @Test
    fun `resolve parameter templates`() {
        val yaml = """
            |name: template-test
            |params:
            |  target:
            |    type: string
            |    default: "src/"
            |steps:
            |  - id: scan
            |    type: ai
            |    goal: "Scan {{params.target}} for goal {{state.goal}}"
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(
            definition = def,
            runtimeParams = emptyMap(),
            goal = "find bugs",
        )

        assertEquals("template-test", resolved.name)
        assertEquals(1, resolved.steps.size)
        assertEquals("Scan src/ for goal find bugs", resolved.steps[0].goal)
    }

    @Test
    fun `resolve parameter templates with overrides`() {
        val yaml = """
            |name: override-test
            |params:
            |  files:
            |    type: string
            |    default: "*.java"
            |steps:
            |  - id: check
            |    type: ai
            |    goal: "Check {{params.files}}"
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(
            definition = def,
            runtimeParams = mapOf("files" to "*.kt"),
            goal = "",
        )

        assertEquals("Check *.kt", resolved.steps[0].goal)
    }

    @Test
    fun `done condition evaluator - output has content`() {
        val state = SpolaState(goal = "test", result = "some output")
        val result = DoneConditionEvaluator.evaluate(
            DoneCondition("output_has_content"),
            state,
            emptyMap(),
        )
        assertTrue(result, "State with non-null result should pass output_has_content")
    }

    @Test
    fun `done condition evaluator - output contains regex`() {
        val state = SpolaState(goal = "test", result = "Found CRITICAL vulnerability")
        val result = DoneConditionEvaluator.evaluate(
            DoneCondition("output_contains", "CRITICAL|HIGH"),
            state,
            emptyMap(),
        )
        assertTrue(result, "Output containing 'CRITICAL' should match regex")
    }

    @Test
    fun `done condition evaluator - no critical blockers`() {
        val state = SpolaState(goal = "test", result = "All checks passed, no issues found")
        val result = DoneConditionEvaluator.evaluate(
            DoneCondition("no_critical_blockers"),
            state,
            emptyMap(),
        )
        assertTrue(result, "Output without CRITICAL markers should pass")
    }

    @Test
    fun `done condition evaluator - fails on critical`() {
        val state = SpolaState(goal = "test", result = "CRITICAL: SQL injection vulnerability found")
        val result = DoneConditionEvaluator.evaluate(
            DoneCondition("no_critical_blockers"),
            state,
            emptyMap(),
        )
        assertTrue(!result, "Output with CRITICAL should fail no_critical_blockers")
    }

    @Test
    fun `load workflow from directory`(@TempDir tempDir: Path) {
        val yaml = """
            |name: dir-test
            |steps:
            |  - id: step-1
            |    type: ai
            |    goal: "Test"
        """.trimMargin()
        Files.writeString(tempDir.resolve("my-workflow.yaml"), yaml)

        val files = YamlWorkflowLoader.discoverWorkflowFiles(tempDir)
        assertEquals(1, files.size)
        assertEquals("my-workflow.yaml", files[0].fileName.toString())
    }

    @Test
    fun `compile workflow produces valid tramai workflow`() {
        val yaml = """
            |name: compile-test
            |steps:
            |  - id: analyze
            |    type: ai
            |    goal: "Analyze the project structure"
            |    persona: "You are a test assistant."
            |    done:
            |      - condition: output_has_content
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "test goal")

        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        val workflow = YamlWorkflowCompiler.compile(
            resolved = resolved,
            config = config,
            goal = "test goal",
            registry = WorkflowTemplateRegistry(),
        )

        assertNotNull(workflow)
        assertEquals("compile-test", workflow.name)
    }

    @Test
    fun `yaml workflow registered alongside built-ins`() = withTempYamlWorkflow { yamlPath ->
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        val registry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
            YamlWorkflowLoader.loadAndRegister(this, config, yamlPath.parent)
        }

        // Only YAML workflows are registered (built-in templates removed)
        assertNotNull(registry.resolve("custom-audit"))
    }

    @Test
    fun `parse yaml with depends_on maps to dependsOn`() {
        val yaml = """
            |name: depends-test
            |steps:
            |  - id: step-a
            |    type: ai
            |    goal: "First step"
            |  - id: step-b
            |    type: ai
            |    goal: "Second step"
            |    depends_on: [step-a]
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def, "YAML should parse successfully")
        assertEquals(2, def.steps.size)

        val stepB = def.steps.find { it.id == "step-b" }
        assertNotNull(stepB, "step-b should exist")

        assertNotNull(stepB.dependsOn, "depends_on should be parsed into dependsOn")
        assertEquals(listOf("step-a"), stepB.dependsOn, "depends_on value should be preserved")
    }

    @Test
    fun `parse yaml with depends_on empty list`() {
        val yaml = """
            |name: depends-empty-list
            |steps:
            |  - id: step-a
            |    type: ai
            |    goal: "First step"
            |    depends_on: []
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(emptyList(), def.steps.single().dependsOn)
    }

    @Test
    fun `parse yaml with depends_on null`() {
        val yaml = """
            |name: depends-null
            |steps:
            |  - id: step-a
            |    type: ai
            |    goal: "First step"
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertNull(def.steps.single().dependsOn)
    }

    @Test
    fun `parse yaml with camelCase dependsOn`() {
        val yaml = """
            |name: depends-camel
            |steps:
            |  - id: step-a
            |    type: ai
            |    goal: "First step"
            |  - id: step-b
            |    type: ai
            |    goal: "Second step"
            |    dependsOn: [step-a]
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(listOf("step-a"), def.steps.last().dependsOn)
    }

    @Test
    fun `parse yaml with on_error enum values`() {
        val yaml = """
            |name: onerror-enum-test
            |steps:
            |  - id: step-a
            |    type: ai
            |    goal: "test"
            |    on_error: continue
            |  - id: step-b
            |    type: ai
            |    goal: "test"
            |    on_error: fail
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml))
        assertEquals(OnError.CONTINUE, def.steps[0].onError)
        assertEquals(OnError.FAIL, def.steps[1].onError)
    }

    @Test
    fun `parse yaml with retry_count`() {
        val yaml = """
            |name: retry-count-test
            |steps:
            |  - id: step-a
            |    type: ai
            |    goal: "First step"
            |    retry_count: 3
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(3, def.steps.single().retryCount)
    }

    @Test
    fun `parse yaml with workflow_ref`() {
        val yaml = """
            |name: workflow-ref-test
            |steps:
            |  - id: step-a
            |    type: composite
            |    goal: "Run referenced workflow"
            |    workflow_ref: my-workflow
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals("my-workflow", def.steps.single().workflowRef)
    }

    @Test
    fun `parse yaml with shell step`() {
        val yaml = """
            |name: shell-parse
            |steps:
            |  - id: shell-step
            |    type: shell
            |    command: "echo hello"
            |    timeout: 30
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals("shell", def.steps.single().type)
        assertEquals("echo hello", def.steps.single().command)
        assertEquals(30, def.steps.single().timeout)
    }

    @Test
    fun `parse yaml with local step`() {
        val yaml = """
            |name: local-parse
            |steps:
            |  - id: local-step
            |    type: local
            |    command: "ls -la"
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals("local", def.steps.single().type)
        assertEquals("ls -la", def.steps.single().command)
    }

    @Test
    fun `parse yaml with shell timeout default`() {
        val yaml = """
            |name: shell-default-timeout
            |steps:
            |  - id: shell-step
            |    type: shell
            |    command: "echo hello"
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(60, def.steps.single().timeout)
    }

    @Test
    fun `parse yaml with max_output_bytes`() {
        val yaml = """
            |name: maxbytes-test
            |steps:
            |  - id: step-a
            |    type: shell
            |    command: "echo test"
            |    max_output_bytes: 5242880
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml))
        assertEquals(5242880L, def.steps.single().maxOutputBytes)
    }

    @Test
    fun `parse yaml with env vars`() {
        val yaml = """
            |name: env-test
            |steps:
            |  - id: step-a
            |    type: shell
            |    command: "echo test"
            |    env:
            |      MY_VAR: hello
            |      ANOTHER: world
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml))
        assertEquals("hello", def.steps.single().env?.get("MY_VAR"))
        assertEquals("world", def.steps.single().env?.get("ANOTHER"))
    }

    @Test
    fun `parse yaml with shell step and depends_on`() {
        val yaml = """
            |name: shell-depends
            |steps:
            |  - id: first
            |    type: ai
            |    goal: "first"
            |  - id: second
            |    type: shell
            |    command: "echo after"
            |    depends_on: [first]
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(listOf("first"), def.steps.last().dependsOn)
    }

    @Test
    fun `parse yaml with both depends_on and dependsOn`() {
        val yaml = """
            |name: depends-conflict
            |steps:
            |  - id: step-a
            |    type: ai
            |    goal: "First step"
            |  - id: step-b
            |    type: ai
            |    goal: "Second step"
            |    depends_on: [step-a]
            |    dependsOn: [step-c]
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(listOf("step-a"), def.steps.last().dependsOn)
    }

    @Test
    fun `reject yaml with duplicate step ids`() {
        val yaml = """
            |name: duplicate-steps
            |steps:
            |  - id: dup
            |    type: ai
            |    goal: "First step"
            |  - id: dup
            |    type: ai
            |    goal: "Second step"
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)

        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "test goal")
        assertThrows(IllegalArgumentException::class.java) {
            YamlWorkflowDagSorter.sort(resolved.steps)
        }
    }

    @Test
    fun `compile workflow with topological sort`() {
        val yaml = """
            |name: compile-topological
            |steps:
            |  - id: finalize
            |    type: ai
            |    goal: "Finalize"
            |    depends_on: [analyze, summarize]
            |  - id: summarize
            |    type: ai
            |    goal: "Summarize"
            |    depends_on: [analyze]
            |  - id: analyze
            |    type: ai
            |    goal: "Analyze"
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "test goal")
        val sorted = YamlWorkflowDagSorter.sort(resolved.steps).map { it.id }

        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        val workflow = YamlWorkflowCompiler.compile(
            resolved = resolved,
            config = config,
            goal = "test goal",
            registry = WorkflowTemplateRegistry(),
        )

        assertEquals(listOf("analyze", "summarize", "finalize"), sorted)
        assertEquals("compile-topological", workflow.name)
    }

    @Test
    fun `export YAML workflow produces valid YAML`() = withTempYamlWorkflow { yamlPath ->
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        val registry = WorkflowTemplateRegistry().apply {
            YamlWorkflowLoader.loadAndRegister(this, config, yamlPath.parent)
        }
        val yaml = WorkflowExport.exportTemplate(registry, "custom-audit")
        assertNotNull(yaml, "Export should produce YAML for custom-audit")
        assertTrue(yaml.contains("name: custom-audit"), "YAML should contain workflow name")

        // Verify the exported YAML can be re-parsed
        val parsed = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(parsed, "Exported YAML should be valid")
        assertEquals("custom-audit", parsed.name)
    }

    @Test
    fun `unknown workflow export returns null`() {
        val registry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
        }
        val yaml = WorkflowExport.exportTemplate(registry, "non-existent-workflow")
        assertEquals(null, yaml, "Export of unknown workflow should return null")
    }

    // ── YamlWorkflowDagSorter tests ──────────────────────────────

    @Test
    fun `sort linear chain preserves order`() {
        val steps = listOf(
            step("c", deps = listOf("b")),
            step("b", deps = listOf("a")),
            step("a"),
        )
        val sorted = YamlWorkflowDagSorter.sort(steps)
        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    fun `sort fan-out preserves execution order`() {
        val steps = listOf(
            step("d", deps = listOf("b", "c")),
            step("c", deps = listOf("a")),
            step("b", deps = listOf("a")),
            step("a"),
        )
        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }
        // a must be first, b and c must come before d
        assertEquals("a", sorted[0])
        assertTrue(sorted.indexOf("b") < sorted.indexOf("d"))
        assertTrue(sorted.indexOf("c") < sorted.indexOf("d"))
    }

    @Test
    fun `sort fan-in merges correctly`() {
        val steps = listOf(
            step("c", deps = listOf("a", "b")),
            step("a"),
            step("b"),
        )
        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }
        // A and B before C
        assertTrue(sorted.indexOf("a") < sorted.indexOf("c"))
        assertTrue(sorted.indexOf("b") < sorted.indexOf("c"))
    }

    @Test
    fun `sort stable for equal-level steps`() {
        val steps = listOf(
            step("x"),
            step("a"),
            step("b"),
            step("c"),
        )
        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }
        // Root steps preserve declaration order
        assertEquals(listOf("x", "a", "b", "c"), sorted)
    }

    @Test
    fun `sort detects cycle`() {
        val steps = listOf(
            step("a", deps = listOf("b")),
            step("b", deps = listOf("c")),
            step("c", deps = listOf("a")),
        )
        assertThrows(IllegalStateException::class.java) {
            YamlWorkflowDagSorter.sort(steps)
        }
    }

    @Test
    fun `sort detects missing dependency`() {
        val steps = listOf(
            step("a", deps = listOf("nonexistent")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            YamlWorkflowDagSorter.sort(steps)
        }
    }

    @Test
    fun `sort self-reference detected as cycle`() {
        val steps = listOf(
            step("a", deps = listOf("a")),
        )
        assertThrows(IllegalStateException::class.java) {
            YamlWorkflowDagSorter.sort(steps)
        }
    }

    @Test
    fun `sort duplicate step ids`() {
        val steps = listOf(
            ResolvedStep(id = "dup", type = "ai", goal = "first", persona = null, agents = null, dependsOn = null,
                command = null, timeout = 60, prompt = null, expression = null, workflowRef = null, invoke = null,
                done = emptyList(), onError = OnError.FAIL, retryCount = 0, maxOutputBytes = 10 * 1024 * 1024, env = null),
            ResolvedStep(id = "dup", type = "ai", goal = "second", persona = null, agents = null, dependsOn = null,
                command = null, timeout = 60, prompt = null, expression = null, workflowRef = null, invoke = null,
                done = emptyList(), onError = OnError.FAIL, retryCount = 0, maxOutputBytes = 10 * 1024 * 1024, env = null),
        )
        assertThrows(IllegalArgumentException::class.java) {
            YamlWorkflowDagSorter.sort(steps)
        }
    }

    @Test
    fun `sort empty list returns empty`() {
        val sorted = YamlWorkflowDagSorter.sort(emptyList())
        assertTrue(sorted.isEmpty())
    }

    @Test
    fun `sort single step no deps`() {
        val steps = listOf(step("a"))
        val sorted = YamlWorkflowDagSorter.sort(steps)
        assertEquals(1, sorted.size)
        assertEquals("a", sorted[0].id)
    }

    @Test
    fun `sort extremely deep chain`() {
        val stepCount = 200
        val steps = (0 until stepCount).map { index ->
            val id = "step-$index"
            val deps = if (index == 0) null else listOf("step-${index - 1}")
            step(id, deps = deps)
        }.asReversed()

        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }

        assertEquals((0 until stepCount).map { "step-$it" }, sorted)
    }

    @Test
    fun `sort diamond shaped DAG`() {
        val steps = listOf(
            step("d", deps = listOf("b", "c")),
            step("c", deps = listOf("a")),
            step("b", deps = listOf("a")),
            step("a"),
        )

        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }

        assertEquals("a", sorted.first())
        assertEquals("d", sorted.last())
        assertTrue(sorted.indexOf("b") in 1..2)
        assertTrue(sorted.indexOf("c") in 1..2)
    }

    @Test
    fun `sort disconnected DAGs`() {
        val steps = listOf(
            step("b", deps = listOf("a")),
            step("d", deps = listOf("c")),
            step("a"),
            step("c"),
        )

        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }

        assertTrue(sorted.indexOf("a") < sorted.indexOf("b"))
        assertTrue(sorted.indexOf("c") < sorted.indexOf("d"))
    }

    @Test
    fun `sort with all root steps`() {
        val steps = listOf(step("a"), step("b"), step("c"), step("d"), step("e"))
        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }
        assertEquals(listOf("a", "b", "c", "d", "e"), sorted)
    }

    @Test
    fun `sort single step with self-referencing non-existent dep`() {
        val steps = listOf(step("x", deps = listOf("nonexistent")))
        assertThrows(IllegalArgumentException::class.java) {
            YamlWorkflowDagSorter.sort(steps)
        }
    }

    @Test
    fun `sort complex multi-level DAG`() {
        val steps = listOf(
            step("d", deps = listOf("c", "e")),
            step("f", deps = listOf("a")),
            step("c", deps = listOf("b")),
            step("e", deps = listOf("a")),
            step("b", deps = listOf("a")),
            step("a"),
        )

        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }

        assertEquals("a", sorted.first())
        assertTrue(sorted.indexOf("b") < sorted.indexOf("c"))
        assertTrue(sorted.indexOf("c") < sorted.indexOf("d"))
        assertTrue(sorted.indexOf("e") < sorted.indexOf("d"))
        assertTrue(sorted.indexOf("a") < sorted.indexOf("f"))
    }

    @Test
    fun `sort with empty depends_on list`() {
        val steps = listOf(step("a", deps = emptyList()), step("b"))
        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }
        assertEquals(listOf("a", "b"), sorted)
    }

    @Test
    fun `sort with depends_on null`() {
        val steps = listOf(step("a", deps = null), step("b", deps = listOf("a")))
        val sorted = YamlWorkflowDagSorter.sort(steps).map { it.id }
        assertEquals(listOf("a", "b"), sorted)
    }

    @Test
    fun `resolve params with missing required param`() {
        val yaml = """
            |name: required-param-test
            |params:
            |  target:
            |    type: string
            |    required: true
            |steps:
            |  - id: scan
            |    type: ai
            |    goal: "Scan {{params.target}}"
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "")

        assertEquals("", resolved.params["target"])
        assertEquals("Scan ", resolved.steps.single().goal)
    }

    @Test
    fun `resolve params with empty string default`() {
        val yaml = """
            |name: empty-default-test
            |params:
            |  suffix:
            |    type: string
            |    default: ""
            |steps:
            |  - id: scan
            |    type: ai
            |    goal: "Value={{params.suffix}}"
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "")

        assertEquals("", resolved.params["suffix"])
        assertEquals("Value=", resolved.steps.single().goal)
    }

    @Test
    fun `resolve params with boolean default false`() {
        val yaml = """
            |name: boolean-default-test
            |params:
            |  verbose:
            |    type: boolean
            |    default: false
            |steps:
            |  - id: scan
            |    type: ai
            |    goal: "Verbose={{params.verbose}}"
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "")

        assertEquals(false, resolved.params["verbose"])
        assertEquals("Verbose=false", resolved.steps.single().goal)
    }

    @Test
    fun `resolve params with integer default`() {
        val yaml = """
            |name: integer-default-test
            |params:
            |  count:
            |    type: number
            |    default: 5
            |steps:
            |  - id: scan
            |    type: ai
            |    goal: "Count={{params.count}}"
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "")

        assertEquals(5, resolved.params["count"])
        assertEquals("Count=5", resolved.steps.single().goal)
    }

    @Test
    fun `resolve templates with escaped braces`() {
        val yaml = """
            |name: escaped-braces-test
            |steps:
            |  - id: scan
            |    type: ai
            |    goal: "Keep {{not-a-param-should-stay}} and resolve {{state.goal}}"
        """.trimMargin()

        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "real goal")

        assertEquals(
            "Keep {{not-a-param-should-stay}} and resolve real goal",
            resolved.steps.single().goal,
        )
    }

    @Test
    fun `compile shell step executes command`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: shell-exec
                |steps:
                |  - id: shell-step
                |    type: shell
                |    command: "echo hello"
            """.trimMargin()
        )

        assertTrue(result.contains("hello"), "Output: $result")
    }

    @Test
    fun `compile shell step with failing command`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            runYamlWorkflow(
                """
                    |name: shell-fail
                    |steps:
                    |  - id: shell-step
                    |    type: shell
                    |    command: "ls /definitely_missing_spola_path"
                """.trimMargin()
            )
        }

        assertTrue(error.message?.contains("definitely_missing_spola_path") == true)
    }

    @Test
    fun `compile shell step with on_error continue`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: shell-continue
                |steps:
                |  - id: shell-step
                |    type: shell
                |    command: "ls /definitely_missing_spola_path"
                |    on_error: continue
            """.trimMargin()
        )

        assertTrue(result.contains("definitely_missing_spola_path"), "Output: $result")
    }

    @Test
    fun `compile shell step with timeout`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            runYamlWorkflow(
                """
                    |name: shell-timeout
                    |steps:
                    |  - id: shell-step
                    |    type: shell
                    |    command: "sleep 2"
                    |    timeout: 1
                """.trimMargin()
            )
        }

        assertTrue(error.message?.contains("timed out") == true, "Error: ${error.message}")
    }

    @Test
    fun `compile shell step with template resolution`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: shell-template
                |steps:
                |  - id: previous
                |    type: local
                |    command: "printf 'hello-template'"
                |  - id: next
                |    type: shell
                |    command: "printf '{{step.previous.output}} world'"
                |    depends_on: [previous]
            """.trimMargin()
        )

        assertEquals("hello-template world", result)
    }

    @Test
    fun `shell step with env vars executes with custom environment`() = runTest {
        val yaml = """
            |name: env-exec
            |steps:
            |  - id: test-env
            |    type: shell
            |    command: "echo ${'$'}MY_VAR"
            |    env:
            |      MY_VAR: custom-value
        """.trimMargin()
        val result = runYamlWorkflow(yaml, "test")
        assertTrue(result.contains("custom-value"), "Env var should be passed to shell: $result")
    }

    @Test
    fun `compile local step executes command`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: local-exec
                |steps:
                |  - id: local-step
                |    type: local
                |    command: "echo local-hello"
            """.trimMargin()
        )

        assertTrue(result.contains("local-hello"), "Output: $result")
    }

    @Test
    fun `shell step with retry succeeds eventually`() {
        val state = SpolaState(
            goal = "test",
            intermediateResults = mapOf("path" to "/definitely_missing_before_retry"),
        )
        val tempFile = Files.createTempFile("spola-shell-retry", ".txt")
        try {
            Files.deleteIfExists(tempFile)
            val output = YamlWorkflowStepRunner.execute(
                command = "if [ -f '${tempFile}' ]; then cat '${tempFile}'; else printf 'ok' > '${tempFile}'; exit 1; fi",
                state = state,
                retryCount = 1,
            )
            assertEquals("ok", output)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `shell step with empty command`() {
        val error = assertFailsWith<IllegalStateException> {
            YamlWorkflowStepRunner.execute(
                command = "   ",
                state = SpolaState(goal = "test"),
            )
        }

        assertTrue(error.message?.contains("empty") == true, "Error: ${error.message}")
    }

    @Test
    fun `shell step with very long output`() {
        val output = YamlWorkflowStepRunner.execute(
            command = "i=0; while [ \$i -lt 6000 ]; do printf x; i=\$((i+1)); done; printf '\\n'",
            state = SpolaState(goal = "test"),
        )

        assertEquals(6001, output.length)
        assertTrue(output.startsWith("xxxx"))
    }

    @Test
    fun `shell step command not found`() {
        val error = assertFailsWith<IllegalStateException> {
            YamlWorkflowStepRunner.execute(
                command = "spola_command_that_should_not_exist_12345",
                state = SpolaState(goal = "test"),
            )
        }

        assertTrue(error.message?.contains("not found") == true || error.message?.contains("not exist") == true)
    }

    @Test
    fun `compile shell step with done condition`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: shell-done
                |steps:
                |  - id: shell-step
                |    type: shell
                |    command: "printf completed"
                |    done:
                |      - condition: output_contains
                |        value: "completed"
            """.trimMargin()
        )

        assertEquals("completed", result)
    }

    @Test
    fun `compile shell step with done conditions and on_error`() = runTest {
        val error = assertFailsWith<WorkflowGateRejectedException> {
            runYamlWorkflow(
                """
                    |name: shell-done-continue
                    |steps:
                    |  - id: shell-step
                    |    type: shell
                    |    command: "ls /definitely_missing_spola_path"
                    |    on_error: continue
                    |    done:
                    |      - condition: output_contains
                    |        value: "completed"
                """.trimMargin()
            )
        }

        assertTrue(error.message?.contains("Done conditions not met") == true, "Error: ${error.message}")
    }

    @Test
    fun `compile multi-step workflow with shell and local mixed`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: shell-local-mixed
                |steps:
                |  - id: first
                |    type: shell
                |    command: "printf first"
                |  - id: second
                |    type: local
                |    command: "printf '{{step.first.output}} second'"
                |    depends_on: [first]
            """.trimMargin()
        )

        assertEquals("first second", result)
    }

    @Test
    fun `compile workflow with multiple shell steps and dependency chain`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: shell-chain
                |steps:
                |  - id: one
                |    type: shell
                |    command: "printf one"
                |  - id: two
                |    type: shell
                |    command: "printf '{{step.one.output}}-two'"
                |    depends_on: [one]
                |  - id: three
                |    type: local
                |    command: "printf '{{step.two.output}}-three'"
                |    depends_on: [two]
            """.trimMargin()
        )

        assertEquals("one-two-three", result)
    }

    @Test
    fun `shell step maxOutputBytes truncates output at runtime`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: maxbytes-runtime
                |steps:
                |  - id: big-output
                |    type: shell
                |    command: "printf 'hello-this-is-a-long-output'"
                |    max_output_bytes: 5
            """.trimMargin()
        )

        assertEquals(5, result.length, "Output should be truncated to 5 bytes: $result")
        assertEquals("hello", result)
    }

    @Test
    fun `shell step with explicit on_error fail throws on non-zero exit`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            runYamlWorkflow(
                """
                    |name: onerror-fail-explicit
                    |steps:
                    |  - id: fail-step
                    |    type: shell
                    |    command: "exit 1"
                    |    on_error: fail
                """.trimMargin()
            )
        }

        assertTrue(error.message?.contains("exit 1") == true, "Error: ${error.message}")
    }

    @Test
    fun `shell step with on_error continue still runs subsequent steps`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: onerror-continue-chain
                |steps:
                |  - id: fail-first
                |    type: shell
                |    command: "exit 1"
                |    on_error: continue
                |  - id: after-fail
                |    type: shell
                |    command: "echo 'still running'"
                |    depends_on: [fail-first]
            """.trimMargin()
        )

        assertTrue(result.contains("still running"), "Subsequent step should execute: $result")
    }

    @Test
    fun `shell step retry via full compiler pipeline`() = runTest {
        val tempFile = Files.createTempFile("spola-retry-pipeline-", ".txt")
        try {
            Files.deleteIfExists(tempFile)
            val yaml = """
                |name: retry-pipeline
                |steps:
                |  - id: retry-step
                |    type: shell
                |    command: "if [ -f '${tempFile}' ]; then cat '${tempFile}'; else printf success > '${tempFile}'; exit 1; fi"
                |    retry_count: 1
            """.trimMargin()
            val result = runYamlWorkflow(yaml, "test")
            assertEquals("success", result.trim())
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `composite step executes sub-workflow and captures output`() = runTest {
        val subYaml = """
            |name: sub-workflow
            |version: "1"
            |steps:
            |  - id: sub-step
            |    type: shell
            |    command: "echo hello from sub"
        """.trimMargin()

        val parentYaml = """
            |name: parent-workflow
            |steps:
            |  - id: main
            |    type: composite
            |    workflow_ref: sub-workflow
        """.trimMargin()

        val subDef = requireNotNull(YamlWorkflowParser.parseContent(subYaml))
        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        registry.register(object : dev.spola.workflow.WorkflowTemplate {
            override val name = "sub-workflow"
            override val version = "1"

            override fun build(
                config: SpolaConfig,
                goal: String,
                parametersJson: String,
            ): Workflow<SpolaState, String> {
                val resolved = WorkflowParameterResolver.resolve(subDef, emptyMap(), goal)
                return YamlWorkflowCompiler.compile(resolved, config, goal, registry)
            }
        })

        val parentDef = requireNotNull(YamlWorkflowParser.parseContent(parentYaml))
        val parentResolved = WorkflowParameterResolver.resolve(parentDef, emptyMap(), "test")
        val parentWorkflow = YamlWorkflowCompiler.compile(parentResolved, config, "test", registry)
        val result = WorkflowFactory.runWorkflow(parentWorkflow, SpolaState(goal = "test"), config = config)

        assertTrue(result.contains("hello from sub"), "Composite step should execute sub-workflow: $result")
    }

    @Test
    fun `composite step missing workflow_ref throws`() {
        val yaml = """
            |name: bad-composite
            |steps:
            |  - id: comp
            |    type: composite
        """.trimMargin()
        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml))
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "test")
        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))

        assertFailsWith<IllegalArgumentException> {
            YamlWorkflowCompiler.compile(resolved, config, "test", registry)
        }
    }

    @Test
    fun `composite step refers to non-existent workflow throws`() {
        val yaml = """
            |name: bad-composite
            |steps:
            |  - id: comp
            |    type: composite
            |    workflow_ref: nonexistent-workflow
        """.trimMargin()
        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml))
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "test")
        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))

        assertFailsWith<IllegalStateException> {
            YamlWorkflowCompiler.compile(resolved, config, "test", registry)
        }
    }

    @Test
    fun `composite step output available via template resolution`() = runTest {
        val subYaml = """
            |name: analyze-sub-workflow
            |version: "1"
            |steps:
            |  - id: sub-step
            |    type: local
            |    command: "printf analyzed-data"
        """.trimMargin()

        val parentYaml = """
            |name: composite-output-parent
            |steps:
            |  - id: main
            |    type: composite
            |    workflow_ref: analyze-sub-workflow
            |  - id: summarize
            |    type: local
            |    command: "printf '{{step.main.output}} processed'"
            |    depends_on: [main]
        """.trimMargin()

        val subDef = requireNotNull(YamlWorkflowParser.parseContent(subYaml))
        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        registry.register(object : dev.spola.workflow.WorkflowTemplate {
            override val name = "analyze-sub-workflow"
            override val version = "1"

            override fun build(
                config: SpolaConfig,
                goal: String,
                parametersJson: String,
            ): Workflow<SpolaState, String> {
                val resolved = WorkflowParameterResolver.resolve(subDef, emptyMap(), goal)
                return YamlWorkflowCompiler.compile(resolved, config, goal, registry)
            }
        })

        val parentDef = requireNotNull(YamlWorkflowParser.parseContent(parentYaml))
        val parentResolved = WorkflowParameterResolver.resolve(parentDef, emptyMap(), "test")
        val parentWorkflow = YamlWorkflowCompiler.compile(parentResolved, config, "test", registry)
        val result = WorkflowFactory.runWorkflow(parentWorkflow, SpolaState(goal = "test"), config = config)

        assertTrue(result.contains("analyzed-data processed"), "Composite output should resolve in parent: $result")
    }

    @Test
    fun `composite step self-reference detected as cycle`() {
        val yaml = """
            |name: self-cycle
            |steps:
            |  - id: comp
            |    type: composite
            |    workflow_ref: self-cycle
        """.trimMargin()
        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml))
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), "test")
        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        registry.register(YamlWorkflowTemplate(def, config, registry))

        val error = assertFailsWith<IllegalStateException> {
            YamlWorkflowCompiler.compile(resolved, config, "test", registry)
        }

        assertTrue(error.message?.contains("Chain: self-cycle -> self-cycle") == true, "Error: ${error.message}")
    }

    @Test
    fun `composite step chain cycle detected`() {
        val aYaml = """
            |name: cycle-a
            |steps:
            |  - id: comp
            |    type: composite
            |    workflow_ref: cycle-b
        """.trimMargin()
        val bYaml = """
            |name: cycle-b
            |steps:
            |  - id: comp
            |    type: composite
            |    workflow_ref: cycle-a
        """.trimMargin()

        val aDef = requireNotNull(YamlWorkflowParser.parseContent(aYaml))
        val bDef = requireNotNull(YamlWorkflowParser.parseContent(bYaml))

        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        registry.register(YamlWorkflowTemplate(aDef, config, registry))
        registry.register(YamlWorkflowTemplate(bDef, config, registry))

        val resolvedA = WorkflowParameterResolver.resolve(aDef, emptyMap(), "test")

        val error = assertFailsWith<IllegalStateException> {
            YamlWorkflowCompiler.compile(resolvedA, config, "test", registry)
        }

        assertTrue(error.message?.contains("Chain: cycle-a -> cycle-b -> cycle-a") == true, "Error: ${error.message}")
    }

    @Test
    fun `composite step three-way cycle detected`() {
        val aYaml = """
            |name: three-a
            |steps:
            |  - id: comp
            |    type: composite
            |    workflow_ref: three-b
        """.trimMargin()
        val bYaml = """
            |name: three-b
            |steps:
            |  - id: comp
            |    type: composite
            |    workflow_ref: three-c
        """.trimMargin()
        val cYaml = """
            |name: three-c
            |steps:
            |  - id: comp
            |    type: composite
            |    workflow_ref: three-a
        """.trimMargin()

        val aDef = requireNotNull(YamlWorkflowParser.parseContent(aYaml))
        val bDef = requireNotNull(YamlWorkflowParser.parseContent(bYaml))
        val cDef = requireNotNull(YamlWorkflowParser.parseContent(cYaml))

        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        registry.register(YamlWorkflowTemplate(aDef, config, registry))
        registry.register(YamlWorkflowTemplate(bDef, config, registry))
        registry.register(YamlWorkflowTemplate(cDef, config, registry))

        val resolvedA = WorkflowParameterResolver.resolve(aDef, emptyMap(), "test")

        val error = assertFailsWith<IllegalStateException> {
            YamlWorkflowCompiler.compile(resolvedA, config, "test", registry)
        }

        assertTrue(
            error.message?.contains("Chain: three-a -> three-b -> three-c -> three-a") == true,
            "Error: ${error.message}"
        )
    }

    @Test
    fun `composite step no cycle with deep chain`() = runTest {
        val leafYaml = """
            |name: leaf-workflow
            |steps:
            |  - id: leaf-step
            |    type: shell
            |    command: "echo leaf result"
        """.trimMargin()
        val midYaml = """
            |name: mid-workflow
            |steps:
            |  - id: mid-step
            |    type: composite
            |    workflow_ref: leaf-workflow
        """.trimMargin()
        val rootYaml = """
            |name: root-workflow
            |steps:
            |  - id: root-step
            |    type: composite
            |    workflow_ref: mid-workflow
        """.trimMargin()

        val leafDef = requireNotNull(YamlWorkflowParser.parseContent(leafYaml))
        val midDef = requireNotNull(YamlWorkflowParser.parseContent(midYaml))
        val rootDef = requireNotNull(YamlWorkflowParser.parseContent(rootYaml))

        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        registry.register(YamlWorkflowTemplate(leafDef, config, registry))
        registry.register(YamlWorkflowTemplate(midDef, config, registry))
        registry.register(YamlWorkflowTemplate(rootDef, config, registry))

        val resolvedRoot = WorkflowParameterResolver.resolve(rootDef, emptyMap(), "test")
        val workflow = YamlWorkflowCompiler.compile(resolvedRoot, config, "test", registry)
        val result = WorkflowFactory.runWorkflow(
            workflow = workflow,
            initialState = SpolaState(goal = "test"),
            config = config,
        )

        assertTrue(result.contains("leaf result"), "Deep non-cyclic chain should execute: $result")
    }

    @Test
    fun `composite step diamond dependency`() = runTest {
        val leafYaml = """
            |name: leaf-workflow
            |steps:
            |  - id: leaf-step
            |    type: shell
            |    command: "echo diamond leaf"
        """.trimMargin()
        val bYaml = """
            |name: b-workflow
            |steps:
            |  - id: b-step
            |    type: composite
            |    workflow_ref: leaf-workflow
        """.trimMargin()
        val cYaml = """
            |name: c-workflow
            |steps:
            |  - id: c-step
            |    type: composite
            |    workflow_ref: leaf-workflow
        """.trimMargin()
        val aYaml = """
            |name: a-workflow
            |steps:
            |  - id: to-b
            |    type: composite
            |    workflow_ref: b-workflow
            |  - id: to-c
            |    type: composite
            |    workflow_ref: c-workflow
        """.trimMargin()

        val leafDef = requireNotNull(YamlWorkflowParser.parseContent(leafYaml))
        val bDef = requireNotNull(YamlWorkflowParser.parseContent(bYaml))
        val cDef = requireNotNull(YamlWorkflowParser.parseContent(cYaml))
        val aDef = requireNotNull(YamlWorkflowParser.parseContent(aYaml))

        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        registry.register(YamlWorkflowTemplate(leafDef, config, registry))
        registry.register(YamlWorkflowTemplate(bDef, config, registry))
        registry.register(YamlWorkflowTemplate(cDef, config, registry))
        registry.register(YamlWorkflowTemplate(aDef, config, registry))

        val resolvedA = WorkflowParameterResolver.resolve(aDef, emptyMap(), "test")
        val workflow = YamlWorkflowCompiler.compile(resolvedA, config, "test", registry)
        val result = WorkflowFactory.runWorkflow(
            workflow = workflow,
            initialState = SpolaState(goal = "test"),
            config = config,
        )

        assertTrue(result.contains("diamond leaf"), "Diamond chain should execute: $result")
    }

    @Test
    fun `composite step multiple steps referencing same sub-workflow`() = runTest {
        val leafYaml = """
            |name: leaf-workflow
            |steps:
            |  - id: leaf-step
            |    type: shell
            |    command: "echo multiple refs"
        """.trimMargin()
        val parentYaml = """
            |name: multi-ref
            |steps:
            |  - id: first
            |    type: composite
            |    workflow_ref: leaf-workflow
            |  - id: second
            |    type: composite
            |    workflow_ref: leaf-workflow
        """.trimMargin()

        val leafDef = requireNotNull(YamlWorkflowParser.parseContent(leafYaml))
        val parentDef = requireNotNull(YamlWorkflowParser.parseContent(parentYaml))

        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        registry.register(YamlWorkflowTemplate(leafDef, config, registry))

        val resolved = WorkflowParameterResolver.resolve(parentDef, emptyMap(), "test")
        val workflow = YamlWorkflowCompiler.compile(resolved, config, "test", registry)
        val result = WorkflowFactory.runWorkflow(
            workflow = workflow,
            initialState = SpolaState(goal = "test"),
            config = config,
        )

        assertTrue(result.contains("multiple refs"), "Multiple composite steps should execute: $result")
    }

    @Test
    fun `composite step nesting depth limit enforced`() = runTest {
        // Create 12 workflows: wf-1 composites to wf-2, ..., wf-11 composites to wf-12
        // Max depth is 10, so this should fail at wf-11 (depth 10 >= 10)
        val wfYamls = (1..11).map { i ->
            val target = if (i < 11) "wf-${i + 1}" else "wf-12"
            """
                |name: wf-$i
                |steps:
                |  - id: step-$i
                |    type: composite
                |    workflow_ref: $target
            """.trimMargin()
        }
        val leafYaml = """
            |name: wf-12
            |steps:
            |  - id: leaf
            |    type: shell
            |    command: "echo done"
        """.trimMargin()

        val registry = WorkflowTemplateRegistry()
        val config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))

        val defs = wfYamls.map { yaml -> requireNotNull(YamlWorkflowParser.parseContent(yaml)) }
        val leafDef = requireNotNull(YamlWorkflowParser.parseContent(leafYaml))

        for (def in (defs + leafDef)) {
            registry.register(YamlWorkflowTemplate(def, config, registry))
        }

        val resolved = WorkflowParameterResolver.resolve(defs.first(), emptyMap(), "test")
        val workflow = YamlWorkflowCompiler.compile(resolved, config, "test", registry)
        val error = assertFailsWith<IllegalStateException> {
            WorkflowFactory.runWorkflow(
                workflow = workflow,
                initialState = SpolaState(goal = "test"),
                config = config,
            )
        }

        assertTrue(
            error.message?.contains("Maximum composite nesting depth") == true,
            "Error: ${error.message}"
        )
    }

    @Test
    fun `done condition evaluator - empty result with output_has_content`() {
        val state = SpolaState(goal = "test", result = "")
        val result = DoneConditionEvaluator.evaluate(
            DoneCondition("output_has_content"),
            state,
            emptyMap(),
        )
        assertTrue(!result)
    }

    @Test
    fun `done condition evaluator - output_contains with null value`() {
        val state = SpolaState(goal = "test", result = "some output")
        val result = DoneConditionEvaluator.evaluate(
            DoneCondition("output_contains", null),
            state,
            emptyMap(),
        )
        assertTrue(result)
    }

    @Test
    fun `done condition evaluator - no_critical_blockers handles mixed case`() {
        val state = SpolaState(goal = "test", result = "critical issue remains unresolved")
        val result = DoneConditionEvaluator.evaluate(
            DoneCondition("no_critical_blockers"),
            state,
            emptyMap(),
        )
        assertTrue(!result)
    }

    @Test
    fun `done condition evaluator - all_steps_passed condition`() {
        val state = SpolaState(goal = "test", result = "workflow complete")
        val result = DoneConditionEvaluator.evaluate(
            DoneCondition("all_steps_passed"),
            state,
            emptyMap(),
        )
        assertTrue(result)
    }

    @Test
    fun `global done condition - output_has_content`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: global-done-output-has-content
                |steps:
                |  - id: shell-step
                |    type: shell
                |    command: "printf hello"
                |done:
                |  - condition: output_has_content
            """.trimMargin()
        )

        assertEquals("hello", result)
    }

    @Test
    fun `global done condition - output_contains`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: global-done-output-contains
                |steps:
                |  - id: shell-step
                |    type: shell
                |    command: "printf 'BUILD SUCCESSFUL'"
                |done:
                |  - condition: output_contains
                |    value: "SUCCESSFUL"
            """.trimMargin()
        )

        assertEquals("BUILD SUCCESSFUL", result)
    }

    @Test
    fun `global done condition - no_critical_blockers passes`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: global-done-no-critical-pass
                |steps:
                |  - id: shell-step
                |    type: shell
                |    command: "printf 'All checks passed'"
                |done:
                |  - condition: no_critical_blockers
            """.trimMargin()
        )

        assertEquals("All checks passed", result)
    }

    @Test
    fun `global done condition - no_critical_blockers fails`() = runTest {
        val error = assertFailsWith<WorkflowGateRejectedException> {
            runYamlWorkflow(
                """
                    |name: global-done-no-critical-fail
                    |steps:
                    |  - id: shell-step
                    |    type: shell
                    |    command: "printf 'CRITICAL: vulnerability found'"
                    |done:
                    |  - condition: no_critical_blockers
                """.trimMargin()
            )
        }

        assertTrue(error.message?.contains("Global done conditions not met") == true, "Error: ${error.message}")
    }

    @Test
    fun `global done condition - output_contains fails`() = runTest {
        val error = assertFailsWith<WorkflowGateRejectedException> {
            runYamlWorkflow(
                """
                    |name: global-done-output-contains-fail
                    |steps:
                    |  - id: shell-step
                    |    type: shell
                    |    command: "printf hello"
                    |done:
                    |  - condition: output_contains
                    |    value: "COMPLETED"
                """.trimMargin()
            )
        }

        assertTrue(error.message?.contains("Global done conditions not met") == true, "Error: ${error.message}")
    }

    @Test
    fun `global done condition - empty workflow done passes`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: global-done-empty
                |steps:
                |  - id: shell-step
                |    type: shell
                |    command: "printf ok"
                |done: []
            """.trimMargin()
        )

        assertEquals("ok", result)
    }

    @Test
    fun `global done condition - mixed step and workflow done`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: global-done-mixed
                |steps:
                |  - id: shell-step
                |    type: shell
                |    command: "printf ok"
                |    done:
                |      - condition: output_has_content
                |done:
                |  - condition: output_contains
                |    value: "ok"
            """.trimMargin()
        )

        assertEquals("ok", result)
    }

    @Test
    fun `global done condition - multi step with all_steps_passed`() = runTest {
        val result = runYamlWorkflow(
            """
                |name: global-done-all-steps-passed
                |steps:
                |  - id: one
                |    type: shell
                |    command: "printf one"
                |  - id: two
                |    type: shell
                |    command: "printf '{{step.one.output}}-two'"
                |    depends_on: [one]
                |done:
                |  - condition: all_steps_passed
            """.trimMargin()
        )

        assertEquals("one-two", result)
    }

    // ── LLM Judge condition tests ──

    @Test
    fun `llm judge condition parses from YAML`() {
        val yaml = """
            |name: llm-judge-parse
            |steps:
            |  - id: analyze
            |    type: ai
            |    goal: "Analyze code"
            |    done:
            |      - condition: llm_judge
            |        value: "Is the analysis thorough and complete?"
            |done:
            |  - condition: llm_judge
            |    value: "Is the final output acceptable?"
        """.trimMargin()

        val def = YamlWorkflowParser.parseContent(yaml)
        assertNotNull(def)
        assertEquals(1, def.steps.size)
        assertEquals(1, def.steps[0].done.size)
        assertEquals("llm_judge", def.steps[0].done[0].condition)
        assertEquals("Is the analysis thorough and complete?", def.steps[0].done[0].value)
        assertEquals(1, def.done.size)
        assertEquals("llm_judge", def.done[0].condition)
        assertEquals("Is the final output acceptable?", def.done[0].value)
    }

    @Test
    fun `llm judge condition passes silently with no provider`() = runTest {
        // No provider configured in SpolaConfig (defaults), llm_judge should pass silently
        val yaml = """
            |name: llm-judge-no-provider
            |steps:
            |  - id: step-a
            |    type: shell
            |    command: "printf 'test output'"
            |    done:
            |      - condition: llm_judge
            |        value: "Does the output look good?"
        """.trimMargin()

        val result = runYamlWorkflow(yaml)
        assertEquals("test output", result)
    }

    @Test
    fun `llm judge with heuristic conditions coexist`() = runTest {
        val yaml = """
            |name: llm-judge-heuristic-coexist
            |steps:
            |  - id: step-a
            |    type: shell
            |    command: "printf 'analysis complete'"
            |    done:
            |      - condition: output_has_content
            |      - condition: llm_judge
            |        value: "Is the analysis thorough?"
        """.trimMargin()

        val result = runYamlWorkflow(yaml)
        assertEquals("analysis complete", result)
    }

    @Test
    fun `llm judge with mock provider returns true when condition passes`() = runTest {
        val mockProvider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(content = "true")
            override fun providerId(): String = "mock"
        }
        val conditions = listOf(DoneCondition("llm_judge", "Is this good?"))
        val state = SpolaState(goal = "test", result = "good output")
        val passed = YamlWorkflowCompiler.evaluateLlmConditions(
            conditions, state, mockProvider, "mock-model",
        )
        assertTrue(passed)
    }

    @Test
    fun `llm judge with mock provider returns false when condition fails`() = runTest {
        val mockProvider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(content = "false")
            override fun providerId(): String = "mock"
        }
        val conditions = listOf(DoneCondition("llm_judge", "Is this good?"))
        val state = SpolaState(goal = "test", result = "bad output")
        val passed = YamlWorkflowCompiler.evaluateLlmConditions(
            conditions, state, mockProvider, "mock-model",
        )
        assertFalse(passed)
    }

    @Test
    fun `llm judge with mock provider passes on word-boundary true`() = runTest {
        val mockProvider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(content = "verdict: true")
            override fun providerId(): String = "mock"
        }
        val conditions = listOf(DoneCondition("llm_judge", "Is this good?"))
        val state = SpolaState(goal = "test", result = "decent output")
        val passed = YamlWorkflowCompiler.evaluateLlmConditions(
            conditions, state, mockProvider, "mock-model",
        )
        assertTrue(passed, "Should accept 'verdict: true' as pass")
    }

    @Test
    fun `llm judge with mock provider throws on provider error`() = runTest {
        val mockProvider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                throw RuntimeException("API error")
            }
            override fun providerId(): String = "mock"
        }
        val conditions = listOf(DoneCondition("llm_judge", "Is this good?"))
        val state = SpolaState(goal = "test", result = "output")
        assertFailsWith<RuntimeException> {
            YamlWorkflowCompiler.evaluateLlmConditions(
                conditions, state, mockProvider, "mock-model",
            )
        }
    }

    // ── Human Approval / Resume Tests ──────────────────────────

    @Test
    fun `human_approval step transitions to WAITING_APPROVAL on gate rejection`() = runTest {
        val yaml = """
            name: test-approval-gate
            version: "1"
            steps:
              - id: pre-step
                type: shell
                command: "echo setup done"
              - id: review
                type: human_approval
                prompt: "Approve this step?"
              - id: post-step
                type: shell
                command: "echo approved"
                depends_on: [review]
        """.trimIndent()

        val testConfig = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        val registry = WorkflowTemplateRegistry()
        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML"
        }
        registry.register(YamlWorkflowTemplate(def, testConfig, registry))

        val store = SqliteWorkflowExecutionStore(":memory:")
        val service = WorkflowExecutionService(
            config = testConfig,
            executionStore = store,
            workflowRegistry = registry,
        )

        val record = service.enqueue(
            NewWorkflowExecution(
                definitionId = null,
                workflowName = "test-approval-gate",
                inputJson = """{"goal":"test"}""",
            )
        )

        // Should hit the gate and throw WorkflowGateRejectedException
        val exception = try {
            service.runExecution(record)
            null
        } catch (e: WorkflowGateRejectedException) {
            e
        }
        assertNotNull(exception, "Expected WorkflowGateRejectedException")

        val updated = store.get(record.id)!!
        assertEquals(WorkflowExecutionStatus.WAITING_APPROVAL, updated.status)
        assertNotNull(updated.checkpointKey, "checkpointKey should be stored for resume")
    }

    @Test
    fun `approve on non-WAITING_APPROVAL execution returns false`() = runTest {
        val yaml = """
            name: test-approval-non-waiting
            version: "1"
            steps:
              - id: review
                type: human_approval
                prompt: "Approve?"
        """.trimIndent()

        val testConfig = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        val registry = WorkflowTemplateRegistry()
        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml))
        registry.register(YamlWorkflowTemplate(def, testConfig, registry))

        val store = SqliteWorkflowExecutionStore(":memory:")
        val service = WorkflowExecutionService(
            config = testConfig,
            executionStore = store,
            workflowRegistry = registry,
        )

        val record = service.enqueue(
            NewWorkflowExecution(
                definitionId = null,
                workflowName = "test-approval-non-waiting",
                inputJson = """{"goal":"test"}""",
            )
        )

        // Still QUEUED, not WAITING_APPROVAL
        val result = service.approveExecution(record.id)
        assertFalse(result)
    }

    @Test
    fun `approve on completed execution returns false`() = runTest {
        val yaml = """
            name: simple-approve-test-completed
            version: "1"
            steps:
              - id: step-1
                type: shell
                command: "echo done"
        """.trimIndent()

        val testConfig = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        val registry = WorkflowTemplateRegistry()
        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml))
        registry.register(YamlWorkflowTemplate(def, testConfig, registry))

        val store = SqliteWorkflowExecutionStore(":memory:")
        val service = WorkflowExecutionService(
            config = testConfig,
            executionStore = store,
            workflowRegistry = registry,
        )

        val record = service.enqueue(
            NewWorkflowExecution(
                definitionId = null,
                workflowName = "simple-approve-test-completed",
                inputJson = """{"goal":"test"}""",
            )
        )

        service.runExecution(record) // completes normally

        val result = service.approveExecution(record.id)
        assertFalse(result) // already COMPLETED
    }

    @Test
    fun `approve execution resumes workflow from checkpoint`() = runTest {
        val yaml = """
            name: test-approval-resume
            version: "1"
            steps:
              - id: pre-step
                type: shell
                command: "echo start"
              - id: review
                type: human_approval
                prompt: "Approve this step?"
              - id: post-step
                type: shell
                command: "echo approved-workflow"
                depends_on: [review]
        """.trimIndent()

        val testConfig = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false))
        val registry = WorkflowTemplateRegistry()
        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML"
        }
        registry.register(YamlWorkflowTemplate(def, testConfig, registry))

        val store = SqliteWorkflowExecutionStore(":memory:")
        val service = WorkflowExecutionService(
            config = testConfig,
            executionStore = store,
            workflowRegistry = registry,
        )

        val record = service.enqueue(
            NewWorkflowExecution(
                definitionId = null,
                workflowName = "test-approval-resume",
                inputJson = """{"goal":"test"}""",
            )
        )

        // First run: should hit the gate and throw
        val gateException = try {
            service.runExecution(record)
            null
        } catch (e: WorkflowGateRejectedException) {
            e
        }
        assertNotNull(gateException, "Expected WorkflowGateRejectedException")

        val waiting = store.get(record.id)!!
        assertEquals(WorkflowExecutionStatus.WAITING_APPROVAL, waiting.status)
        assertNotNull(waiting.checkpointKey)

        // Approve: should resume from checkpoint and complete
        val approved = service.approveExecution(record.id)
        assertTrue(approved, "approveExecution should return true on success")

        val completed = store.get(record.id)!!
        assertEquals(WorkflowExecutionStatus.COMPLETED, completed.status)
        assertNotNull(completed.result)
        assertTrue(completed.result!!.contains("approved-workflow"))
    }

    // ── Helpers ──

    private fun step(id: String, deps: List<String>? = null) = ResolvedStep(
        id = id,
        type = "ai",
        goal = "Goal for $id",
        persona = null,
        agents = null,
        dependsOn = deps,
        command = null,
        timeout = 60,
        prompt = null,
        expression = null,
        workflowRef = null,
        invoke = null,
        done = emptyList(),
        onError = OnError.FAIL,
        retryCount = 0,
        maxOutputBytes = 10 * 1024 * 1024,
        env = null,
    )

    private fun withTempYamlWorkflow(test: (Path) -> Unit) {
        val tempDir = Files.createTempDirectory("spola-yaml-test")
        try {
            val yaml = """
                |name: custom-audit
                |steps:
                |  - id: audit
                |    type: ai
                |    goal: "Audit the codebase"
                |    persona: "You are an auditor."
            """.trimMargin()
            val yamlPath = tempDir.resolve("custom-audit.yaml")
            Files.writeString(yamlPath, yaml)
            test(yamlPath)
        } finally {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private suspend fun runYamlWorkflow(yaml: String, goal: String = "test"): String {
        val def = requireNotNull(YamlWorkflowParser.parseContent(yaml)) {
            "Failed to parse workflow YAML: ${yaml.take(200)}"
        }
        val resolved = WorkflowParameterResolver.resolve(def, emptyMap(), goal)
        val workflow = YamlWorkflowCompiler.compile(
            resolved = resolved,
            config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false)),
            goal = goal,
            registry = WorkflowTemplateRegistry(),
        )

        return WorkflowFactory.runWorkflow(
            workflow = workflow,
            initialState = SpolaState(goal = goal),
            config = SpolaConfig(metrics = MetricsConfig(metricsEnabled = false)),
        )
    }
}
