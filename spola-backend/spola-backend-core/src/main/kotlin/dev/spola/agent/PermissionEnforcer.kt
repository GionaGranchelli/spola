package dev.spola.agent

import dev.spola.tools.ShellSecurityPolicy
import dev.spola.tools.inspectShellCommand

/**
 * ⚠️ IMPORTANT: This is NOT a real security boundary.
 * When shellAccess = true, a determined agent can always escape
 * filesystem and network restrictions through the shell.
 * For true isolation, use Docker, seccomp, or read-only mounts.
 *
 * Runtime permission checks for agent tool execution.
 *
 * **Known limitation:** When `shellAccess = true`, shell commands can
 * bypass `filesystemAccess` and `networkAccess` restrictions at the OS
 * level. This enforcer blocks known destructive/network commands but
 * cannot fully prevent a determined shell escape. For true isolation,
 * use OS-level sandboxing (containers, seccomp, read-only mounts).
 *
 * Layer 1: Registry filtering (what model sees)
 * Layer 2: Runtime enforcement (this class)
 * Layer 3: OS-level sandbox (recommended for shell agents)
 */
class PermissionEnforcer(private val agentDef: AgentDefinition) {

    /** Check if a shell command is permitted. */
    fun checkShell(command: String, workdir: String? = null) {
        val parsedCommand = inspectShellCommand(command, workdir)
        val commandNames = listOfNotNull(
            parsedCommand.executableName,
            parsedCommand.resolvedExecutableName,
        ).distinct()
        val executable = commandNames.firstOrNull() ?: parsedCommand.rawExecutable

        if (!agentDef.shellAccess) {
            throw PermissionDeniedException("Shell access is disabled for this agent")
        }

        if (agentDef.executeCommands == "never") {
            throw PermissionDeniedException("Command execution is disabled for this agent")
        }

        // Filesystem-read-only agents: block destructive + write commands in shell
        if (agentDef.filesystemAccess == "read-only") {
            if (commandNames.any { it in ShellSecurityPolicy.destructiveCommands || it in ShellSecurityPolicy.writeCommands }) {
                throw PermissionDeniedException(
                    "Cannot run '$executable': agent has read-only filesystem access " +
                        "(shell bypasses filesystem constraints)",
                )
            }
        }

        // Filesystem-none agents: block ALL write-capable commands
        if (agentDef.filesystemAccess == "none") {
            if (commandNames.any { it in ShellSecurityPolicy.destructiveCommands || it in ShellSecurityPolicy.writeCommands }) {
                throw PermissionDeniedException(
                    "Cannot run '$executable': agent has no filesystem access",
                )
            }
        }

        // Network access disabled: block network commands
        if (!agentDef.networkAccess && commandNames.any { it in ShellSecurityPolicy.networkCommands }) {
            throw PermissionDeniedException(
                "Cannot run '$executable': agent has network access disabled",
            )
        }
    }

    /** Check if a file operation is permitted. */
    fun checkFileAccess(path: String, writeMode: Boolean = false) {
        if (!writeMode) return

        when (agentDef.filesystemAccess) {
            "read-only" -> throw PermissionDeniedException(
                "Cannot write: agent has read-only filesystem access",
            )
            "none" -> throw PermissionDeniedException(
                "Filesystem access is disabled for this agent",
            )
        }
    }
}

class PermissionDeniedException(message: String) : SecurityException(message)
