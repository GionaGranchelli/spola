package dev.spola.tools

import dev.spola.SpolaConfig
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import kotlinx.serialization.json.Json
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

private val issueLogger = LoggerFactory.getLogger("dev.spola.tools.IssueTools")
private val issueJson = Json { ignoreUnknownKeys = true }

/**
 * Register GitHub Issue tools: github_issue_search, github_issue_create,
 * github_issue_comment, and github_issue_transition.
 * These tools require the GITHUB_TOKEN environment variable.
 */
fun registerIssueTools(
    registry: ToolRegistry,
    config: SpolaConfig = SpolaConfig(),
) {
    registry.register(githubIssueSearchTool())
    registry.register(githubIssueCreateTool())
    registry.register(githubIssueCommentTool())
    registry.register(githubIssueTransitionTool())
}

private fun resolveGitHubToken(): String? {
    return System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
}

private fun gitHubHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private fun gitHubRequestBuilder(uri: URI): HttpRequest.Builder {
    val token = resolveGitHubToken()
    val builder = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/vnd.github.v3+json")
        .header("User-Agent", "Spola/1.0")
    if (token != null) {
        builder.header("Authorization", "Bearer $token")
    }
    return builder
}

private fun parseIssueKey(issueKey: String): Triple<String, String, Int>? {
    // Format: "owner/repo#123"
    val slashIndex = issueKey.indexOf('/')
    if (slashIndex == -1) return null
    val owner = issueKey.substring(0, slashIndex)
    val rest = issueKey.substring(slashIndex + 1)
    val hashIndex = rest.indexOf('#')
    if (hashIndex == -1) return null
    val repo = rest.substring(0, hashIndex)
    val numberStr = rest.substring(hashIndex + 1)
    val number = numberStr.toIntOrNull() ?: return null
    return Triple(owner, repo, number)
}

@Suppress("unused")
private fun githubIssueSearchTool(): Tool {
    return Tool(
        name = "github_issue_search",
        description = "Search for GitHub issues using the GitHub Issues API. Supports filtering by repository and limiting results.",
        parameters = listOf(
            ToolParameter("query", "Search query for GitHub issues", ToolParameterType.STRING),
            ToolParameter("repo", "Repository filter in owner/repo format (e.g. owner/repo)", ToolParameterType.STRING, required = false),
            ToolParameter("limit", "Maximum number of results (default: 10, max: 50)", ToolParameterType.INTEGER, required = false, defaultValue = 10),
        ),
        execute = { args ->
            try {
                val query = (args["query"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: query")
                if (query.isEmpty()) return@Tool ToolResult.fail("query must not be empty")

                val repo = (args["repo"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val limit = ((args["limit"] as? Int) ?: 10).coerceIn(1, 50)

                val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
                val repoFilter = if (repo != null) "+repo:${URLEncoder.encode(repo, StandardCharsets.UTF_8)}" else ""
                val url = "https://api.github.com/search/issues?q=${encodedQuery}${repoFilter}&per_page=$limit"

                val client = gitHubHttpClient()
                val request = gitHubRequestBuilder(URI.create(url))
                    .GET()
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() in 200..299 -> {
                        val root = issueJson.parseToJsonElement(response.body()).jsonObject
                        val items = root["items"]?.jsonArray ?: return@Tool ToolResult.ok("No issues found matching query: $query")

                        val formatted = items.mapNotNull { item ->
                            val obj = item.jsonObject
                            val number = obj["number"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val title = obj["title"]?.jsonPrimitive?.content ?: "(no title)"
                            val state = obj["state"]?.jsonPrimitive?.content ?: "unknown"
                            val labels = (obj["labels"] as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { label -> (label as? JsonObject)?.get("name")?.jsonPrimitive?.content }
                                ?.joinToString(", ") ?: ""
                            val createdAt = obj["created_at"]?.jsonPrimitive?.content ?: "unknown"
                            buildString {
                                appendLine("##$number - $title")
                                appendLine("State: $state")
                                if (labels.isNotBlank()) appendLine("Labels: $labels")
                                appendLine("Created: $createdAt")
                            }.trim()
                        }

                        if (formatted.isEmpty()) {
                            ToolResult.ok("No issues found matching query: $query")
                        } else {
                            ToolResult.ok(formatted.joinToString("\n\n"))
                        }
                    }
                    response.statusCode() == 401 -> {
                        ToolResult.fail("GitHub API returned 401: Unauthorized — check GITHUB_TOKEN")
                    }
                    response.statusCode() == 403 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("GitHub API returned 403: Rate limit exceeded — $body")
                    }
                    response.statusCode() == 422 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("GitHub API returned 422: Invalid query — $body")
                    }
                    else -> {
                        ToolResult.fail("GitHub API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("GitHub API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to GitHub API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("GitHub issue search failed: ${e.message}")
            }
        },
    )
}

private fun githubIssueCreateTool(): Tool {
    return Tool(
        name = "github_issue_create",
        description = "Create a new GitHub issue in a repository. Requires GITHUB_TOKEN env var.",
        parameters = listOf(
            ToolParameter("title", "Issue title", ToolParameterType.STRING),
            ToolParameter("body", "Issue body/description", ToolParameterType.STRING),
            ToolParameter("repo", "Repository in owner/repo format (e.g. owner/repo)", ToolParameterType.STRING),
            ToolParameter("labels", "Comma-separated list of labels", ToolParameterType.STRING, required = false),
            ToolParameter("assignee", "GitHub username to assign", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            try {
                val title = (args["title"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: title")
                if (title.isEmpty()) return@Tool ToolResult.fail("title must not be empty")

                val body = (args["body"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: body")

                val repo = (args["repo"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: repo")
                if (repo.isEmpty()) return@Tool ToolResult.fail("repo must not be empty")
                if (!repo.contains("/")) return@Tool ToolResult.fail("repo must be in owner/repo format (e.g. owner/repo)")

                val labels = (args["labels"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val assignee = (args["assignee"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

                val token = resolveGitHubToken()
                if (token == null) return@Tool ToolResult.fail(
                    "GitHub token not configured. Set GITHUB_TOKEN environment variable."
                )

                val jsonBody = buildString {
                    append("{\"title\":\"${escapeJson(title)}\",\"body\":\"${escapeJson(body)}\"")
                    if (labels != null) {
                        val labelArray = labels.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .joinToString(",") { "\"${escapeJson(it)}\"" }
                        append(",\"labels\":[$labelArray]")
                    }
                    if (assignee != null) {
                        append(",\"assignees\":[\"${escapeJson(assignee)}\"]")
                    }
                    append("}")
                }

                val url = "https://api.github.com/repos/$repo/issues"
                val client = gitHubHttpClient()
                val request = gitHubRequestBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() in 200..299 || response.statusCode() == 201 -> {
                        val root = issueJson.parseToJsonElement(response.body()).jsonObject
                        val issueNumber = root["number"]?.jsonPrimitive?.content ?: "?"
                        val issueUrl = root["html_url"]?.jsonPrimitive?.content ?: ""
                        ToolResult.ok("Issue created successfully: #$issueNumber at $issueUrl")
                    }
                    response.statusCode() == 401 -> {
                        ToolResult.fail("GitHub API returned 401: Unauthorized — check GITHUB_TOKEN")
                    }
                    response.statusCode() == 403 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("GitHub API returned 403: Rate limit exceeded — $body")
                    }
                    response.statusCode() == 404 -> {
                        ToolResult.fail("GitHub API returned 404: Repository not found — $repo")
                    }
                    response.statusCode() == 422 -> {
                        val body = response.body().take(500)
                        ToolResult.fail("GitHub API returned 422: Validation failed — $body")
                    }
                    else -> {
                        ToolResult.fail("GitHub API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("GitHub API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to GitHub API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("Failed to create GitHub issue: ${e.message}")
            }
        },
    )
}

private fun githubIssueCommentTool(): Tool {
    return Tool(
        name = "github_issue_comment",
        description = "Add a comment to an existing GitHub issue. Requires GITHUB_TOKEN env var.",
        parameters = listOf(
            ToolParameter("issue_key", "Issue key in owner/repo#number format (e.g. owner/repo#123)", ToolParameterType.STRING),
            ToolParameter("body", "Comment body text", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val issueKey = (args["issue_key"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: issue_key")
                if (issueKey.isEmpty()) return@Tool ToolResult.fail("issue_key must not be empty")

                val parsed = parseIssueKey(issueKey)
                if (parsed == null) {
                    return@Tool ToolResult.fail("Invalid issue_key format. Expected owner/repo#number (e.g. owner/repo#123)")
                }
                val (owner, repo, number) = parsed

                val body = (args["body"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: body")
                if (body.isEmpty()) return@Tool ToolResult.fail("body must not be empty")

                val token = resolveGitHubToken()
                if (token == null) return@Tool ToolResult.fail(
                    "GitHub token not configured. Set GITHUB_TOKEN environment variable."
                )

                val jsonBody = """{"body":"${escapeJson(body)}"}"""
                val url = "https://api.github.com/repos/$owner/$repo/issues/$number/comments"
                val client = gitHubHttpClient()
                val request = gitHubRequestBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() in 200..299 || response.statusCode() == 201 -> {
                        ToolResult.ok("Comment added successfully to $owner/$repo#$number")
                    }
                    response.statusCode() == 401 -> {
                        ToolResult.fail("GitHub API returned 401: Unauthorized — check GITHUB_TOKEN")
                    }
                    response.statusCode() == 403 -> {
                        val bodyMsg = response.body().take(500)
                        ToolResult.fail("GitHub API returned 403: Rate limit exceeded — $bodyMsg")
                    }
                    response.statusCode() == 404 -> {
                        ToolResult.fail("GitHub API returned 404: Issue or repository not found — $issueKey")
                    }
                    response.statusCode() == 422 -> {
                        val bodyMsg = response.body().take(500)
                        ToolResult.fail("GitHub API returned 422: Validation failed — $bodyMsg")
                    }
                    else -> {
                        ToolResult.fail("GitHub API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("GitHub API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to GitHub API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("Failed to add GitHub comment: ${e.message}")
            }
        },
    )
}

private fun githubIssueTransitionTool(): Tool {
    return Tool(
        name = "github_issue_transition",
        description = "Transition an existing GitHub issue to open or closed state. Requires GITHUB_TOKEN env var.",
        parameters = listOf(
            ToolParameter("issue_key", "Issue key in owner/repo#number format (e.g. owner/repo#123)", ToolParameterType.STRING),
            ToolParameter("status", "Target status: open or closed", ToolParameterType.ENUM, enumValues = listOf("open", "closed")),
        ),
        execute = { args ->
            try {
                val issueKey = (args["issue_key"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: issue_key")
                if (issueKey.isEmpty()) return@Tool ToolResult.fail("issue_key must not be empty")

                val parsed = parseIssueKey(issueKey)
                if (parsed == null) {
                    return@Tool ToolResult.fail("Invalid issue_key format. Expected owner/repo#number (e.g. owner/repo#123)")
                }
                val (owner, repo, number) = parsed

                val status = (args["status"] as? String)?.trim()?.lowercase()
                    ?: return@Tool ToolResult.fail("Missing required argument: status")
                if (status !in listOf("open", "closed")) {
                    return@Tool ToolResult.fail("Invalid status '$status'. Must be 'open' or 'closed'")
                }

                val token = resolveGitHubToken()
                if (token == null) return@Tool ToolResult.fail(
                    "GitHub token not configured. Set GITHUB_TOKEN environment variable."
                )

                val jsonBody = """{"state":"$status"}"""
                val url = "https://api.github.com/repos/$owner/$repo/issues/$number"
                val client = gitHubHttpClient()
                val request = gitHubRequestBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() in 200..299 -> {
                        ToolResult.ok("Issue $owner/$repo#$number transitioned to '$status'")
                    }
                    response.statusCode() == 401 -> {
                        ToolResult.fail("GitHub API returned 401: Unauthorized — check GITHUB_TOKEN")
                    }
                    response.statusCode() == 403 -> {
                        val bodyMsg = response.body().take(500)
                        ToolResult.fail("GitHub API returned 403: Rate limit exceeded — $bodyMsg")
                    }
                    response.statusCode() == 404 -> {
                        ToolResult.fail("GitHub API returned 404: Issue or repository not found — $issueKey")
                    }
                    response.statusCode() == 422 -> {
                        val bodyMsg = response.body().take(500)
                        ToolResult.fail("GitHub API returned 422: Validation failed — $bodyMsg")
                    }
                    else -> {
                        ToolResult.fail("GitHub API returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
                    }
                }
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolResult.fail("GitHub API request timed out: ${e.message}")
            } catch (e: java.net.ConnectException) {
                ToolResult.fail("Could not connect to GitHub API: ${e.message}")
            } catch (e: Exception) {
                ToolResult.fail("Failed to transition GitHub issue: ${e.message}")
            }
        },
    )
}

/**
 * Escape special characters in a JSON string value.
 */
private fun escapeJson(s: String): String {
    return s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
