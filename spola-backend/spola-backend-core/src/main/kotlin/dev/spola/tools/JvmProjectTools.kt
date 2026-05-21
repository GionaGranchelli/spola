package dev.spola.tools

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.compression.TokenJuice
import dev.spola.jvm.GitChangeCollector
import dev.spola.jvm.GradleFailureParser
import dev.spola.jvm.GradleTaskCataloger
import dev.spola.jvm.ImpactAnalyzer
import dev.spola.jvm.IndexFreshnessPolicy
import dev.spola.jvm.JavaSymbolExtractor
import dev.spola.jvm.JvmProjectIndex
import dev.spola.jvm.JvmFailureExplainer
import dev.spola.jvm.JvmIndexCoordinator
import dev.spola.jvm.JvmPatchPreflight
import dev.spola.jvm.JvmProjectSnapshot
import dev.spola.jvm.KotlinSymbolExtractor
import dev.spola.jvm.ModuleDependencyGraph
import dev.spola.jvm.ProjectModule
import dev.spola.jvm.SqliteDependencyCache
import dev.spola.jvm.SqliteJvmProjectIndex
import dev.spola.jvm.SymbolKind
import dev.spola.jvm.SymbolLocation
import dev.spola.jvm.TestSelectionEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

fun registerJvmTools(
    registry: ToolRegistry,
    index: JvmProjectIndex,
    coordinator: JvmIndexCoordinator? = null,
) {
    registry.register(Tool(
        name = "jvm_project_overview",
        description = "Inspect the current Gradle JVM project: modules, source roots, plugins, and Java/Kotlin versions.",
        parameters = emptyList(),
        execute = {
            try {
                val snapshot = ensureSnapshot(index, coordinator, IndexFreshnessPolicy.QUERY_PROJECT_OVERVIEW)
                ToolResult.ok(formatOverview(snapshot))
            } catch (e: Exception) {
                ToolResult.fail("Failed to inspect JVM project: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "jvm_symbol_search",
        description = "Search Kotlin and Java symbols by name, optionally filtered by kind and Gradle module.",
        parameters = listOf(
            ToolParameter("name", "Symbol name or substring to search for", ToolParameterType.STRING),
            ToolParameter("kind", "Optional symbol kind: CLASS, INTERFACE, OBJECT, FUN, VAL, VAR, ENUM, ANNOTATION", ToolParameterType.STRING, required = false),
            ToolParameter("module", "Optional Gradle module name, e.g. :spola-backend-core", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            try {
                val name = (args["name"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: name")
                if (name.isBlank()) return@Tool ToolResult.fail("Symbol name must not be blank")
                val kind = (args["kind"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let {
                    runCatching { SymbolKind.valueOf(it.uppercase()) }
                        .getOrElse { return@Tool ToolResult.fail("Invalid symbol kind: $it") }
                }
                val module = (args["module"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                val snapshot = ensureSnapshot(index, coordinator, IndexFreshnessPolicy.QUERY_SYMBOL_SEARCH)
                val symbols = if (index is SqliteJvmProjectIndex) {
                    index.searchSymbols(name, kind, module)
                } else {
                    extractSymbols(snapshot).filter { symbol ->
                        symbol.name.contains(name, ignoreCase = true) &&
                            (kind == null || symbol.kind == kind) &&
                            (module == null || symbol.module == module)
                    }
                }
                ToolResult.ok(formatSymbols(symbols))
            } catch (e: Exception) {
                ToolResult.fail("Failed to search JVM symbols: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "jvm_file_outline",
        description = "Return the Kotlin or Java symbols declared in a source file.",
        parameters = listOf(
            ToolParameter("path", "Source file path, absolute or relative to the project working directory", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val path = (args["path"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: path")
                val snapshot = ensureSnapshot(index, coordinator, IndexFreshnessPolicy.QUERY_FILE_OUTLINE)
                val symbols = outlineFile(snapshot, index, path)
                ToolResult.ok(formatSymbols(symbols))
            } catch (e: Exception) {
                ToolResult.fail("Failed to outline JVM file: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "jvm_context_pack",
        description = "Build a compact JVM project context summary focused by optional goal keywords.",
        parameters = listOf(
            ToolParameter("goal", "Optional keywords or implementation goal to focus symbol and build context", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            try {
                val goal = (args["goal"] as? String)?.trim().orEmpty()
                val snapshot = ensureSnapshot(index, coordinator, IndexFreshnessPolicy.QUERY_CONTEXT_PACK)
                val symbols = if (index is SqliteJvmProjectIndex) {
                    if (goal.isBlank()) snapshot.modules.flatMap { index.symbols.searchByModule(it.name) }.take(80)
                    else goalKeywords(goal).flatMap { index.searchSymbols(it) }.distinctBy { "${it.module}:${it.file}:${it.line}:${it.name}" }.take(80)
                } else {
                    extractSymbols(snapshot).filterByGoal(goal).take(80)
                }
                val raw = buildString {
                    appendLine("JVM project: ${snapshot.projectDir}")
                    appendLine("Modules:")
                    snapshot.modules.forEach { module ->
                        appendLine("- ${module.name} path=${module.path}")
                        if (module.plugins.isNotEmpty()) appendLine("  plugins=${module.plugins.joinToString(", ")}")
                        val versions = listOfNotNull(module.javaVersion?.let { "java=$it" }, module.kotlinVersion?.let { "kotlin=$it" })
                        if (versions.isNotEmpty()) appendLine("  ${versions.joinToString(", ")}")
                        if (module.sourceDirs.isNotEmpty()) appendLine("  src=${module.sourceDirs.joinToString(", ")}")
                        if (module.testDirs.isNotEmpty()) appendLine("  test=${module.testDirs.joinToString(", ")}")
                        module.dependencies.filterDependenciesByGoal(goal).take(5).forEach { appendLine("  dep=$it") }
                    }
                    appendLine("Symbols:")
                    symbols.forEach { appendLine("- ${it.module} ${it.kind} ${it.name} ${it.file}:${it.line}") }
                }
                ToolResult.ok(TokenJuice.compact("jvm_context_pack", raw).take(2000))
            } catch (e: Exception) {
                ToolResult.fail("Failed to build JVM context pack: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "jvm_dependency_trace",
        description = "Show module dependency graph for the JVM project or a specific module/dependency.",
        parameters = listOf(
            ToolParameter("module", "Optional Gradle module name, e.g. :app", ToolParameterType.STRING, required = false),
            ToolParameter("dependency", "Optional dependency module to trace, e.g. :lib", ToolParameterType.STRING, required = false),
            ToolParameter("use_gradle_command", "Use ./gradlew dependencies for resolved tree (default: false)", ToolParameterType.BOOLEAN, required = false, defaultValue = false),
        ),
        execute = { args ->
            try {
                val moduleFilter = (args["module"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                val dependencyFilter = (args["dependency"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                val useGradleCommand = (args["use_gradle_command"] as? Boolean) ?: false
                val snapshot = ensureSnapshot(index, coordinator, IndexFreshnessPolicy.QUERY_DEPENDENCY_TRACE)
                val graph = ModuleDependencyGraph(SqliteDependencyCache("./.spola/dependency-cache.db"))
                val (direct, transitive) = if (useGradleCommand) {
                    val resolved = graph.resolveWithDependenciesCommand(snapshot.modules, snapshot.projectDir)
                    val allDeps = resolved.values.flatten()
                    val direct = allDeps.filter { it.type != "external" }
                    val transitive = resolved.mapValues { (_, deps) -> deps.map { it.dependency } }
                    Pair(direct, transitive)
                } else {
                    val direct = graph.directDependencies(snapshot.modules)
                    val transitive = graph.resolveTransitiveDependencies(snapshot.modules)
                    Pair(direct, transitive)
                }
                val output = buildString {
                    appendLine("Dependency graph:")
                    snapshot.modules
                        .filter { moduleFilter == null || it.name == moduleFilter }
                        .forEach { module ->
                            val deps = direct.filter { it.moduleName == module.name }
                                .filter { dependencyFilter == null || it.dependency == dependencyFilter }
                            appendLine("- ${module.name}")
                            if (deps.isEmpty()) appendLine("  dependencies: none")
                            deps.forEach { appendLine("  ${it.type}: ${it.dependency}") }
                            val transitives = transitive[module.name].orEmpty().filter { dependencyFilter == null || it == dependencyFilter }
                            if (transitives.isNotEmpty()) appendLine("  transitive: ${transitives.joinToString(", ")}")
                        }
                }.trim()
                ToolResult.ok(output.ifBlank { "No dependency graph entries found." })
            } catch (e: Exception) {
                ToolResult.fail("Failed to trace JVM dependencies: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "jvm_task_suggest",
        description = "Suggest Gradle commands for a JVM module and change type.",
        parameters = listOf(
            ToolParameter("change_type", "Change type: src, test, build, or java", ToolParameterType.STRING),
            ToolParameter("module", "Optional Gradle module name, e.g. :app", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            try {
                val changeType = (args["change_type"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: change_type")
                val module = (args["module"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: ":"
                val commands = GradleTaskCataloger().suggestTasks(module, changeType)
                ToolResult.ok(commands.joinToString("\n") { "./gradlew $it" })
            } catch (e: Exception) {
                ToolResult.fail("Failed to suggest JVM tasks: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "jvm_change_impact",
        description = "Analyze current git changes and suggest impacted JVM modules and verification commands.",
        parameters = emptyList(),
        execute = {
            try {
                val snapshot = ensureSnapshot(index, coordinator, IndexFreshnessPolicy.QUERY_CHANGE_IMPACT)
                val collector = GitChangeCollector(Path.of(snapshot.projectDir))
                val changedFiles = collector.getChangedFiles()
                val symbols = if (index is SqliteJvmProjectIndex) {
                    changedFiles.flatMap { index.fileSymbols(it.path) }
                } else {
                    val changed = changedFiles.map { it.path }.toSet()
                    extractSymbols(snapshot).filter { it.file in changed }
                }
                val graph = ModuleDependencyGraph()
                val impact = ImpactAnalyzer().analyze(changedFiles, snapshot.modules, graph, symbols)
                ToolResult.ok(formatImpactReport(impact))
            } catch (e: Exception) {
                ToolResult.fail("Failed to analyze JVM change impact: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "jvm_failure_explain",
        description = "Parse Gradle console output and explain likely JVM build or test failures.",
        parameters = listOf(
            ToolParameter("output", "Gradle console output to analyze", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val output = (args["output"] as? String)
                    ?: return@Tool ToolResult.fail("Missing required argument: output")
                val snapshot = ensureSnapshot(index, coordinator, IndexFreshnessPolicy.QUERY_FAILURE_EXPLAIN)
                val failures = GradleFailureParser().parse(output)
                val symbols = if (index is SqliteJvmProjectIndex) {
                    snapshot.modules.flatMap { index.symbols.searchByModule(it.name) }
                } else {
                    extractSymbols(snapshot)
                }
                val report = JvmFailureExplainer().explain(failures, snapshot.modules, symbols)
                ToolResult.ok(formatFailureReport(report))
            } catch (e: Exception) {
                ToolResult.fail("Failed to explain JVM failure: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "jvm_verify_plan",
        description = "Suggest minimal JVM verification commands for edited file paths.",
        parameters = listOf(
            ToolParameter("paths", "Comma-separated edited file paths", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val paths = (args["paths"] as? String)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: return@Tool ToolResult.fail("Missing required argument: paths")
                val snapshot = ensureSnapshot(index, coordinator, IndexFreshnessPolicy.QUERY_VERIFY_PLAN)
                val plan = JvmPatchPreflight().preflightCheck(paths, snapshot.modules, ModuleDependencyGraph())
                ToolResult.ok(
                    """
                    Compilation: ${plan.compilationCommand}
                    Tests: ${plan.testCommand}
                    Estimated duration: ${plan.estimatedDuration}
                    """.trimIndent(),
                )
            } catch (e: Exception) {
                ToolResult.fail("Failed to plan JVM verification: ${e.message}")
            }
        },
    ))
}

private suspend fun ensureSnapshot(
    index: JvmProjectIndex,
    coordinator: JvmIndexCoordinator?,
    queryType: String,
): JvmProjectSnapshot = when {
    coordinator != null && index is SqliteJvmProjectIndex -> coordinator.ensureFresh(queryType, index)
    else -> index.getSnapshot() ?: index.scan(System.getProperty("user.dir"))
}

private fun formatOverview(snapshot: JvmProjectSnapshot): String = buildString {
    appendLine("Project: ${snapshot.projectDir}")
    appendLine("Scanned at: ${snapshot.scannedAt}")
    appendLine("Modules (${snapshot.modules.size}):")
    snapshot.modules.forEach { module ->
        appendLine("- ${module.name}${if (module.isRoot) " (root)" else ""}")
        appendLine("  path: ${module.path}")
        if (module.sourceDirs.isNotEmpty()) appendLine("  sources: ${module.sourceDirs.joinToString(", ")}")
        if (module.testDirs.isNotEmpty()) appendLine("  tests: ${module.testDirs.joinToString(", ")}")
        if (module.plugins.isNotEmpty()) appendLine("  plugins: ${module.plugins.joinToString(", ")}")
        val versions = listOfNotNull(module.javaVersion?.let { "Java $it" }, module.kotlinVersion?.let { "Kotlin $it" })
        if (versions.isNotEmpty()) appendLine("  versions: ${versions.joinToString(", ")}")
    }
}.trim()

private fun formatSymbols(symbols: List<SymbolLocation>): String {
    if (symbols.isEmpty()) return "No symbols found."
    return symbols.joinToString("\n") { symbol ->
        "${symbol.module} ${symbol.kind} ${symbol.name} ${symbol.file}:${symbol.line}:${symbol.column}" +
            (symbol.visibility?.let { " $it" } ?: "")
    }
}

private suspend fun outlineFile(snapshot: JvmProjectSnapshot, index: JvmProjectIndex, path: String): List<SymbolLocation> {
    val root = Path.of(snapshot.projectDir).toAbsolutePath().normalize()
    val file = root.resolve(path).normalize().takeIf { !Path.of(path).isAbsolute }
        ?: Path.of(path).toAbsolutePath().normalize()
    // Security: reject paths outside the project root
    if (!file.startsWith(root)) {
        throw SecurityException("Access denied: $path is outside the project directory $root")
    }
    val module = snapshot.modules.firstOrNull { file.startsWith(Path.of(it.path).toAbsolutePath().normalize()) }
        ?: ProjectModule(":", root.toString(), true, emptyList(), emptyList(), emptyList(), null, null, emptyList())
    val relative = runCatching { root.relativize(file).toString() }.getOrElse { path }
    if (index is SqliteJvmProjectIndex) {
        val cached = index.fileSymbols(relative)
        if (cached.isNotEmpty()) return cached
    }
    return when (file.extension) {
        "kt" -> KotlinSymbolExtractor().extract(file, module.name, root)
        "java" -> JavaSymbolExtractor().extract(file, module.name, root)
        else -> emptyList()
    }
}

private fun extractSymbols(snapshot: JvmProjectSnapshot): List<SymbolLocation> {
    val root = Path.of(snapshot.projectDir).toAbsolutePath().normalize()
    val kotlinExtractor = KotlinSymbolExtractor()
    val javaExtractor = JavaSymbolExtractor()
    return snapshot.modules.flatMap { module ->
        (module.sourceDirs + module.testDirs).flatMap { sourceDir ->
            val dir = root.resolve(sourceDir).normalize()
            if (!Files.isDirectory(dir)) return@flatMap emptyList()
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.flatMap { file ->
                    when (file.extension) {
                        "kt" -> kotlinExtractor.extract(file, module.name, root).stream()
                        "java" -> javaExtractor.extract(file, module.name, root).stream()
                        else -> emptyList<SymbolLocation>().stream()
                    }
                }.toList()
            }
        }
    }
}

private fun goalKeywords(goal: String): List<String> =
    goal.split(Regex("""[^A-Za-z0-9_]+""")).map { it.trim() }.filter { it.length >= 3 }.distinct()

private fun List<SymbolLocation>.filterByGoal(goal: String): List<SymbolLocation> {
    val keywords = goalKeywords(goal)
    if (keywords.isEmpty()) return this
    return filter { symbol ->
        keywords.any {
            symbol.name.contains(it, ignoreCase = true) ||
                symbol.file.contains(it, ignoreCase = true) ||
                symbol.module.contains(it, ignoreCase = true)
        }
    }
}

private fun List<String>.filterDependenciesByGoal(goal: String): List<String> {
    val keywords = goalKeywords(goal)
    if (keywords.isEmpty()) return this
    return filter { dependency -> keywords.any { dependency.contains(it, ignoreCase = true) } }
}

private fun formatImpactReport(impact: dev.spola.jvm.ImpactReport): String = buildString {
    appendLine("Changed files:")
    if (impact.changedFiles.isEmpty()) appendLine("- none")
    impact.changedFiles.forEach { appendLine("- ${it.changeType} ${it.path}${it.summary?.let { summary -> " ($summary)" } ?: ""}") }
    appendLine("Changed symbols:")
    if (impact.changedSymbols.isEmpty()) appendLine("- none")
    impact.changedSymbols.forEach { appendLine("- ${it.module} ${it.kind} ${it.name} ${it.file}:${it.line}") }
    appendLine("Impacted modules: ${impact.impactedModules.ifEmpty { listOf("none") }.joinToString(", ")}")
    appendLine("Compilation scope: ${impact.compilationScope.ifEmpty { listOf("none") }.joinToString(", ")}")
    appendLine("Verification commands:")
    if (impact.verificationCommands.isEmpty()) appendLine("- none")
    impact.verificationCommands.forEach { appendLine("- ./gradlew $it") }
}.trim()

private fun formatFailureReport(report: dev.spola.jvm.FailureReport): String = buildString {
    appendLine(report.summary)
    appendLine("Root causes:")
    if (report.rootCauses.isEmpty()) appendLine("- none")
    report.rootCauses.forEach {
        appendLine("- module=${it.module ?: "unknown"} file=${it.file ?: "unknown"} symbol=${it.symbol ?: "unknown"}: ${it.message}")
    }
    appendLine("Suggested commands:")
    if (report.suggestedFixCommands.isEmpty()) appendLine("- none")
    report.suggestedFixCommands.forEach { appendLine("- ./gradlew $it") }
}.trim()
