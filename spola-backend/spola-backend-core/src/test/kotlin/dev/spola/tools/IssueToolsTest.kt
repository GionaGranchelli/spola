package dev.spola.tools

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IssueToolsTest {

    @Test
    fun `github_issue_search tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_search")
        assertNotNull(tool, "github_issue_search tool should be registered")
        assertEquals("github_issue_search", tool.name)
        assertTrue(tool.description.contains("GitHub", ignoreCase = true), "Description should mention GitHub")

        val paramNames = tool.parameters.map { it.name }
        assertTrue("query" in paramNames, "Should have query parameter")
        assertTrue("repo" in paramNames, "Should have repo parameter")
        assertTrue("limit" in paramNames, "Should have limit parameter")

        val queryParam = tool.parameters.find { it.name == "query" }
        assertNotNull(queryParam)
        assertTrue(queryParam.required, "query should be required")

        val repoParam = tool.parameters.find { it.name == "repo" }
        assertNotNull(repoParam)
        assertFalse(repoParam.required, "repo should not be required")

        val limitParam = tool.parameters.find { it.name == "limit" }
        assertNotNull(limitParam)
        assertFalse(limitParam.required, "limit should not be required")
        assertEquals(10, limitParam.defaultValue, "limit default should be 10")
    }

    @Test
    fun `github_issue_create tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_create")
        assertNotNull(tool, "github_issue_create tool should be registered")
        assertEquals("github_issue_create", tool.name)
        assertTrue(tool.description.contains("GitHub", ignoreCase = true), "Description should mention GitHub")

        val paramNames = tool.parameters.map { it.name }
        assertTrue("title" in paramNames, "Should have title parameter")
        assertTrue("body" in paramNames, "Should have body parameter")
        assertTrue("repo" in paramNames, "Should have repo parameter")
        assertTrue("labels" in paramNames, "Should have labels parameter")
        assertTrue("assignee" in paramNames, "Should have assignee parameter")

        val titleParam = tool.parameters.find { it.name == "title" }
        assertNotNull(titleParam)
        assertTrue(titleParam.required, "title should be required")

        val bodyParam = tool.parameters.find { it.name == "body" }
        assertNotNull(bodyParam)
        assertTrue(bodyParam.required, "body should be required")

        val repoParam = tool.parameters.find { it.name == "repo" }
        assertNotNull(repoParam)
        assertTrue(repoParam.required, "repo should be required")

        val labelsParam = tool.parameters.find { it.name == "labels" }
        assertNotNull(labelsParam)
        assertFalse(labelsParam.required, "labels should not be required")

        val assigneeParam = tool.parameters.find { it.name == "assignee" }
        assertNotNull(assigneeParam)
        assertFalse(assigneeParam.required, "assignee should not be required")
    }

    @Test
    fun `github_issue_comment tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_comment")
        assertNotNull(tool, "github_issue_comment tool should be registered")
        assertEquals("github_issue_comment", tool.name)
        assertTrue(tool.description.contains("GitHub", ignoreCase = true), "Description should mention GitHub")

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
    fun `github_issue_transition tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_transition")
        assertNotNull(tool, "github_issue_transition tool should be registered")
        assertEquals("github_issue_transition", tool.name)
        assertTrue(tool.description.contains("GitHub", ignoreCase = true), "Description should mention GitHub")

        val paramNames = tool.parameters.map { it.name }
        assertTrue("issue_key" in paramNames, "Should have issue_key parameter")
        assertTrue("status" in paramNames, "Should have status parameter")

        val issueKeyParam = tool.parameters.find { it.name == "issue_key" }
        assertNotNull(issueKeyParam)
        assertTrue(issueKeyParam.required, "issue_key should be required")

        val statusParam = tool.parameters.find { it.name == "status" }
        assertNotNull(statusParam)
        assertTrue(statusParam.required, "status should be required")
    }

    @Test
    fun `github_issue_search returns error when query missing`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_search")!!
        val result = tool.execute(mapOf())

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `github_issue_search returns error when query empty`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_search")!!
        val result = tool.execute(mapOf("query" to ""))

        assertFalse(result.success)
        assertTrue(result.output.contains("must not be empty", ignoreCase = true))
    }

    @Test
    fun `github_issue_create returns error when title missing`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_create")!!
        val result = tool.execute(mapOf("body" to "test body", "repo" to "owner/repo"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `github_issue_create returns error when repo missing`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_create")!!
        val result = tool.execute(mapOf("title" to "test title", "body" to "test body"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `github_issue_create returns error when repo format invalid`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_create")!!
        val result = tool.execute(
            mapOf("title" to "test title", "body" to "test body", "repo" to "invalid-repo"),
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("owner/repo", ignoreCase = true))
    }

    @Test
    fun `github_issue_create returns error when body missing`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_create")!!
        val result = tool.execute(mapOf("title" to "test title", "repo" to "owner/repo"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `github_issue_comment returns error when issue_key missing`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_comment")!!
        val result = tool.execute(mapOf("body" to "test comment"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `github_issue_comment returns error when body missing`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_comment")!!
        val result = tool.execute(mapOf("issue_key" to "owner/repo#123"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `github_issue_comment returns error when body empty`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_comment")!!
        val result = tool.execute(mapOf("issue_key" to "owner/repo#123", "body" to ""))

        assertFalse(result.success)
        assertTrue(result.output.contains("must not be empty", ignoreCase = true))
    }

    @Test
    fun `github_issue_transition returns error when issue_key missing`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_transition")!!
        val result = tool.execute(mapOf("status" to "closed"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `github_issue_transition returns error when status missing`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_transition")!!
        val result = tool.execute(mapOf("issue_key" to "owner/repo#123"))

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `github_issue_transition validates status values`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_transition")!!

        val invalidResult = tool.execute(
            mapOf("issue_key" to "owner/repo#123", "status" to "invalid"),
        )
        assertFalse(invalidResult.success)
        assertTrue(invalidResult.output.contains("open", ignoreCase = true))
        assertTrue(invalidResult.output.contains("closed", ignoreCase = true))
    }

    @Test
    fun `github_issue_transition accepts open status`() = runTest {
        // This test verifies validation passes for valid status values
        // It will fail on HTTP since there's no token, but validation should succeed
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_transition")!!
        val result = tool.execute(
            mapOf("issue_key" to "owner/repo#123", "status" to "open"),
        )

        assertFalse(result.success)
        // Should fail on connection/token, not validation
        assertTrue(
            !result.output.lowercase().contains("must be"),
            "Should fail with connection/API error, not validation. Got: ${result.output}",
        )
    }

    @Test
    fun `github_issue_transition accepts closed status`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_transition")!!
        val result = tool.execute(
            mapOf("issue_key" to "owner/repo#123", "status" to "closed"),
        )

        assertFalse(result.success)
        // Should fail on connection/token, not validation
        assertTrue(
            !result.output.lowercase().contains("must be"),
            "Should fail with connection/API error, not validation. Got: ${result.output}",
        )
    }

    @Test
    fun `github_issue_comment parses issue_key format correctly`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_comment")!!

        // Valid format - will fail with connection/API error, not parsing
        val result = tool.execute(
            mapOf("issue_key" to "owner/repo#123", "body" to "Nice work!"),
        )

        assertFalse(result.success)
        // Should fail with connection/API/token error, not parsing
        assertTrue(
            !result.output.lowercase().contains("invalid issue_key") &&
                !result.output.lowercase().contains("must not be empty") &&
                !result.output.lowercase().contains("missing required"),
            "Should fail with connection/API error after parsing. Got: ${result.output}",
        )
    }

    @Test
    fun `github_issue_comment rejects invalid issue_key format`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_comment")!!

        val result = tool.execute(
            mapOf("issue_key" to "invalid-key", "body" to "Nice work!"),
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("Invalid", ignoreCase = true))
    }

    @Test
    fun `github_issue_comment rejects issue_key without hash`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_comment")!!

        val result = tool.execute(
            mapOf("issue_key" to "owner/repo", "body" to "Nice work!"),
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("Invalid", ignoreCase = true))
    }

    @Test
    fun `github_issue_tools are included via registerTools with config`() {
        val config = SpolaConfig()
        val registry = ToolRegistry()
        registerTools(registry, config)

        assertNotNull(registry.get("github_issue_search"), "github_issue_search should be registered via registerTools")
        assertNotNull(registry.get("github_issue_create"), "github_issue_create should be registered via registerTools")
        assertNotNull(registry.get("github_issue_comment"), "github_issue_comment should be registered via registerTools")
        assertNotNull(registry.get("github_issue_transition"), "github_issue_transition should be registered via registerTools")
    }

    @Test
    fun `github_issue_tools are NOT included via registerTools without config`() {
        val registry = ToolRegistry()
        registerTools(registry)

        assertEquals(null, registry.get("github_issue_search"))
        assertEquals(null, registry.get("github_issue_create"))
        assertEquals(null, registry.get("github_issue_comment"))
        assertEquals(null, registry.get("github_issue_transition"))
    }

    @Test
    fun `github_issue_search with valid args attempts GitHub API call`() = runTest {
        // This test verifies the tool attempts an HTTP call and handles failure gracefully
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_search")!!
        // Valid arguments but no real API -> connection error or token error
        val result = tool.execute(mapOf("query" to "bug", "repo" to "owner/repo", "limit" to 5))

        assertFalse(result.success)
        // Should fail with connection/timeout/token, not validation error
        assertTrue(
            !result.output.lowercase().contains("missing required"),
            "Should fail with connection/API error, not validation. Got: ${result.output}",
        )
    }

    @Test
    fun `github_issue_create with valid args attempts GitHub API call`() = runTest {
        // This test verifies the tool attempts an HTTP call and handles failure gracefully
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_create")!!
        // Valid arguments but no real API -> connection error or token error
        val result = tool.execute(
            mapOf(
                "title" to "Test issue",
                "body" to "This is a test",
                "repo" to "owner/repo",
            ),
        )

        assertFalse(result.success)
        // Should fail with connection/timeout/token, not validation error
        assertTrue(
            !result.output.lowercase().contains("missing required") &&
                !result.output.lowercase().contains("must not be empty") &&
                !result.output.lowercase().contains("owner/repo"),
            "Should fail with connection/API error, not validation. Got: ${result.output}",
        )
    }

    @Test
    fun `github_issue_search returns error when query too long gracefully handled`() = runTest {
        val registry = ToolRegistry()
        registerIssueTools(registry)

        val tool = registry.get("github_issue_search")!!
        val result = tool.execute(mapOf("query" to "a".repeat(500)))

        assertFalse(result.success)
        // Should not be a validation error — it will attempt the API call
        assertTrue(
            !result.output.lowercase().contains("missing required"),
            "Should fail with API/connection error, not validation. Got: ${result.output}",
        )
    }
}
