package dev.spola.app.backend.repo

import dev.spola.app.db.OpenClawDb
import dev.spola.app.models.AuditEvent
import java.util.UUID

interface AuditRepository {
    fun log(
        kind: String,
        sessionId: String? = null,
        approvalId: String? = null,
        path: String? = null,
        command: String? = null,
        details: String? = null,
    )
    fun getAll(): List<AuditEvent>
}

class SqlAuditRepository(private val db: OpenClawDb) : AuditRepository {
    override fun log(
        kind: String,
        sessionId: String?,
        approvalId: String?,
        path: String?,
        command: String?,
        details: String?,
    ) {
        db.openClawDbQueries.insertAuditEvent(
            id = UUID.randomUUID().toString(),
            kind = kind,
            sessionId = sessionId,
            approvalId = approvalId,
            path = path,
            command = command,
            timestamp = System.currentTimeMillis(),
            details = details,
        )
    }

    override fun getAll(): List<AuditEvent> {
        return db.openClawDbQueries.getAuditEvents().executeAsList().map {
            AuditEvent(it.id, it.kind, it.sessionId, it.approvalId, it.path, it.command, it.timestamp, it.details)
        }
    }
}
