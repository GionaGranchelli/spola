package dev.spola.app.backend.repo

import dev.spola.app.db.OpenClawDb
import dev.spola.app.models.FileMetadata
import java.io.File

interface FileRepository {
    fun save(metadata: FileMetadata, storagePath: String)
    fun getBySessionId(sessionId: String): List<FileMetadata>
    fun getById(id: String): FileMetadata?
    fun getStoragePath(id: String): String?
    fun delete(id: String)
}

class SqlFileRepository(private val db: OpenClawDb) : FileRepository {
    override fun save(metadata: FileMetadata, storagePath: String) {
        db.openClawDbQueries.insertFile(
            id = metadata.id,
            sessionId = metadata.sessionId,
            name = metadata.name,
            mimeType = metadata.mimeType,
            size = metadata.size,
            storagePath = storagePath,
            timestamp = metadata.timestamp
        )
    }

    override fun getBySessionId(sessionId: String): List<FileMetadata> {
        return db.openClawDbQueries.getFilesBySessionId(sessionId).executeAsList().map {
            FileMetadata(it.id, it.sessionId, it.name, it.mimeType, it.size, it.timestamp)
        }
    }

    override fun getById(id: String): FileMetadata? {
        return db.openClawDbQueries.getFileById(id).executeAsOneOrNull()?.let {
            FileMetadata(it.id, it.sessionId, it.name, it.mimeType, it.size, it.timestamp)
        }
    }

    override fun getStoragePath(id: String): String? {
        return db.openClawDbQueries.getFileById(id).executeAsOneOrNull()?.storagePath
    }

    override fun delete(id: String) {
        db.openClawDbQueries.deleteFile(id)
    }
}

val uploadsRoot = File(System.getProperty("user.home"), ".openclaw/uploads").also { if (!it.exists()) it.mkdirs() }
