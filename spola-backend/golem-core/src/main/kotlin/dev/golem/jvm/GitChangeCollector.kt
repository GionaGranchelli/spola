package dev.spola.jvm

import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class ChangeType {
    ADDED,
    MODIFIED,
    DELETED,
}

data class ChangedFile(
    val path: String,
    val changeType: ChangeType,
    val summary: String? = null,
)

class GitChangeCollector(
    private val workdir: Path = Path.of(System.getProperty("user.dir")),
) {
    fun getChangedFiles(): List<ChangedFile> {
        // Try HEAD diff first (committed vs working tree)
        val nameStatus = runGit(listOf("diff", "--name-status", "HEAD"))
        // If no HEAD (empty repo), fall back to staged-only + unstaged
        val lines = if (nameStatus.isNotBlank()) {
            nameStatus.lines()
        } else {
            val staged = runGit(listOf("diff", "--cached", "--name-status"))
            val unstaged = runGit(listOf("diff", "--name-status"))
            (staged.lines() + unstaged.lines()).filter { it.isNotBlank() }
        }
        val summaries = parseStat(runGit(listOf("diff", "--stat", "HEAD")).ifBlank {
            // No HEAD — empty repo, no stat possible
            ""
        })
        return lines
            .mapNotNull { line ->
                val parts = line.split(Regex("""\s+"""), limit = 3)
                if (parts.size < 2) return@mapNotNull null
                val path = parts.last().trim()
                ChangedFile(path, parts.first().toChangeType(), summaries[path])
            }
            .distinctBy { it.path }
    }

    fun getChangedSymbols(symbolIndex: SqliteSymbolIndex): List<SymbolLocation> {
        return getChangedFiles().flatMap { changed ->
            runCatching { kotlinx.coroutines.runBlocking { symbolIndex.searchByFile(changed.path) } }.getOrDefault(emptyList())
        }.distinctBy { "${it.module}:${it.file}:${it.line}:${it.name}" }
    }

    private fun parseStat(output: String): Map<String, String> =
        output.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.contains("|")) return@mapNotNull null
            val path = trimmed.substringBefore("|").trim()
            path to trimmed.substringAfter("|").trim()
        }.toMap()

    private fun String.toChangeType(): ChangeType = when {
        startsWith("A") -> ChangeType.ADDED
        startsWith("D") -> ChangeType.DELETED
        else -> ChangeType.MODIFIED
    }

    private fun runGit(args: List<String>): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workdir.toFile())
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ""
        }
        return if (process.exitValue() == 0) process.inputStream.bufferedReader().readText().trim() else ""
    }
}
