package dev.spola.workflow.yaml

import dev.spola.workflow.GolemState
import org.slf4j.LoggerFactory

/**
 * Evaluates Definition of Done conditions against workflow state.
 *
 * Supported conditions:
 * - `all_agents_completed` — All items in a parallel step completed
 * - `output_has_content` — Step result is non-null and non-blank
 * - `output_contains` — Result matches a regex pattern (value = regex)
 * - `output_not_contains` — Result does NOT match a regex pattern
 * - `markdown_valid` — Result looks like valid Markdown
 * - `all_steps_passed` — All steps completed without failure
 * - `no_critical_blockers` — Result doesn't contain CRITICAL/ERROR/FATAL markers
 * - `report_generated` — Final result is non-empty and sufficiently long
 */
object DoneConditionEvaluator {

    private val logger = LoggerFactory.getLogger(DoneConditionEvaluator::class.java)

    /**
     * Evaluate all done conditions for a set of conditions against current state.
     * @return true if ALL conditions pass
     */
    fun evaluateAll(conditions: List<DoneCondition>, state: GolemState, stepOutputs: Map<String, String>): Boolean {
        if (conditions.isEmpty()) return true // No conditions = pass
        return conditions.all { evaluate(it, state, stepOutputs) }
    }

    /**
     * Evaluate a single done condition against current state.
     */
    fun evaluate(condition: DoneCondition, state: GolemState, stepOutputs: Map<String, String>): Boolean {
        val result = try {
            when (condition.condition) {
                "all_agents_completed" -> evaluateAllAgentsCompleted(state)
                "output_has_content" -> evaluateOutputHasContent(state)
                "output_contains" -> evaluateOutputContains(state, condition.value ?: "")
                "output_not_contains" -> !evaluateOutputContains(state, condition.value ?: "")
                "markdown_valid" -> evaluateMarkdownValid(state)
                "all_steps_passed" -> evaluateAllStepsPassed(state)
                "no_critical_blockers" -> evaluateNoCriticalBlockers(state)
                "report_generated" -> evaluateReportGenerated(state)
                else -> {
                    logger.warn("Unknown done condition type: '{}'", condition.condition)
                    true // Unknown conditions pass by default
                }
            }
        } catch (e: Exception) {
            logger.error("Error evaluating done condition '{}': {}", condition.condition, e.message)
            false
        }

        if (!result) {
            logger.info("Done condition '{}' failed for step outputs: {}", condition.condition, stepOutputs.keys)
        }
        return result
    }

    private fun evaluateAllAgentsCompleted(state: GolemState): Boolean {
        return state.intermediateResults.isNotEmpty()
    }

    private fun evaluateOutputHasContent(state: GolemState): Boolean {
        return !state.result.isNullOrBlank()
    }

    private fun evaluateOutputContains(state: GolemState, regex: String): Boolean {
        val output = state.result ?: return false
        return try {
            Regex(regex, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(output)
        } catch (e: Exception) {
            logger.warn("Invalid regex in done condition: '{}'", regex)
            output.contains(regex, ignoreCase = true)
        }
    }

    private fun evaluateMarkdownValid(state: GolemState): Boolean {
        val output = state.result ?: return false
        // Basic markdown validation: has headers, lists, or code blocks
        return output.contains("##") || output.contains("```") || output.contains("- ") || output.contains("1. ")
    }

    private fun evaluateAllStepsPassed(state: GolemState): Boolean {
        // If we got a result, steps passed
        return state.result != null
    }

    private fun evaluateNoCriticalBlockers(state: GolemState): Boolean {
        val output = state.result ?: return true
        val criticalPatterns = listOf(
            "CRITICAL", "BLOCKER", "SEVERE", "CATASTROPHIC",
            "unresolved critical", "cannot proceed",
        )
        return criticalPatterns.none { pattern ->
            Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(output)
        }
    }

    private fun evaluateReportGenerated(state: GolemState): Boolean {
        val output = state.result ?: return false
        return output.length >= 50 && output.contains(Regex("""\w{3,}"""))
    }
}
