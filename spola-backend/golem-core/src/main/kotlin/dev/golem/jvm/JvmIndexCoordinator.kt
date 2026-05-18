package dev.spola.jvm

import java.io.Closeable
import java.nio.file.Path

class JvmIndexCoordinator(
    private val freshnessPolicy: IndexFreshnessPolicy = IndexFreshnessPolicy(),
    private val fileWatcher: JvmFileWatcher = JvmFileWatcher(),
    private val autoRefresh: Boolean = true,
    private val projectDirProvider: () -> String = { System.getProperty("user.dir") },
) : Closeable {

    @Volatile
    private var watcherStartedFor: Path? = null

    suspend fun ensureFresh(queryType: String, index: SqliteJvmProjectIndex): JvmProjectSnapshot {
        val snapshot = index.getSnapshot()
        if (snapshot == null) {
            val full = index.scan(projectDirProvider())
            startWatching(Path.of(full.projectDir))
            return full
        }

        startWatching(Path.of(snapshot.projectDir))
        val policy = freshnessPolicy.forQueryType(queryType)
        val stale = policy.isStale(snapshot.scannedAt)
        if (!stale) return snapshot

        if (!autoRefresh) {
            val full = index.scan(snapshot.projectDir)
            startWatching(Path.of(full.projectDir))
            return full
        }

        val changedPaths = fileWatcher.takeChangedPaths()
        if (changedPaths.isEmpty()) {
            if (policy.preferReindex) {
                val full = index.scan(snapshot.projectDir)
                startWatching(Path.of(full.projectDir))
                return full
            }
            return snapshot
        }

        val refreshed = index.refreshChangedPaths(snapshot.projectDir, changedPaths)
        startWatching(Path.of(refreshed.projectDir))
        return refreshed
    }

    fun startWatching(projectDir: Path) {
        if (!autoRefresh) return
        val normalized = projectDir.toAbsolutePath().normalize()
        if (watcherStartedFor == normalized) return
        fileWatcher.start(normalized)
        watcherStartedFor = normalized
    }

    override fun close() {
        fileWatcher.close()
    }
}
