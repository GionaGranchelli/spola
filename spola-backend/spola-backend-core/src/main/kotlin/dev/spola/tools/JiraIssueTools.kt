package dev.spola.tools

import dev.spola.SpolaConfig
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

private val jiraLogger = LoggerFactory.getLogger("dev.spola.tools.JiraIssueTools")
private val jiraJson = Json { ignoreUnknownKeys = true }

/**
 * Register Jira issue tools: jira_issue_search, jira_issue_create,
 * jira_issue_comment, and jira_issue_transition.
 * These tools require the JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN
 * environment variables.
 */
fun registerJiraIssueTools(
    registry: ToolRegistry,
    config: SpolaConfig = SpolaConfig(),
) {
    registry.register(jiraIssueSearchTool())
    registry.register(jiraIssueCreateTool())
    registry.register(jiraIssueCommentTool())
    registry.register(jiraIssueTransitionTool())
}

private val jiraAuthHeader: String? by lazy {
    val email = System.getenv("JIRA_EMAIL")?.takeIf { it.isNotBlank() }
    val token = System.getenv("JIRA_API_TOKEN")?.takeIf { it.isNotBlank() }
    if (email != null && token != null) {
        "Basic " + Base64.getEncoder().encodeToString("$email:$token".toByteArray())
    } else null
}

private fun jiraBaseUrl(): String? = System.getenv("JIRA_BASE_URL")?.takeIf { it.isNotBlank() }

private fun jiraHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private fun jiraRequestBuilder(uri: URI): HttpRequest.Builder {
    val auth = jiraAuthHeader
    val builder = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/json")
    if (auth != null) {
        builder.header("Authorization", auth)
    }
    return builder
}

private fun buildAdfDescription(text: String): String {
    val escaped = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return """{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"$escaped"}]}]}"""
}

private fun escapeJiraJson(s: String): String {
    return s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

@Suppress("unused")
private fun jiraIssueSearchTool(): Tool {
    return Tool(
        name = "jira_issue_search",
        description = "Search for Jira issues using JQL (Jira Query Language). Returns issues with key, summary, status, assignee, and priority.",
        parameters = listOf(
            ToolParameter("jql", "JQL query string (e.g. 'project = PROJ AND status = \"In Progress\"')", ToolParameterType.STRING),
            ToolParameter("maxResults", "Maximum number of results (default: 20, max: 100)", ToolParameterType.INTEGER, required = false, defaultValue = 20),
        ),
        execute = { args ->
            try {
                val jql = (args["jql"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: jql")
                if (jql.isEmpty()) return@Tool ToolResult.fail("jql must not be empty")

                val maxResults = ((args["maxResults"] as? Int) ?: 20).coerceIn(1, 100)

                val baseUrl = jiraBaseUrl()
                if (baseUrl == null) return@Tool ToolResult.fail(
                    "Jira base URL not configured. Set JIRA_BASE_URL environment variable."
                )

                val encodedJql = URLEncoder.encode(jql, StandardCharsets.UTF_8)
                val url = "${baseUrl.trimEnd('/')}/rest/api/3/search?jql=$encodedJql&maxResults=$maxResults"

                val client = jiraHttpClient()
                val request = jiraRequestBuilder(URI.create(url))
                    .GET()
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() in 200..299 -> {
                        val root = jiraJson.parseToJsonElement(response.body()).jsonObject
                        val issues = root["issues"]?.jsonArray ?: return@Tool ToolResult.ok("No issues found matching JQL: $jql")

                        val formatted = issues.mapNotNull { item ->
                            val obj = item.jsonObject
                            val key = obj["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val fields = obj["fields"]?.jsonObject ?: return@mapNotNull null
                            val summary = fields["summary"]?.jsonPrimitive?.content ?: "(no summary)"
                            val status = fields["status"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "unknown"
                            val assignee = fields["assignee"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content ?: "Unassigned"
                            val priority = fields["priority"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "None"

                            buildString {
                                appendLine("$key - $summary")
                                appendLine("Status: $status")
                                appendLine("Assignee: $assignee")
                                appendLine("Priority: $priority")
                            }.trim()
                        }

                        if (formatted.isEmpty()) {
                            ToolResult.ok("No issues found matching JQL: $jql")
                        } else {
                            ToolResult.ok(formatted.joinToString("\n\n"))
                        }
                    }
                    response.statusCode() == 401 -> {
                        ToolResult.fail("Jira API returned 401: Unauthorized — check JIRA_EMAIL and JIRA_API_TOKEN")
                    }
                    response.statusCode() == 403 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("Jira API returned 403: Forbidden — $body")
                    }
                    response.statusCode() == 404 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("Jira API returned 404: Not found — $body")
                    }
                    response.statusCode() == 400 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("Jira API returned 400: Bad request — $body")
                    }
                    else -> {
                        ToolResult.fail("Jira API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("Jira API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to Jira API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("Jira issue search failed: ${e.message}")
            }
        },
    )
}

private fun jiraIssueCreateTool(): Tool {
    return Tool(
        name = "jira_issue_create",
        description = "Create a new Jira issue. Requires JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN env vars.",
        parameters = listOf(
            ToolParameter("project_key", "Jira project key (e.g. PROJ)", ToolParameterType.STRING),
            ToolParameter("summary", "Issue summary/title", ToolParameterType.STRING),
            ToolParameter("description", "Issue description body", ToolParameterType.STRING),
            ToolParameter("issue_type", "Issue type name (e.g. Task, Bug, Story)", ToolParameterType.STRING, required = false, defaultValue = "Task"),
        ),
        execute = { args ->
            try {
                val projectKey = (args["project_key"] as? String)?.trim()?.uppercase()
                    ?: return@Tool ToolResult.fail("Missing required argument: project_key")
                if (projectKey.isEmpty()) return@Tool ToolResult.fail("project_key must not be empty")

                val summary = (args["summary"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: summary")
                if (summary.isEmpty()) return@Tool ToolResult.fail("summary must not be empty")

                val description = (args["description"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: description")
                if (description.isEmpty()) return@Tool ToolResult.fail("description must not be empty")

                val issueType = (args["issue_type"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "Task"

                val baseUrl = jiraBaseUrl()
                if (baseUrl == null) return@Tool ToolResult.fail(
                    "Jira base URL not configured. Set JIRA_BASE_URL environment variable."
                )
                if (jiraAuthHeader == null) return@Tool ToolResult.fail(
                    "Jira credentials not configured. Set JIRA_EMAIL and JIRA_API_TOKEN environment variables."
                )

                val adfDescription = buildAdfDescription(description)
                val jsonBody = buildString {
                    append("{\"fields\":{")
                    append("\"project\":{\"key\":\"${escapeJiraJson(projectKey)}\"},")
                    append("\"summary\":\"${escapeJiraJson(summary)}\",")
                    append("\"description\":$adfDescription,")
                    append("\"issuetype\":{\"name\":\"${escapeJiraJson(issueType)}\"}")
                    append("}}")
                }

                val url = "${baseUrl.trimEnd('/')}/rest/api/3/issue"
                val client = jiraHttpClient()
                val request = jiraRequestBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() in 200..299 || response.statusCode() == 201 -> {
                        val root = jiraJson.parseToJsonElement(response.body()).jsonObject
                        val issueKey = root["key"]?.jsonPrimitive?.content ?: "?"
                        val selfUrl = root["self"]?.jsonPrimitive?.content ?: ""
                        ToolResult.ok("Issue created successfully: $issueKey at $selfUrl")
                    }
                    response.statusCode() == 401 -> {
                        ToolResult.fail("Jira API returned 401: Unauthorized — check JIRA_EMAIL and JIRA_API_TOKEN")
                    }
                    response.statusCode() == 403 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("Jira API returned 403: Forbidden — $body")
                    }
                    response.statusCode() == 404 -> {
                        ToolResult.fail("Jira API returned 404: Not found — check project_key '$projectKey'")
                    }
                    response.statusCode() == 400 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("Jira API returned 400: Bad request — $body")
                    }
                    else -> {
                        ToolResult.fail("Jira API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("Jira API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to Jira API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("Failed to create Jira issue: ${e.message}")
            }
        },
    )
}

private fun jiraIssueCommentTool(): Tool {
    return Tool(
        name = "jira_issue_comment",
        description = "Add a comment to an existing Jira issue. Requires JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN env vars.",
        parameters = listOf(
            ToolParameter("issue_key", "Issue key in PROJ-123 format", ToolParameterType.STRING),
            ToolParameter("body", "Comment body text", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val issueKey = (args["issue_key"] as? String)?.trim()?.uppercase()
                    ?: return@Tool ToolResult.fail("Missing required argument: issue_key")
                if (issueKey.isEmpty()) return@Tool ToolResult.fail("issue_key must not be empty")
                if (!issueKey.matches(Regex("^[A-Z]+-\\d+$"))) {
                    return@Tool ToolResult.fail("Invalid issue_key format. Expected format like PROJ-123")
                }

                val body = (args["body"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: body")
                if (body.isEmpty()) return@Tool ToolResult.fail("body must not be empty")

                val baseUrl = jiraBaseUrl()
                if (baseUrl == null) return@Tool ToolResult.fail(
                    "Jira base URL not configured. Set JIRA_BASE_URL environment variable."
                )
                if (jiraAuthHeader == null) return@Tool ToolResult.fail(
                    "Jira credentials not configured. Set JIRA_EMAIL and JIRA_API_TOKEN environment variables."
                )

                // Jira comment body also uses ADF
                val adfBody = buildAdfDescription(body)
                val jsonBody = """{"body":$adfBody}"""

                val url = "${baseUrl.trimEnd('/')}/rest/api/3/issue/$issueKey/comment"
                val client = jiraHttpClient()
                val request = jiraRequestBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() in 200..299 || response.statusCode() == 201 -> {
                        val root = jiraJson.parseToJsonElement(response.body()).jsonObject
                        val commentId = root["id"]?.jsonPrimitive?.content ?: "?"
                        ToolResult.ok("Comment added successfully to $issueKey (comment ID: $commentId)")
                    }
                    response.statusCode() == 401 -> {
                        ToolResult.fail("Jira API returned 401: Unauthorized — check JIRA_EMAIL and JIRA_API_TOKEN")
                    }
                    response.statusCode() == 403 -> {
                        val bodyMsg = response.body().take(500)
                        ToolResult.fail("Jira API returned 403: Forbidden — $bodyMsg")
                    }
                    response.statusCode() == 404 -> {
                        ToolResult.fail("Jira API returned 404: Issue not found — $issueKey")
                    }
                    response.statusCode() == 400 -> {
                        val bodyMsg = response.body().take(500)
                        ToolResult.fail("Jira API returned 400: Bad request — $bodyMsg")
                    }
                    else -> {
                        ToolResult.fail("Jira API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("Jira API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to Jira API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("Failed to add Jira comment: ${e.message}")
            }
        },
    )
}

private fun jiraIssueTransitionTool(): Tool {
    return Tool(
        name = "jira_issue_transition",
        description = "Transition a Jira issue to a new status (e.g. 'In Progress', 'Done', 'To Do'). Requires JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN env vars.",
        parameters = listOf(
            ToolParameter("issue_key", "Issue key in PROJ-123 format", ToolParameterType.STRING),
            ToolParameter("transition_name", "Target transition/status name (e.g. 'In Progress', 'Done', 'To Do')", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val issueKey = (args["issue_key"] as? String)?.trim()?.uppercase()
                    ?: return@Tool ToolResult.fail("Missing required argument: issue_key")
                if (issueKey.isEmpty()) return@Tool ToolResult.fail("issue_key must not be empty")
                if (!issueKey.matches(Regex("^[A-Z]+-\\d+$"))) {
                    return@Tool ToolResult.fail("Invalid issue_key format. Expected format like PROJ-123")
                }

                val transitionName = (args["transition_name"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: transition_name")
                if (transitionName.isEmpty()) return@Tool ToolResult.fail("transition_name must not be empty")

                val baseUrl = jiraBaseUrl()
                if (baseUrl == null) return@Tool ToolResult.fail(
                    "Jira base URL not configured. Set JIRA_BASE_URL environment variable."
                )
                if (jiraAuthHeader == null) return@Tool ToolResult.fail(
                    "Jira credentials not configured. Set JIRA_EMAIL and JIRA_API_TOKEN environment variables."
                )

                val client = jiraHttpClient()

                // Step 1: Get available transitions and find the matching ID
                val getUrl = "${baseUrl.trimEnd('/')}/rest/api/3/issue/$issueKey/transitions"
                val getRequest = jiraRequestBuilder(URI.create(getUrl))
                    .GET()
                    .build()

                val getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString())
                if (getResponse.statusCode() !in 200..299) {
                    return@Tool ToolResult.fail(
                        "Failed to get transitions for $issueKey: HTTP ${getResponse.statusCode()} — ${getResponse.body().take(500)}"
                    )
                }

                val getRoot = jiraJson.parseToJsonElement(getResponse.body()).jsonObject
                val transitions = getRoot["transitions"]?.jsonArray ?: return@Tool ToolResult.fail(
                    "No transitions available for issue $issueKey"
                )

                val targetTransition = transitions.firstOrNull { transition ->
                    val name = (transition as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: ""
                    name.equals(transitionName, ignoreCase = true)
                }?.jsonObject

                if (targetTransition == null) {
                    val availableNames = transitions.mapNotNull { t ->
                        (t as? JsonObject)?.get("name")?.jsonPrimitive?.content
                    }
                    return@Tool ToolResult.fail(
                        "Transition '$transitionName' not found for issue $issueKey. Available transitions: ${availableNames.joinToString(", ")}"
                    )
                }

                val transitionId = targetTransition["id"]?.jsonPrimitive?.content
                    ?: return@Tool ToolResult.fail("Transition ID not found for '$transitionName'")

                // Step 2: Perform the transition
                val jsonBody = """{"transition":{"id":"$transitionId"}}"""
                val postUrl = "${baseUrl.trimEnd('/')}/rest/api/3/issue/$issueKey/transitions"
                val postRequest = jiraRequestBuilder(URI.create(postUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()

                val postResponse = client.send(postRequest, HttpResponse.BodyHandlers.ofString())
                when {
                    postResponse.statusCode() in 200..299 || postResponse.statusCode() == 204 -> {
                        ToolResult.ok("Issue $issueKey transitioned to '$transitionName'")
                    }
                    postResponse.statusCode() == 401 -> {
                        ToolResult.fail("Jira API returned 401: Unauthorized — check JIRA_EMAIL and JIRA_API_TOKEN")
                    }
                    postResponse.statusCode() == 403 -> {
                        val bodyMsg = postResponse.body().take(500)
                        ToolResult.fail("Jira API returned 403: Forbidden — $bodyMsg")
                    }
                    postResponse.statusCode() == 404 -> {
                        ToolResult.fail("Jira API returned 404: Issue not found — $issueKey")
                    }
                    postResponse.statusCode() == 400 -> {
                        val bodyMsg = postResponse.body().take(500)
                        ToolResult.fail("Jira API returned 400: Bad request — $bodyMsg")
                    }
                    else -> {
                        ToolResult.fail("Jira API returned HTTP ${postResponse.statusCode()}: ${postResponse.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("Jira API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to Jira API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("Failed to transition Jira issue: ${e.message}")
            }
        },
    )
}
