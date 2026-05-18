package dev.spola.jvm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo

class GradleProjectScanner {
    fun scan(projectDir: String): List<ProjectModule> {
        val root = Path.of(projectDir).toAbsolutePath().normalize()
        if (!root.exists() || !root.isDirectory()) return emptyList()

        val settings = resolveFirstExisting(root, "settings.gradle.kts", "settings.gradle")
        val moduleNames = if (settings.exists()) {
            runCatching { BuildFileParsers.parseSettingsModules(Files.readString(settings)) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val modules = mutableListOf<ProjectModule>()
        val rootBuild = resolveFirstExisting(root, "build.gradle.kts", "build.gradle")
        if (rootBuild.exists() || moduleNames.isEmpty()) {
            modules += buildModule(root, root, ":", isRoot = true)
        }

        for (moduleName in moduleNames) {
            val modulePath = root.resolve(moduleName.trimStart(':').replace(':', '/')).normalize()
            if (!modulePath.startsWith(root)) continue
            val buildFile = resolveFirstExisting(modulePath, "build.gradle.kts", "build.gradle")
            if (!buildFile.exists() && !modulePath.isDirectory()) continue
            modules += buildModule(root, modulePath, moduleName, isRoot = modulePath == root)
        }

        return modules.distinctBy { it.name }
    }

    private fun buildModule(root: Path, modulePath: Path, name: String, isRoot: Boolean): ProjectModule {
        val build = resolveFirstExisting(modulePath, "build.gradle.kts", "build.gradle")
        val parsed = if (build.exists()) {
            runCatching { BuildFileParsers.parseBuildFile(Files.readString(build)) }.getOrDefault(ParsedBuildFile())
        } else {
            ParsedBuildFile()
        }
        val sourceDirs = existingSourceDirs(root, modulePath, "main", parsed.sourceDirs)
        val testDirs = existingSourceDirs(root, modulePath, "test", parsed.testDirs)
        val testFramework = BuildFileParsers.detectTestFramework(parsed.dependencies)
        val dependencies = if (testFramework == null) parsed.dependencies else parsed.dependencies + "testFramework:$testFramework"

        return ProjectModule(
            name = name,
            path = modulePath.toString(),
            isRoot = isRoot,
            sourceDirs = sourceDirs,
            testDirs = testDirs,
            plugins = parsed.plugins,
            javaVersion = parsed.javaVersion,
            kotlinVersion = parsed.kotlinVersion,
            dependencies = dependencies.distinct(),
        )
    }

    private fun existingSourceDirs(root: Path, modulePath: Path, sourceSet: String, parsedDirs: List<String>): List<String> {
        val candidates = listOf(
            modulePath.resolve("src/$sourceSet/kotlin"),
            modulePath.resolve("src/$sourceSet/java"),
        ) + parsedDirs.map { modulePath.resolve(it) }
        return candidates.filter { it.exists() && it.isDirectory() }.map { dir ->
            dir.relativeTo(root).toString()
        }.distinct()
    }

    private fun resolveFirstExisting(base: Path, vararg names: String): Path =
        names.asSequence().map(base::resolve).firstOrNull { it.exists() } ?: base.resolve(names.first())
}
