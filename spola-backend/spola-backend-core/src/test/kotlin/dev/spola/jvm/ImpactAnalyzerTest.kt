package dev.spola.jvm

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpactAnalyzerTest {
    @Test
    fun `changed files map to impacted modules and verification commands`() {
        val modules = GradleProjectScanner().scan(fixture("multi-module-deps").toString())
        val report = ImpactAnalyzer().analyze(
            changedFiles = listOf(ChangedFile("service/src/main/kotlin/com/example/service/Service.kt", ChangeType.MODIFIED)),
            modules = modules,
            depGraph = ModuleDependencyGraph(),
            symbols = emptyList(),
        )

        assertEquals(listOf(":service", ":app"), report.impactedModules)
        assertTrue(report.compilationScope.contains(":service:compileKotlin"))
        assertTrue(report.verificationCommands.contains(":app:test"))
    }

    @Test
    fun `changed test file prefers test class selection`() {
        val modules = GradleProjectScanner().scan(fixture("multi-module-deps").toString())
        val report = ImpactAnalyzer().analyze(
            changedFiles = listOf(ChangedFile("service/src/test/kotlin/com/example/service/ServiceTest.kt", ChangeType.MODIFIED)),
            modules = modules,
            depGraph = ModuleDependencyGraph(),
            symbols = emptyList(),
        )

        assertTrue(report.likelyAffectedTests.any { it == ":service:test --tests \"com.example.service.ServiceTest\"" })
    }

    private fun fixture(name: String): Path =
        Path.of(javaClass.classLoader.getResource("fixtures/jvm/$name")!!.toURI())
}
