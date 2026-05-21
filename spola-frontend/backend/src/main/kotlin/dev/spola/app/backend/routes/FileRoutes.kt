package dev.spola.app.backend.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import dev.spola.app.backend.TrustAuth
import dev.spola.app.backend.BackendServices
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import dev.spola.app.backend.repo.uploadsRoot
import dev.spola.app.models.FileMetadata
import dev.spola.app.models.FileTransferRequest
import dev.spola.app.models.FileTransferResponse
import java.io.File
import java.util.UUID

private val fileRoot = File(System.getProperty("spola.fileRoot") ?: System.getProperty("user.home")).canonicalFile

private fun resolveFilePath(rawPath: String): File? {
    val candidate = File(rawPath)
    val target = if (candidate.isAbsolute) candidate else File(fileRoot, rawPath)
    val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
    val rootPath = fileRoot.path
    return if (canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)) canonical else null
}

fun Route.fileRoutes(services: BackendServices) {
    get("/files/root") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        call.respond(mapOf("root" to fileRoot.path))
    }

    // List files for a session
    get("/session/{id}/files") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(services.fileRepository.getBySessionId(sessionId))
    }

    // Upload a file to a session
    post("/session/{id}/upload") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        
        val multipart = call.receiveMultipart()
        var metadata: FileMetadata? = null
        
        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                val fileName = part.originalFileName ?: "file"
                val fileId = UUID.randomUUID().toString()
                val mimeType = part.contentType?.toString() ?: "application/octet-stream"
                val sessionDir = File(uploadsRoot, sessionId).also { if (!it.exists()) it.mkdirs() }
                val storageFile = File(sessionDir, fileId)
                
                val channel: ByteReadChannel = part.provider()
                storageFile.outputStream().use { output ->
                    channel.copyTo(output)
                }
                val size = storageFile.length()
                
                val meta = FileMetadata(
                    id = fileId,
                    sessionId = sessionId,
                    name = fileName,
                    mimeType = mimeType,
                    size = size,
                    timestamp = System.currentTimeMillis()
                )
                services.fileRepository.save(meta, storageFile.absolutePath)
                metadata = meta
                services.auditRepository.log("file.upload", sessionId = sessionId, path = fileName, details = "id=$fileId size=$size")
            }
            part.dispose()
        }
        
        if (metadata != null) {
            call.respond(metadata)
        } else {
            call.respond(HttpStatusCode.BadRequest, "No file found in multipart")
        }
    }

    // Download/Read uploaded file content
    get("/files/{id}/content") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        val fileId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val storagePath = services.fileRepository.getStoragePath(fileId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val file = File(storagePath)
        if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)
        
        call.respondFile(file)
    }

    // Download/Read host file directly (streaming)
    get("/files/download") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@get
        val path = call.request.queryParameters["path"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val file = resolveFilePath(path) ?: return@get call.respond(HttpStatusCode.Forbidden)
        if (!file.exists() || !file.isFile) return@get call.respond(HttpStatusCode.NotFound)
        call.respondFile(file)
    }

    // Delete an uploaded file
    delete("/session/{sessionId}/files/{fileId}") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@delete
        val fileId = call.parameters["fileId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val storagePath = services.fileRepository.getStoragePath(fileId)
        
        if (storagePath != null) {
            val file = File(storagePath)
            if (file.exists() && !file.delete()) {
                println("[FileRoutes] Warning: failed to delete file $storagePath")
            }
        }
        
        services.fileRepository.delete(fileId)
        services.auditRepository.log("file.delete", sessionId = call.parameters["sessionId"], details = "fileId=$fileId")
        call.respond(HttpStatusCode.NoContent)
    }

    post("/files/pull") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val request = call.receive<FileTransferRequest>()
        val file = resolveFilePath(request.path) ?: run {
            services.auditRepository.log("file.pull.denied", sessionId = request.sessionId, path = request.path, details = "outside file root")
            return@post call.respond(FileTransferResponse(request.sessionId, request.path, false, error = "Path outside allowed root"))
        }
        services.auditRepository.log("file.pull", sessionId = request.sessionId, path = file.path)
        if (!file.exists() || !file.isFile) return@post call.respond(FileTransferResponse(request.sessionId, request.path, false, error = "File not found"))
        
        if (file.length() > 5 * 1024 * 1024) { // 5MB limit for direct pull
            return@post call.respond(FileTransferResponse(request.sessionId, request.path, false, error = "File too large for direct pull. Use download endpoint."))
        }
        
        call.respond(FileTransferResponse(request.sessionId, request.path, true, content = file.readText()))
    }

    post("/files/push") {
        if (TrustAuth.requireTrust(call, services.stateStore) == null) return@post
        val request = call.receive<FileTransferRequest>()
        val file = resolveFilePath(request.path) ?: run {
            services.auditRepository.log("file.push.denied", sessionId = request.sessionId, path = request.path, details = "outside file root")
            return@post call.respond(FileTransferResponse(request.sessionId, request.path, false, error = "Path outside allowed root"))
        }
        val content = request.content ?: return@post call.respond(FileTransferResponse(request.sessionId, request.path, false, error = "No content provided"))
        services.auditRepository.log("file.push", sessionId = request.sessionId, path = file.path)
        try {
            file.writeText(content)
            call.respond(FileTransferResponse(request.sessionId, request.path, true))
        } catch (e: Exception) {
            call.respond(FileTransferResponse(request.sessionId, request.path, false, error = e.message))
        }
    }
}
