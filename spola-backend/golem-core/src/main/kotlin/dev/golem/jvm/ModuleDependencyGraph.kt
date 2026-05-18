package dev.spola.jvm

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ModuleDependencyGraph(
    private val cache: SqliteDependencyCache? = null,
) {
    private val dependencyRegex = Regex("""\b(api|implementation|compileOnly|testImplementation)\s*\(\s*project\s*\(\s*["'](:[^"']+)["']\s*\)\s*\)""")
    private val projectDepRegex = Regex("""\+--- project\s+(:\S+)""")
    private val externalDepRegex = Regex("""\+---\s+([\w.]+):([\w.]+):([\S]+)""")
    private val duplicateMarker = Regex("""\(\*\)""")

    fun resolveTransitiveDependencies(modules: List<ProjectModule>): Map<String, List<String>> {
        val direct = directDependencies(modules).groupBy({ it.moduleName }, { it.dependency })
        return modules.associate { module ->
            module.name to transitive(module.name, direct)
        }
    }

    fun directDependencies(modules: List<ProjectModule>): List<ModuleDependency> {
        val dependencies = modules.flatMap { module ->
            val fromBuildFile = parseProjectDependencies(module)
            if (fromBuildFile.isNotEmpty()) {
                fromBuildFile
            } else {
                module.dependencies.mapNotNull { dependency ->
                    val project = Regex("""project\s*\(\s*["'](:[^"']+)["']\s*\)""").find(dependency)?.groupValues?.get(1)
                        ?: dependency.takeIf { it.startsWith(":") }
                    project?.let { ModuleDependency(module.name, it, "implementation") }
                }
            }
        }.distinctBy { "${it.moduleName}:${it.dependency}" }
        cache?.replaceAll(dependencies)
        return dependencies
    }

    fun findAffectedModules(
        changedFiles: List<String>,
        modules: List<ProjectModule>,
        symbols: List<SymbolLocation>,
    ): List<String> {
        val changedModules = changedFiles.mapNotNull { file -> moduleForFile(file, modules) }.toSet() +
            symbols.map { it.module }.toSet()
        if (changedModules.isEmpty()) return emptyList()

        val reverse = directDependencies(modules).groupBy({ it.dependency }, { it.moduleName })
        val affected = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        changedModules.forEach {
            affected += it
            queue += it
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            reverse[current].orEmpty().forEach { dependent ->
                if (affected.add(dependent)) queue += dependent
            }
        }
        return affected.toList()
    }

    private fun parseProjectDependencies(module: ProjectModule): List<ModuleDependency> {
        val buildFile = Path.of(module.path).resolve("build.gradle.kts")
        if (!Files.isRegularFile(buildFile)) return emptyList()
        val text = runCatching { Files.readString(buildFile) }.getOrDefault("")
        BuildFileParsers.parseBuildFile(text)
        return dependencyRegex.findAll(text).map {
            ModuleDependency(
                moduleName = module.name,
                dependency = it.groupValues[2],
                type = it.groupValues[1],
            )
        }.distinctBy { "${it.moduleName}:${it.dependency}" }.toList()
    }

    private fun transitive(module: String, direct: Map<String, List<String>>): List<String> {
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(direct[module].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (seen.add(next)) {
                direct[next].orEmpty().forEach { queue += it }
            }
        }
        return seen.toList()
    }

    private fun moduleForFile(file: String, modules: List<ProjectModule>): String? {
        val normalized = file.replace('\\', '/')
        val normalizedPath = modulePath(normalized)
        return modules
            .filterNot { it.isRoot }
            .firstOrNull { normalizedPath == modulePath(it.name.trimStart(':')) || normalizedPath.startsWith("${modulePath(it.name.trimStart(':'))}/") }
            ?.name
            ?: modules.firstOrNull { module ->
                (module.sourceDirs + module.testDirs).any { normalized.startsWith(it.replace('\\', '/')) }
            }?.name
            ?: modules.firstOrNull { it.isRoot }?.name
    }

    /**
     * Parse the tree output from `./gradlew :<module>:dependencies --configuration runtimeClasspath`.
     * Returns resolved [ModuleDependency] entries (project deps + external deps).
     */
    fun parseDependenciesOutput(output: String): List<ModuleDependency> {
        val deps = mutableListOf<ModuleDependency>()
        output.lines().forEach { line ->
            if (duplicateMarker.containsMatchIn(line)) return@forEach
            projectDepRegex.find(line)?.let {
                deps += ModuleDependency("", it.groupValues[1], "project")
                return@forEach
            }
            externalDepRegex.find(line)?.let {
                val artifact = "${it.groupValues[1]}:${it.groupValues[2]}:${it.groupValues[3]}"
                deps += ModuleDependency("", artifact, "external")
                return@forEach
            }
        }
        return deps
    }

    /**
     * Execute `./gradlew :<module>:dependencies --configuration runtimeClasspath -q` in the
     * given project directory and return the full output. Times out after 60 seconds.
     */
    fun runDependenciesCommand(projectDir: String, module: String): String {
        val process = ProcessBuilder(
            "./gradlew", ":$module:dependencies", "--configuration", "runtimeClasspath",
            "--no-daemon", "-q",
        )
            .directory(File(projectDir))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.reader().readText()
        val exitCode = process.waitFor(60, TimeUnit.SECONDS)
        if (exitCode != true) process.destroyForcibly()
        return output
    }

    /**
     * Resolve the full dependency graph by running `./gradlew dependencies` for each module.
     * Falls back to build-file parsing if the command fails or times out.
     */
    fun resolveWithDependenciesCommand(
        modules: List<ProjectModule>,
        projectDir: String,
    ): Map<String, List<ModuleDependency>> {
        val result = mutableMapOf<String, List<ModuleDependency>>()
        val buildFallback = directDependencies(modules).groupBy { it.moduleName }
        for (module in modules) {
            try {
                val output = runDependenciesCommand(projectDir, module.name.trimStart(':'))
                val parsed = parseDependenciesOutput(output)
                if (parsed.isNotEmpty()) {
                    result[module.name] = parsed
                } else {
                    result[module.name] = buildFallback[module.name].orEmpty()
                }
            } catch (_: Exception) {
                result[module.name] = buildFallback[module.name].orEmpty()
            }
        }
        return result
    }

    companion object {
        /** Normalize a Gradle module name to a filesystem path prefix. `:foo:bar` → `foo/bar` */
        fun modulePath(name: String): String = name.trimStart(':').replace(':', '/')
    }
}
