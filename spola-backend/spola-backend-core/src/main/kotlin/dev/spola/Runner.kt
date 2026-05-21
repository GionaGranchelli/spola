package dev.spola

/**
 * Run Spola in one-shot mode: process a single goal and print the result.
 */
suspend fun runOneShot(
    goal: String,
    config: SpolaConfig = SpolaConfig(),
    onOutput: (String) -> Unit = { println(it) },
) {
    val instance = SpolaFactory.create(config = config)
    try {
        val result = instance.run(goal)
        onOutput(result)
    } finally {
        instance.close()
    }
}
