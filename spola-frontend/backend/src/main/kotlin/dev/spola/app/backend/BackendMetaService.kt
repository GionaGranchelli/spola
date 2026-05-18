package dev.spola.app.backend

import dev.spola.app.models.BackendMeta
import java.lang.management.ManagementFactory

class BackendMetaService(
    private val version: String = defaultVersion(),
    private val buildTime: String = System.getProperty("openclaw.buildTime") ?: "unknown",
) {
    fun current(): BackendMeta {
        return BackendMeta(
            version = version,
            buildTime = buildTime,
            pid = currentPid(),
        )
    }

    private fun currentPid(): Long {
        return runCatching { ProcessHandle.current().pid() }
            .getOrElse {
                ManagementFactory.getRuntimeMXBean().name.substringBefore("@").toLongOrNull() ?: -1L
            }
    }
}

private fun defaultVersion(): String {
    return System.getProperty("openclaw.version")
        ?.takeIf { it.isNotBlank() }
        ?: BackendMetaService::class.java.`package`?.implementationVersion
        ?: "dev"
}
