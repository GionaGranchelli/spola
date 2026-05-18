package dev.spola.app.backend.manager

import dev.spola.app.models.*
import dev.spola.app.backend.repo.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class CommandManager(
    private val auditRepository: AuditRepository,
    private val flowManager: FlowManager,
) {
    private val approvalQueue = ConcurrentHashMap<String, BashCommandPreview>()
    private val runningProcesses = ConcurrentHashMap<String, Process>()

    fun preview(request: BashCommandRequest): BashCommandPreview {
        val preview = BashCommandPreview(
            approvalId = UUID.randomUUID().toString(),
            command = request.command,
            sessionId = request.sessionId ?: error("sessionId required"),
            workingDirectory = null,
            status = CommandStatus.PENDING,
        )
        approvalQueue[preview.approvalId] = preview
        auditRepository.log("bash.preview", sessionId = preview.sessionId, approvalId = preview.approvalId, command = preview.command)
        return preview
    }

    fun approve(approvalId: String): BashCommandPreview? {
        val stored = approvalQueue[approvalId] ?: return null
        val approved = stored.copy(status = CommandStatus.APPROVED)
        approvalQueue[approvalId] = approved
        auditRepository.log("bash.approve", sessionId = approved.sessionId, approvalId = approved.approvalId, command = approved.command)
        return approved
    }

    suspend fun execute(request: BashCommandRequest): BashCommandResponse = coroutineScope {
        val approvalId = request.approvalId ?: return@coroutineScope BashCommandResponse("approvalId required", -1, true)
        val approved = approvalQueue[approvalId] ?: return@coroutineScope BashCommandResponse("approval not found", -1, true)
        
        if (approved.status != CommandStatus.APPROVED) return@coroutineScope BashCommandResponse("command not approved", -1, true)
        if (request.command != approved.command) return@coroutineScope BashCommandResponse("command does not match approved preview", -1, true)
        
        val sessionId = request.sessionId ?: approved.sessionId
        if (sessionId != approved.sessionId) return@coroutineScope BashCommandResponse("session does not match approved preview", -1, true)

        val commandText = approved.command
        val commandFlow = flowManager.getCommandFlow(approvalId)
        auditRepository.log("bash.execute", sessionId = sessionId, approvalId = approvalId, command = commandText)

        try {
            approvalQueue[approvalId] = approved.copy(status = CommandStatus.RUNNING)
            commandFlow.emit(CommandStreamEvent(approvalId, sessionId, CommandStreamType.STARTED))
            
            val pb = ProcessBuilder("bash", "-c", commandText)
            approved.workingDirectory?.let { pb.directory(File(it)) }
            val process = pb.start()
            runningProcesses[approvalId] = process
            
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val out = StringBuilder()
            val err = StringBuilder()

            val stdoutJob = launch(Dispatchers.IO) {
                stdout.lineSequence().forEach {
                    out.appendLine(it)
                    launch { commandFlow.emit(CommandStreamEvent(approvalId, sessionId, CommandStreamType.STDOUT, it)) }
                }
            }
            val stderrJob = launch(Dispatchers.IO) {
                stderr.lineSequence().forEach {
                    err.appendLine(it)
                    launch { commandFlow.emit(CommandStreamEvent(approvalId, sessionId, CommandStreamType.STDERR, it)) }
                }
            }

            val code = process.waitFor()
            runningProcesses.remove(approvalId)
            stdoutJob.join()
            stderrJob.join()

            val finalStatus = if (code == 0) CommandStatus.COMPLETED else CommandStatus.FAILED
            approvalQueue[approvalId] = approved.copy(status = finalStatus)
            commandFlow.emit(CommandStreamEvent(approvalId, sessionId, if (code == 0) CommandStreamType.COMPLETED else CommandStreamType.FAILED, exitCode = code))
            
            BashCommandResponse(out.toString() + err.toString(), code, code != 0, approvalId = approvalId)
        } catch (e: Exception) {
            approvalQueue[approvalId] = approved.copy(status = CommandStatus.FAILED)
            runningProcesses.remove(approvalId)
            commandFlow.emit(CommandStreamEvent(approvalId, sessionId, CommandStreamType.FAILED, content = e.message))
            BashCommandResponse(e.message ?: "Error", -1, true, approvalId = approvalId)
        }
    }

    fun sendInput(approvalId: String, input: String): Boolean {
        val process = runningProcesses[approvalId] ?: return false
        return try {
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            writer.write(input)
            if (!input.endsWith("\n")) writer.newLine()
            writer.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun invalidateApprovals() {
        approvalQueue.clear()
        runningProcesses.values.forEach { it.destroy() }
        runningProcesses.clear()
    }
}
