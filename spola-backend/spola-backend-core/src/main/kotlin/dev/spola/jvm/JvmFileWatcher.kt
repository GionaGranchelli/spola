package dev.spola.jvm

import java.io.Closeable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.name

class JvmFileWatcher(
    private val debounceMs: Long = 300L,
) : Closeable {
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val keys = ConcurrentHashMap<WatchKey, Path>()
    private val pendingChanges = linkedSetOf<Path>()
    private var projectRoot: Path? = null
    private var lastEventAt: Long = 0L
    private var started = false

    @Synchronized
    fun start(projectDir: Path) {
        val normalizedRoot = projectDir.toAbsolutePath().normalize()
        if (started && normalizedRoot == projectRoot) return
        reset()
        projectRoot = normalizedRoot
        registerRecursively(normalizedRoot)
        started = true
    }

    @Synchronized
    fun takeChangedPaths(): List<Path> {
        if (!started) return emptyList()
        drainWatchEvents(waitForEvent = pendingChanges.isEmpty())
        if (pendingChanges.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        if (now - lastEventAt < debounceMs) return emptyList()
        val changes = pendingChanges.toList()
        pendingChanges.clear()
        return changes
    }

    private fun drainWatchEvents(waitForEvent: Boolean) {
        var first = true
        while (true) {
            val key = if (first && waitForEvent) {
                first = false
                watchService.poll(debounceMs, TimeUnit.MILLISECONDS)
            } else {
                watchService.poll()
            } ?: break
            val dir = keys[key]
            if (dir != null) {
                key.pollEvents().forEach { event ->
                    val kind = event.kind()
                    if (kind == StandardWatchEventKinds.OVERFLOW) return@forEach
                    @Suppress("UNCHECKED_CAST")
                    val relative = (event as WatchEvent<Path>).context()
                    val child = dir.resolve(relative).normalize()
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                        registerRecursively(child)
                    }
                    if (shouldTrack(child)) {
                        pendingChanges.add(child)
                        lastEventAt = System.currentTimeMillis()
                    }
                }
            }
            if (!key.reset()) {
                keys.remove(key)
            }
        }
    }

    private fun shouldTrack(path: Path): Boolean {
        if (Files.isDirectory(path)) return false
        val fileName = path.fileName?.toString() ?: return false
        return fileName == "build.gradle.kts" ||
            fileName == "build.gradle" ||
            path.extension == "kt" ||
            path.extension == "java"
    }

    private fun registerRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.filter { Files.isDirectory(it) && shouldWatchDirectory(it) }
                .forEach { dir ->
                    val key = dir.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                    )
                    keys[key] = dir
                }
        }
    }

    private fun shouldWatchDirectory(path: Path): Boolean {
        val name = path.name
        return name != ".git" && name != "build" && name != ".gradle"
    }

    @Synchronized
    private fun reset() {
        keys.keys.forEach { it.cancel() }
        keys.clear()
        pendingChanges.clear()
        lastEventAt = 0L
        started = false
    }

    override fun close() {
        reset()
        watchService.close()
    }
}
