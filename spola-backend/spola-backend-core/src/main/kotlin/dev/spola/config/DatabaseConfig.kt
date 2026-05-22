package dev.spola.config

import dev.spola.PostgresConfig

data class DatabaseConfig(
    val dbPath: String = "./.spola/spola.db",
    val memoryDbPath: String = "./.spola/memory.db",
    val checkpointDbPath: String = "./.spola/checkpoint.db",
    val schedulerDbPath: String = "./.spola/scheduler.db",
    val kanbanDbPath: String = "./.spola/kanban.db",
    val workflowsDbPath: String = "./.spola/workflows.db",
    val jvmIndexDbPath: String = "./.spola/jvm-index.db",
    val sessionsDbPath: String = "./.spola/sessions.db",
    val agentsDbPath: String = "./.spola/agents.db",
    val skillsDbPath: String = "./.spola/skills.db",
    val postgres: PostgresConfig = PostgresConfig(),
)
