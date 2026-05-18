package dev.spola

/**
 * Run Golem in one-shot mode: process a single goal and print the result.
 */
suspend fun runOneShot(
    goal: String,
    config: GolemConfig = GolemConfig(),
    onOutput: (String) -> Unit = { println(it) },
) {
    val instance = GolemFactory.create(config = config)
    try {
        val result = instance.run(goal)
        onOutput(result)
    } finally {
        instance.close()
    }
}
