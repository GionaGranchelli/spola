package dev.spola.agent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertTrue

class PermissionEnforcerTest {

    // Helper to build a minimal AgentDefinition
    private fun agentDef(
        filesystemAccess: String = "read-write",
        shellAccess: Boolean = true,
        networkAccess: Boolean = true,
        executeCommands: String = "auto",
    ) = AgentDefinition(
        id = "test-agent",
        name = "Test Agent",
        description = "Test agent for permission enforcer tests",
        systemPrompt = "You are a test agent.",
        preferredModel = "gpt-4o",
        preferredProvider = "openai",
        filesystemAccess = filesystemAccess,
        shellAccess = shellAccess,
        networkAccess = networkAccess,
        executeCommands = executeCommands,
    )

    @Test
    fun `checkShell allows command when shellAccess is true and command is allowed`() {
        val enforcer = PermissionEnforcer(agentDef(shellAccess = true))
        assertDoesNotThrow {
            enforcer.checkShell("ls -la", "/tmp")
        }
    }

    @Test
    fun `checkShell throws when shellAccess is false`() {
        val enforcer = PermissionEnforcer(agentDef(shellAccess = false))
        val ex = assertThrows<PermissionDeniedException> {
            enforcer.checkShell("ls -la")
        }
        assertContains(ex.message!!, "disabled")
    }

    @Test
    fun `checkShell throws when executeCommands is never`() {
        val enforcer = PermissionEnforcer(agentDef(executeCommands = "never"))
        val ex = assertThrows<PermissionDeniedException> {
            enforcer.checkShell("ls -la")
        }
        assertContains(ex.message!!, "disabled")
    }

    @Test
    fun `checkShell blocks destructive command when filesystemAccess is read-only`() {
        val enforcer = PermissionEnforcer(agentDef(filesystemAccess = "read-only"))
        val ex = assertThrows<PermissionDeniedException> {
            enforcer.checkShell("rm -rf /tmp/test")
        }
        assertContains(ex.message!!, "read-only")
    }

    @Test
    fun `checkShell blocks write command when filesystemAccess is read-only`() {
        val enforcer = PermissionEnforcer(agentDef(filesystemAccess = "read-only"))
        val ex = assertThrows<PermissionDeniedException> {
            enforcer.checkShell("cp /source /dest")
        }
        assertContains(ex.message!!, "read-only")
    }

    @Test
    fun `checkShell blocks network command when networkAccess is false`() {
        val enforcer = PermissionEnforcer(agentDef(networkAccess = false))
        val ex = assertThrows<PermissionDeniedException> {
            enforcer.checkShell("curl https://example.com")
        }
        assertContains(ex.message!!, "network")
    }

    @Test
    fun `checkShell blocks destructive command when filesystemAccess is none`() {
        val enforcer = PermissionEnforcer(agentDef(filesystemAccess = "none"))
        val ex = assertThrows<PermissionDeniedException> {
            enforcer.checkShell("rm file.txt")
        }
        assertContains(ex.message!!, "filesystem")
    }

    @Test
    fun `checkFileAccess throws when filesystemAccess is read-only and write is true`() {
        val enforcer = PermissionEnforcer(agentDef(filesystemAccess = "read-only"))
        val ex = assertThrows<PermissionDeniedException> {
            enforcer.checkFileAccess("/some/path", writeMode = true)
        }
        assertContains(ex.message!!, "read-only")
    }

    @Test
    fun `checkFileAccess passes when filesystemAccess is read-write and write is true`() {
        val enforcer = PermissionEnforcer(agentDef(filesystemAccess = "read-write"))
        assertDoesNotThrow {
            enforcer.checkFileAccess("/some/path", writeMode = true)
        }
    }

    @Test
    fun `checkFileAccess passes when write is false regardless of filesystemAccess`() {
        val enforcer = PermissionEnforcer(agentDef(filesystemAccess = "read-only"))
        assertDoesNotThrow {
            enforcer.checkFileAccess("/some/path", writeMode = false)
        }
    }
}
