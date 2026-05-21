package dev.spola.workflow.yaml

/**
 * Topologically sorts workflow steps by their [ResolvedStep.dependsOn] dependencies
 * using Kahn's algorithm.
 *
 * Steps with no dependencies (root steps) preserve their declaration order (stable sort).
 * Cycles are detected and reported with a descriptive error.
 */
object YamlWorkflowDagSorter {

    /**
     * Sort steps topologically by their `dependsOn` dependencies.
     *
     * @param steps The list of steps to sort (unordered)
     * @return Steps sorted so that every step appears after all steps it depends on
     * @throws IllegalStateException if a dependency cycle is detected
     * @throws IllegalArgumentException if a step's `dependsOn` references a non-existent step ID,
     *         or if duplicate step IDs exist
     */
    fun sort(steps: List<ResolvedStep>): List<ResolvedStep> {
        if (steps.isEmpty()) return emptyList()

        // Validate duplicate IDs
        val ids = steps.map { it.id }
        val duplicateIds = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            throw IllegalArgumentException(
                "Duplicate step IDs found: ${duplicateIds.joinToString(", ")}"
            )
        }

        val idSet = ids.toSet()

        // Build adjacency list and in-degree map
        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()

        for (step in steps) {
            inDegree.putIfAbsent(step.id, 0)
            adjacency.putIfAbsent(step.id, mutableListOf())

            val deps = step.dependsOn
            if (deps != null) {
                for (dep in deps) {
                    // Validate dependency exists
                    if (dep !in idSet) {
                        throw IllegalArgumentException(
                            "Step '${step.id}' depends on '${dep}' which does not exist"
                        )
                    }
                    adjacency.getOrPut(dep) { mutableListOf() }.add(step.id)
                    inDegree[step.id] = (inDegree[step.id] ?: 0) + 1
                }
            }
        }

        // Kahn's algorithm: start with root steps (in-degree = 0)
        // Preserve original declaration order for stable sort
        val queue = ArrayDeque<String>()
        for (step in steps) {
            if ((inDegree[step.id] ?: 0) == 0) {
                queue.addLast(step.id)
            }
        }

        val sortedIds = mutableListOf<String>()
        val stepById = steps.associateBy { it.id }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sortedIds.add(current)

            for (neighbor in adjacency[current].orEmpty()) {
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) {
                    queue.addLast(neighbor)
                }
            }
        }

        // Cycle detection
        if (sortedIds.size != steps.size) {
            val unprocessed = steps.map { it.id }.toSet() - sortedIds.toSet()
            throw IllegalStateException(
                "Circular dependency detected involving steps: ${unprocessed.joinToString(", ")}"
            )
        }

        return sortedIds.mapNotNull { stepById[it] }
    }
}
