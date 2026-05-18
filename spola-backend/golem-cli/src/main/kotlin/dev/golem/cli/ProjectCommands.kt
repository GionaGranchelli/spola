package dev.spola.cli

import dev.spola.jvm.SqliteJvmProjectIndex
import dev.spola.jvm.SymbolKind
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(
    name = "project",
    description = ["Inspect JVM Gradle project structure and symbols"],
    subcommands = [
        ProjectScanCommand::class,
        ProjectOverviewCommand::class,
        ProjectSymbolCommand::class,
    ],
)
class ProjectCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: GolemCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}

@Command(name = "scan", description = ["Force a full JVM project reindex"])
class ProjectScanCommand : Callable<Int> {
    @ParentCommand
    lateinit var projectCommand: ProjectCommand

    override fun call(): Int = runBlocking {
        val root = projectCommand.root
        val index = SqliteJvmProjectIndex(root.jvmIndexDb)
        val snapshot = index.scan(root.workdir)
        println("Indexed ${snapshot.modules.size} module(s) in ${snapshot.projectDir}")
        snapshot.modules.forEach { module ->
            println("${module.name} | sources=${module.sourceDirs.size} tests=${module.testDirs.size} deps=${module.dependencies.size}")
        }
        0
    }
}

@Command(name = "overview", description = ["Print the JVM module tree"])
class ProjectOverviewCommand : Callable<Int> {
    @ParentCommand
    lateinit var projectCommand: ProjectCommand

    override fun call(): Int = runBlocking {
        val root = projectCommand.root
        val index = SqliteJvmProjectIndex(root.jvmIndexDb)
        val snapshot = index.getFreshSnapshot(root.workdir)
        println("Project: ${snapshot.projectDir}")
        println("Modules (${snapshot.modules.size}):")
        snapshot.modules.forEach { module ->
            println("- ${module.name}${if (module.isRoot) " (root)" else ""}")
            println("  path: ${module.path}")
            if (module.sourceDirs.isNotEmpty()) println("  sources: ${module.sourceDirs.joinToString(", ")}")
            if (module.testDirs.isNotEmpty()) println("  tests: ${module.testDirs.joinToString(", ")}")
            if (module.plugins.isNotEmpty()) println("  plugins: ${module.plugins.joinToString(", ")}")
        }
        0
    }
}

@Command(name = "symbol", description = ["Lookup a JVM symbol"])
class ProjectSymbolCommand : Callable<Int> {
    @ParentCommand
    lateinit var projectCommand: ProjectCommand

    @Parameters(index = "0", description = ["Symbol name or substring"])
    lateinit var name: String

    @Option(names = ["--module"], description = ["Filter by Gradle module, e.g. :golem-core"])
    var module: String? = null

    @Option(names = ["--kind"], description = ["Filter by symbol kind"])
    var kind: String? = null

    override fun call(): Int = runBlocking {
        val root = projectCommand.root
        val index = SqliteJvmProjectIndex(root.jvmIndexDb)
        index.getFreshSnapshot(root.workdir)
        val parsedKind = kind?.let { SymbolKind.valueOf(it.uppercase()) }
        val symbols = index.searchSymbols(name, parsedKind, module)
        if (symbols.isEmpty()) {
            println("No symbols found for $name")
        } else {
            symbols.forEach { symbol ->
                println("${symbol.module} ${symbol.kind} ${symbol.name} ${symbol.file}:${symbol.line}:${symbol.column}")
            }
        }
        0
    }
}
