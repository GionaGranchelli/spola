package dev.spola.workflow.examples

import dev.tramai.orchestration.GateDecision
import dev.tramai.orchestration.McpStepConfig
import dev.tramai.orchestration.McpToolCall
import dev.tramai.orchestration.McpToolCallDefinition
import dev.tramai.orchestration.McpToolResult
import dev.tramai.orchestration.ReplayPolicy
import dev.tramai.orchestration.ShellCommand
import dev.tramai.orchestration.ShellCommandDefinition
import dev.tramai.orchestration.ShellResult
import dev.tramai.orchestration.ShellStepConfig
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.workflow
import kotlinx.serialization.Serializable

// ────────────────────────────────────────────────────────────────────────────────
// Sealed-State Workflow Examples
// ────────────────────────────────────────────────────────────────────────────────
//
// These examples demonstrate TramAI's sealed-class compile-time state validation.
// In contrast to LangGraph's string-keyed state (where typos cause silent runtime
// errors), Kotlin's sealed classes make the compiler enforce that every step
// transforms the state to a valid variant of the sealed type.
//
// Key advantage: the compiler rejects mismatched state transitions at build time.
// In LangGraph, a typo like "appproved" instead of "approved" is a runtime bug.
//
// Each example is a self-contained function that returns a Workflow<S, R>.
// ────────────────────────────────────────────────────────────────────────────────

// ════════════════════════════════════════════════════════════════════════════════
// Example 1: Code Review Workflow
// ════════════════════════════════════════════════════════════════════════════════
//
// A complete code review pipeline:
//   1. Fetch files from a PR
//   2. AI code review
//   3. Human gate (approval check)
//   4. Branch: approved → shell (apply fixes) | rejected → terminal
// ════════════════════════════════════════════════════════════════════════════════

/** Per-file AI review result. */
@Serializable
data class ReviewResult(
    val score: Int,
    val issues: List<String>,
    val summary: String,
)

/**
 * Compile-time validated state for a code review workflow.
 *
 * Each state variant carries only the data relevant to its stage. The compiler
 * enforces that workflow steps can only transition between valid state pairs.
 * LangGraph equivalents would use string keys like "INIT" → "CODE_FETCHED" →
 * "AI_REVIEWED" → "APPROVED"/"REJECTED" — a typo like "CODE_FETCHD" compiles
 * silently and fails at runtime.
 */
sealed class ReviewState {
    /** Workflow has not started fetching code. */
    object Init : ReviewState()

    /** Files have been fetched and are ready for review. */
    data class CodeFetched(val files: List<String>) : ReviewState()

    /** AI has finished reviewing all files. */
    data class AiReviewed(val results: Map<String, ReviewResult>) : ReviewState()

    /** Human approved the review — ready to apply fixes. */
    data class Approved(val summary: String) : ReviewState()

    /** Human rejected the review with a reason. */
    data class Rejected(val reason: String) : ReviewState()
}

/**
 * Builds a complete code review workflow.
 *
 * Step sequence:
 * - [localStep] "fetch-files": simulates fetching changed files from a PR
 * - [aiStep] "review-code": sends files to an LLM for review
 * - [gateStep] "human-approval": gate that allows or rejects (state stays AiReviewed)
 * - [localStep] "record-approval": transitions AiReviewed → Approved for the branch step
 * - [branchStep] "route-decision": routes to approve or reject path:
 *   - "approved" → [shellStep] "apply-fixes" runs formatting/linting fixes
 *   - "rejected" → workflow terminates with reason
 */
fun createReviewWorkflow(): Workflow<ReviewState, String> = workflow<ReviewState>(
    name = "code-review",
    definitionVersion = "1",
) {
    localStep(name = "fetch-files") { state, _ ->
        // Simulate fetching changed files from a pull request
        val files = listOf(
            "src/main/kotlin/Service.kt",
            "src/main/kotlin/Controller.kt",
            "src/test/kotlin/ServiceTest.kt",
        )
        ReviewState.CodeFetched(files = files)
    }

    aiStep(
        name = "review-code",
        replayPolicy = ReplayPolicy.IDEMPOTENT,
        input = { state: ReviewState, _ ->
            val fetched = state as ReviewState.CodeFetched
            fetched.files
        },
        invoke = { files: List<String>, _ ->
            // Simulated AI review — in production this calls an LLM
            files.associateWith { file ->
                ReviewResult(
                    score = 85,
                    issues = listOf("Consider adding null-safety annotations"),
                    summary = "Reviewed $file: looks good with minor suggestions",
                )
            }
        },
        merge = { state: ReviewState, results: Map<String, ReviewResult>, _ ->
            ReviewState.AiReviewed(results = results)
        },
    )

    gateStep(name = "human-approval") { state: ReviewState, _ ->
        val reviewed = state as ReviewState.AiReviewed
        // Simulated human gate — in production this pauses for a real human
        val allScores = reviewed.results.values.map { it.score }
        val averageScore = allScores.average()
        if (averageScore >= 70.0) {
            GateDecision.allow()
        } else {
            GateDecision.reject("Average score $averageScore is below the 70.0 threshold")
        }
    }

    // Transition AiReviewed → Approved — gateStep does NOT change the state, so we
    // need a localStep to produce the Approved variant expected by the branchStep.
    localStep(name = "record-approval") { state: ReviewState, ctx ->
        val reviewed = state as ReviewState.AiReviewed
        ReviewState.Approved(
            summary = "Approved by human gate (average score: ${reviewed.results.values.map { it.score }.average()})",
        )
    }

    branchStep(name = "route-decision", select = { state ->
        // The compiler only allows ReviewState variants that are valid here:
        // AiReviewed is consumed, Approved or Rejected is produced.
        when (state) {
            is ReviewState.Approved -> "approved"
            is ReviewState.Rejected -> "rejected"
            else -> error("Unexpected state: $state")
        }
    }) {
        branch(key = "approved") {
            shellStep(
                name = "apply-fixes",
                config = ShellStepConfig(
                    timeoutSeconds = 30L,
                    failOnNonZeroExit = true,
                    allowedCommands = setOf("ktlint", "git"),
                ),
                definition = ShellCommandDefinition(
                    executable = "ktlint",
                    hasWorkdir = true,
                ),
                command = { state: ReviewState, _ ->
                    val approved = state as ReviewState.Approved
                    ShellCommand(
                        command = listOf("ktlint", "--format"),
                        workdir = "/home/gionag/Development/spola/spola-software",
                    )
                },
                merge = { state: ReviewState, result: ShellResult, _ ->
                    ReviewState.Approved(
                        summary = "${(state as ReviewState.Approved).summary}\nFixes applied:\n${result.stdout}",
                    )
                },
            )
        }
        default {
            localStep(name = "log-rejection") { state: ReviewState, _ ->
                val rejected = state as ReviewState.Rejected
                println("Review rejected: ${rejected.reason}")
                rejected
            }
        }
    }
}.build { state: ReviewState ->
    when (state) {
        is ReviewState.Approved -> "Approved: ${state.summary}"
        is ReviewState.Rejected -> "Rejected: ${state.reason}"
        else -> error("Workflow ended in unexpected terminal state: $state")
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// Example 2: RAG Pipeline Workflow
// ════════════════════════════════════════════════════════════════════════════════
//
// A Retrieval-Augmented Generation pipeline:
//   1. Load documents from a source
//   2. AI summarizes each document
//   3. MCP step queries a vector store for relevant context
//   4. AI generates the final response using the retrieved context
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Compile-time validated state for a RAG pipeline.
 *
 * Each stage carries precisely the data needed:
 * - [Init]: empty, ready to load
 * - [DocumentsLoaded]: raw document text available
 * - [Embedded]: documents have been summarized/embedded
 * - [Retrieved]: vector store returned relevant context
 * - [Responded]: final answer produced
 */
sealed class RagState {
    object Init : RagState()

    data class DocumentsLoaded(val documents: List<String>) : RagState()

    data class Embedded(val summaries: Map<String, String>) : RagState()

    data class Retrieved(val context: String) : RagState()

    data class Responded(val answer: String) : RagState()
}

/**
 * Builds a complete RAG pipeline workflow.
 *
 * Step sequence:
 * - [localStep] "load-documents": loads raw text documents
 * - [aiStep] "summarize-documents": LLM generates summaries for each document
 * - [mcpStep] "query-vector-store": MCP tool call to retrieve relevant context
 * - [aiStep] "generate-response": LLM produces final answer using context
 */
fun createRagWorkflow(): Workflow<RagState, String> = workflow<RagState>(
    name = "rag-pipeline",
    definitionVersion = "1",
) {
    localStep(name = "load-documents") { _, _ ->
        RagState.DocumentsLoaded(
            documents = listOf(
                "Document 1: Kotlin sealed classes provide exhaustive when checking...",
                "Document 2: TramAI workflows support type-safe state transitions...",
            ),
        )
    }

    aiStep(
        name = "summarize-documents",
        input = { state: RagState, _ ->
            val loaded = state as RagState.DocumentsLoaded
            loaded.documents
        },
        invoke = { docs: List<String>, _ ->
            docs.associateWith { doc ->
                "Summary: ${doc.take(60)}..."
            }
        },
        merge = { _: RagState, summaries: Map<String, String>, _ ->
            RagState.Embedded(summaries = summaries)
        },
    )

    mcpStep(
        name = "query-vector-store",
        config = McpStepConfig(
            timeoutSeconds = 30L,
            toolAllowlist = setOf("query_documents"),
        ),
        definition = McpToolCallDefinition(
            serverCommand = listOf("python3", "-m", "vectordb_mcp_server"),
            serverEnv = mapOf("VECTOR_DB_PATH" to "/data/vectors"),
            toolName = "query_documents",
            argumentKeys = setOf("query"),
        ),
        toolCall = { state: RagState, _ ->
            val embedded = state as RagState.Embedded
            val query = embedded.summaries.values.joinToString(" ") { it }
            McpToolCall(
                serverCommand = listOf("python3", "-m", "vectordb_mcp_server"),
                serverEnv = mapOf("VECTOR_DB_PATH" to "/data/vectors"),
                toolName = "query_documents",
                arguments = mapOf("query" to query),
            )
        },
        merge = { state: RagState, result: McpToolResult, _ ->
            val context = result.content ?: "No relevant context found"
            RagState.Retrieved(context = context)
        },
    )

    aiStep(
        name = "generate-response",
        input = { state: RagState, _ ->
            val retrieved = state as RagState.Retrieved
            retrieved.context
        },
        invoke = { context: String, _ ->
            "Based on the retrieved context: $context\n\nAnswer: Sealed classes enable " +
                "exhaustive when expressions at compile time, eliminating the runtime " +
                "errors common in LangGraph's string-keyed approach."
        },
        merge = { _: RagState, answer: String, _ ->
            RagState.Responded(answer = answer)
        },
    )
}.build { state: RagState ->
    when (state) {
        is RagState.Responded -> state.answer
        else -> error("RAG pipeline did not produce a final response (state: $state)")
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// Example 3: Multi-step Approval Workflow
// ════════════════════════════════════════════════════════════════════════════════
//
// A multi-signer approval chain with escalation:
//   1. Prepare proposal data
//   2. AI generates the proposal document
//   3. Gate: manager reviews and approves
//   4. Gate: director reviews and approves
//   5. Local step: execute approved proposal
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Example domain value object for a business proposal.
 */
@Serializable
data class Proposal(
    val title: String,
    val amount: Double,
    val description: String,
)

/**
 * Compile-time validated state for a multi-signer approval chain.
 *
 * The sealed hierarchy ensures that a proposal cannot skip the manager gate
 * and go directly to the director — the compiler tracks the exact stage.
 * In LangGraph, this would be a string like "manager_reviewed" and nothing
 * prevents a buggy step from jumping to "director_reviewed" without actually
 * passing through the manager gate step.
 */
sealed class ApprovalState {
    object Init : ApprovalState()

    data class ProposalReady(val data: Map<String, Any?>) : ApprovalState()

    data class AiGenerated(val proposal: Proposal) : ApprovalState()

    data class ManagerReviewed(val proposal: Proposal, val comments: String) : ApprovalState()

    data class DirectorReviewed(val proposal: Proposal) : ApprovalState()

    data class Executed(val result: String) : ApprovalState()
}

/**
 * Builds a complete multi-step approval workflow.
 *
 * Step sequence:
 * - [localStep] "prepare-proposal": assembles raw proposal data
 * - [aiStep] "generate-proposal": LLM produces a structured proposal
 * - [gateStep] "manager-review": manager gate (first approval)
 * - [gateStep] "director-review": director gate (escalated approval)
 * - [localStep] "execute-proposal": executes the approved proposal
 */
fun createApprovalWorkflow(): Workflow<ApprovalState, String> = workflow<ApprovalState>(
    name = "multi-signer-approval",
    definitionVersion = "1",
) {
    localStep(name = "prepare-proposal") { _, _ ->
        ApprovalState.ProposalReady(
            data = mapOf(
                "title" to "Q3 Infrastructure Upgrade",
                "amount" to 50000.0,
                "team" to listOf("Platform Engineering"),
            ),
        )
    }

    aiStep(
        name = "generate-proposal",
        input = { state: ApprovalState, _ ->
            val ready = state as ApprovalState.ProposalReady
            @Suppress("UNCHECKED_CAST")
            ready.data["amount"] as Double
        },
        invoke = { amount: Double, _ ->
            Proposal(
                title = "Q3 Infrastructure Upgrade",
                amount = amount,
                description = "Upgrade CI/CD pipelines and migrate to JDK 21",
            )
        },
        merge = { _: ApprovalState, proposal: Proposal, _ ->
            ApprovalState.AiGenerated(proposal = proposal)
        },
    )

    gateStep(name = "manager-review") { state: ApprovalState, _ ->
        val generated = state as ApprovalState.AiGenerated
        // Simulated manager review gate
        if (generated.proposal.amount <= 100000.0) {
            GateDecision.allow()
        } else {
            GateDecision.reject(
                "Amount ${generated.proposal.amount} exceeds manager approval limit of \$100,000",
            )
        }
    }

    // Transition to ManagerReviewed — local step to attach reviewer comments
    localStep(name = "attach-manager-comments") { state: ApprovalState, _ ->
        val generated = state as ApprovalState.AiGenerated
        ApprovalState.ManagerReviewed(
            proposal = generated.proposal,
            comments = "Approved by manager — escalating to director for final sign-off",
        )
    }

    gateStep(name = "director-review") { state: ApprovalState, _ ->
        val managerReviewed = state as ApprovalState.ManagerReviewed
        // Simulated director review gate
        if (managerReviewed.proposal.amount <= 500000.0) {
            GateDecision.allow()
        } else {
            GateDecision.reject(
                "Amount ${managerReviewed.proposal.amount} exceeds director approval limit of \$500,000",
            )
        }
    }

    // Transition to DirectorReviewed — local step to apply director approval
    localStep(name = "finalize-director-approval") { state: ApprovalState, _ ->
        val managerReviewed = state as ApprovalState.ManagerReviewed
        ApprovalState.DirectorReviewed(proposal = managerReviewed.proposal)
    }

    localStep(name = "execute-proposal") { state: ApprovalState, _ ->
        val directorReviewed = state as ApprovalState.DirectorReviewed
        ApprovalState.Executed(
            result = "Proposal '${directorReviewed.proposal.title}' executed successfully " +
                "for \$${directorReviewed.proposal.amount}",
        )
    }
}.build { state: ApprovalState ->
    when (state) {
        is ApprovalState.Executed -> state.result
        else -> error("Approval workflow did not complete execution (state: $state)")
    }
}
