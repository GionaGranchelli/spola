package dev.spola.tools

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JiraIssueToolsTest {

    @Test
    fun `jira_issue_search tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_search")
        assertNotNull(tool, "jira_issue_search tool should be registered")
        assertEquals("jira_issue_search", tool.name)
        assertTrue(tool.description.contains("Jira", ignoreCase = true), "Description should mention Jira")

        val paramNames = tool.parameters.map { it.name }
        assertTrue("jql" in paramNames, "Should have jql parameter")
        assertTrue("maxResults" in paramNames, "Should have maxResults parameter")

        val jqlParam = tool.parameters.find { it.name == "jql" }
        assertNotNull(jqlParam)
        assertTrue(jqlParam.required, "jql should be required")

        val maxResultsParam = tool.parameters.find { it.name == "maxResults" }
        assertNotNull(maxResultsParam)
        assertFalse(maxResultsParam.required, "maxResults should not be required")
        assertEquals(20, maxResultsParam.defaultValue, "maxResults default should be 20")
    }

    @Test
    fun `jira_issue_create tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_create")
        assertNotNull(tool, "jira_issue_create tool should be registered")
        assertEquals("jira_issue_create", tool.name)
        assertTrue(tool.description.contains("Jira", ignoreCase = true), "Description should mention Jira")

        val paramNames = tool.parameters.map { it.name }
        assertTrue("project_key" in paramNames, "Should have project_key parameter")
        assertTrue("summary" in paramNames, "Should have summary parameter")
        assertTrue("description" in paramNames, "Should have description parameter")
        assertTrue("issue_type" in paramNames, "Should have issue_type parameter")

        val projectKeyParam = tool.parameters.find { it.name == "project_key" }
        assertNotNull(projectKeyParam)
        assertTrue(projectKeyParam.required, "project_key should be required")

        val summaryParam = tool.parameters.find { it.name == "summary" }
        assertNotNull(summaryParam)
        assertTrue(summaryParam.required, "summary should be required")

        val descriptionParam = tool.parameters.find { it.name == "description" }
        assertNotNull(descriptionParam)
        assertTrue(descriptionParam.required, "description should be required")

        val issueTypeParam = tool.parameters.find { it.name == "issue_type" }
        assertNotNull(issueTypeParam)
        assertFalse(issueTypeParam.required, "issue_type should not be required")
        assertEquals("Task", issueTypeParam.defaultValue, "issue_type default should be Task")
    }

    @Test
    fun `jira_issue_comment tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_comment")
        assertNotNull(tool, "jira_issue_comment tool should be registered")
        assertEquals("jira_issue_comment", tool.name)
        assertTrue(tool.description.contains("Jira", ignoreCase = true), "Description should mention Jira")

        val paramNames = tool.parameters.map { it.name }
        assertTrue("issue_key" in paramNames, "Should have issue_key parameter")
        assertTrue("body" in paramNames, "Should have body parameter")

        val issueKeyParam = tool.parameters.find { it.name == "issue_key" }
        assertNotNull(issueKeyParam)
        assertTrue(issueKeyParam.required, "issue_key should be required")

        val bodyParam = tool.parameters.find { it.name == "body" }
        assertNotNull(bodyParam)
        assertTrue(bodyParam.required, "body should be required")
    }

    @Test
    fun `jira_issue_transition tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_transition")
        assertNotNull(tool, "jira_issue_transition tool should be registered")
        assertEquals("jira_issue_transition", tool.name)
        assertTrue(tool.description.contains("Jira", ignoreCase = true), "Description should mention Jira")

        val paramNames = tool.parameters.map { it.name }
        assertTrue("issue_key" in paramNames, "Should have issue_key parameter")
        assertTrue("transition_name" in paramNames, "Should have transition_name parameter")

        val issueKeyParam = tool.parameters.find { it.name == "issue_key" }
        assertNotNull(issueKeyParam)
        assertTrue(issueKeyParam.required, "issue_key should be required")

        val transitionNameParam = tool.parameters.find { it.name == "transition_name" }
        assertNotNull(transitionNameParam)
        assertTrue(transitionNameParam.required, "transition_name should be required")
    }

    @Test
    fun `jira_issue_search returns error when jql missing`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_search")!!
        val result = tool.execute(mapOf())

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `jira_issue_search returns error when jql empty`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_search")!!
        val result = tool.execute(mapOf("jql" to ""))

        assertFalse(result.success)
        assertTrue(result.output.contains("must not be empty", ignoreCase = true))
    }

    @Test
    fun `jira_issue_create returns error when project_key missing`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_create")!!
        val result = tool.execute(mapOf("summary" to "test", "description" to "test desc"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `jira_issue_create returns error when summary missing`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_create")!!
        val result = tool.execute(mapOf("project_key" to "PROJ", "description" to "test desc"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `jira_issue_create returns error when description missing`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_create")!!
        val result = tool.execute(mapOf("project_key" to "PROJ", "summary" to "test"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `jira_issue_comment returns error when issue_key missing`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_comment")!!
        val result = tool.execute(mapOf("body" to "test comment"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `jira_issue_comment returns error when body missing`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_comment")!!
        val result = tool.execute(mapOf("issue_key" to "PROJ-123"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `jira_issue_comment returns error when body empty`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_comment")!!
        val result = tool.execute(mapOf("issue_key" to "PROJ-123", "body" to ""))

        assertFalse(result.success)
        assertTrue(result.output.contains("must not be empty", ignoreCase = true))
    }

    @Test
    fun `jira_issue_transition returns error when issue_key missing`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_transition")!!
        val result = tool.execute(mapOf("transition_name" to "Done"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `jira_issue_transition returns error when transition_name missing`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_transition")!!
        val result = tool.execute(mapOf("issue_key" to "PROJ-123"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `jira_issue_comment rejects invalid issue_key format`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_comment")!!
        val result = tool.execute(
            mapOf("issue_key" to "invalid-key", "body" to "Nice work!"),
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("Invalid", ignoreCase = true))
    }

    @Test
    fun `jira_issue_comment rejects issue_key without number`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_comment")!!
        val result = tool.execute(
            mapOf("issue_key" to "PROJ", "body" to "Nice work!"),
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("Invalid", ignoreCase = true))
    }

    @Test
    fun `jira_issue_transition rejects invalid issue_key format`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_transition")!!
        val result = tool.execute(
            mapOf("issue_key" to "bad-key", "transition_name" to "Done"),
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("Invalid", ignoreCase = true))
    }

    @Test
    fun `jira_issue_tools are included via registerTools with config`() {
        val config = SpolaConfig()
        val registry = ToolRegistry()
        registerTools(registry, config)

        assertNotNull(registry.get("jira_issue_search"), "jira_issue_search should be registered via registerTools")
        assertNotNull(registry.get("jira_issue_create"), "jira_issue_create should be registered via registerTools")
        assertNotNull(registry.get("jira_issue_comment"), "jira_issue_comment should be registered via registerTools")
        assertNotNull(registry.get("jira_issue_transition"), "jira_issue_transition should be registered via registerTools")
    }

    @Test
    fun `jira_issue_tools are NOT included via registerTools without config`() {
        val registry = ToolRegistry()
        registerTools(registry)

        assertEquals(null, registry.get("jira_issue_search"))
        assertEquals(null, registry.get("jira_issue_create"))
        assertEquals(null, registry.get("jira_issue_comment"))
        assertEquals(null, registry.get("jira_issue_transition"))
    }

    @Test
    fun `jira_issue_search with valid args attempts Jira API call`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_search")!!
        // Valid arguments but no real API -> connection error or URL error
        val result = tool.execute(mapOf("jql" to "project = PROJ", "maxResults" to 5))

        assertFalse(result.success)
        // Should fail with connection/URL/config error, not validation error
        assertTrue(
            !result.output.lowercase().contains("missing required"),
            "Should fail with connection/API error, not validation. Got: ${result.output}",
        )
    }

    @Test
    fun `jira_issue_create with valid args attempts Jira API call`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_create")!!
        // Valid arguments but no real API -> connection error or config error
        val result = tool.execute(
            mapOf(
                "project_key" to "PROJ",
                "summary" to "Test issue",
                "description" to "This is a test",
            ),
        )

        assertFalse(result.success)
        // Should fail with connection/URL/config error, not validation error
        assertTrue(
            !result.output.lowercase().contains("missing required") &&
                !result.output.lowercase().contains("must not be empty"),
            "Should fail with connection/API error, not validation. Got: ${result.output}",
        )
    }

    @Test
    fun `jira_issue_transition with valid args looks up transitions`() = runTest {
        val registry = ToolRegistry()
        registerJiraIssueTools(registry)

        val tool = registry.get("jira_issue_transition")!!
        // Valid arguments but no real API -> will fail with URL/config error
        val result = tool.execute(
            mapOf("issue_key" to "PROJ-123", "transition_name" to "Done"),
        )

        assertFalse(result.success)
        // Should fail with connection/URL/config error, not validation
        assertTrue(
            !result.output.lowercase().contains("missing required") &&
                !result.output.lowercase().contains("must not be empty") &&
                !result.output.lowercase().contains("invalid issue_key"),
            "Should fail with connection/API error, not validation. Got: ${result.output}",
        )
    }
}
