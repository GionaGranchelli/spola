package dev.spola.jvm

internal data class ParsedBuildFile(
    val plugins: List<String> = emptyList(),
    val javaVersion: String? = null,
    val kotlinVersion: String? = null,
    val dependencies: List<String> = emptyList(),
    val sourceDirs: List<String> = emptyList(),
    val testDirs: List<String> = emptyList(),
)

internal object BuildFileParsers {
    private val includeRegex = Regex("""include\s*(?:\(([^)]*)\)|([^\n]+))""")
    private val quotedRegex = Regex("\"([^\"]+)\"|'([^']+)'")
    private val pluginIdRegex = Regex(
        """id\s*(?:\(\s*["']([^"']+)["']\s*\)|["']([^"']+)["'])(?:\s*version\s*["']([^"']+)["'])?""",
    )
    private val aliasPluginRegex = Regex("""alias\s*\(\s*libs\.plugins\.([A-Za-z0-9_.-]+)\s*\)""")
    private val kotlinPluginRegex = Regex("""kotlin\s*\(\s*["']([^"']+)["']\s*\)(?:\s*version\s*["']([^"']+)["'])?""")
    private val dependencyLineRegex = Regex(
        """^\s*(?:api|implementation|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\s*(?:\((.+)\)|(.+))\s*$""",
    )
    private val srcDirRegexes = listOf(
        Regex("""srcDir\s*\(\s*["']([^"']+)["']\s*\)"""),
        Regex("""srcDir\s+["']([^"']+)["']"""),
    )
    private val srcDirsListRegex = Regex("""srcDirs\s*=?\s*\[([^\]]+)]""")
    private val javaVersionRegexes = listOf(
        Regex("""JavaVersion\.VERSION_([0-9_]+)"""),
        Regex("""jvmToolchain\s*\(\s*(\d+)\s*\)"""),
        Regex("""languageVersion\.set\s*\(\s*JavaLanguageVersion\.of\s*\(\s*(\d+)\s*\)"""),
        Regex("""languageVersion\s*=\s*JavaLanguageVersion\.of\s*\(\s*(\d+)\s*\)"""),
        Regex("""jvmTarget\s*=\s*["']([^"']+)["']"""),
    )
    private val kotlinVersionRegexes = listOf(
        Regex("""kotlin\([^)]*\)\s*version\s*["']([^"']+)["']"""),
        Regex("""id\s*\(\s*["']org\.jetbrains\.kotlin\.[^"']+["']\s*\)\s*version\s*["']([^"']+)["']"""),
    )

    fun parseSettingsModules(text: String): List<String> {
        return includeRegex.findAll(text).flatMap { match ->
            val includeBody = match.groupValues[1].ifBlank { match.groupValues[2] }
            quotedRegex.findAll(includeBody).mapNotNull { quoted ->
                val value = quoted.groupValues[1].ifBlank { quoted.groupValues[2] }.trim()
                value.takeIf { it.startsWith(":") }
            }
        }.distinct().toList()
    }

    fun parseBuildFile(text: String): ParsedBuildFile {
        val stripped = stripComments(text)
        val plugins = buildList {
            pluginIdRegex.findAll(stripped).forEach { match ->
                val id = match.groupValues[1].ifBlank { match.groupValues[2] }
                val version = match.groupValues.getOrNull(3).orEmpty()
                add(if (version.isBlank()) id else "$id:$version")
            }
            aliasPluginRegex.findAll(stripped).forEach { add("libs.plugins.${it.groupValues[1]}") }
            kotlinPluginRegex.findAll(stripped).forEach { match ->
                val id = "kotlin-${match.groupValues[1]}"
                val version = match.groupValues.getOrNull(2).orEmpty()
                add(if (version.isBlank()) id else "$id:$version")
            }
        }.distinct()

        val dependencies = stripped.lineSequence()
            .mapNotNull { line ->
                dependencyLineRegex.matchEntire(line)?.let { match ->
                    match.groupValues[1].ifBlank { match.groupValues[2] }
                        .trim()
                        .removeSuffix("}")
                        .trim()
                        .trim('"', '\'')
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val discoveredSourceDirs = discoverSourceDirs(stripped)
        val mainSourceDirs = discoveredSourceDirs.filterNot { it.contains("/test/", ignoreCase = true) }
        val testSourceDirs = discoveredSourceDirs.filter { it.contains("/test/", ignoreCase = true) }

        return ParsedBuildFile(
            plugins = plugins,
            javaVersion = javaVersionRegexes.firstNotNullOfOrNull { regex ->
                regex.find(stripped)?.groupValues?.getOrNull(1)?.replace("_", ".")
            },
            kotlinVersion = kotlinVersionRegexes.firstNotNullOfOrNull { regex ->
                regex.find(stripped)?.groupValues?.getOrNull(1)
            },
            dependencies = dependencies,
            sourceDirs = mainSourceDirs,
            testDirs = testSourceDirs,
        )
    }

    fun detectTestFramework(dependencies: List<String>): String? = when {
        dependencies.any { it.contains("kotest", ignoreCase = true) } -> "Kotest"
        dependencies.any { it.contains("junit-jupiter", ignoreCase = true) || it.contains("junit-bom", ignoreCase = true) } -> "JUnit 5"
        else -> null
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""//.*"""), "")

    private fun discoverSourceDirs(text: String): List<String> {
        val direct = srcDirRegexes.flatMap { regex ->
            regex.findAll(text).map { it.groupValues[1].trim() }.toList()
        }
        val grouped = srcDirsListRegex.findAll(text).flatMap { match ->
            quotedRegex.findAll(match.groupValues[1]).map { quoted ->
                quoted.groupValues[1].ifBlank { quoted.groupValues[2] }.trim()
            }
        }.toList()
        return (direct + grouped).filter { it.isNotBlank() }.distinct()
    }
}
