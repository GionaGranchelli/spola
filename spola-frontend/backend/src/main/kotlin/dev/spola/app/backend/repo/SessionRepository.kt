package dev.spola.app.backend.repo

import dev.spola.app.db.ChatSessionEntity
import dev.spola.app.db.SpolaDb
import dev.spola.app.models.ChatSession
import dev.spola.app.models.SpolaSessionSettings
import dev.spola.app.state.AppStateStore
import dev.spola.app.backend.normalizeProviderId

interface SessionRepository {
    fun getAll(): List<ChatSession>
    fun getById(id: String): ChatSession?
    fun create(session: ChatSession)
    fun delete(id: String)
    fun updateModel(id: String, modelId: String)
    fun getProvider(id: String): String
    fun saveProvider(id: String, providerId: String)
    fun getSpolaSettings(id: String): SpolaSessionSettings
    fun saveSpolaSettings(id: String, settings: SpolaSessionSettings)
}

class SqlSessionRepository(
    private val db: SpolaDb,
    private val stateStore: AppStateStore,
) : SessionRepository {

    override fun getAll(): List<ChatSession> {
        return db.spolaDbQueries.getAllSessions().executeAsList().map {
            ChatSession(it.id, it.title, it.createdAt, it.modelId, it.providerId)
        }
    }

    override fun getById(id: String): ChatSession? {
        return db.spolaDbQueries.getSessionById(id).executeAsOneOrNull()?.let {
            ChatSession(it.id, it.title, it.createdAt, it.modelId, it.providerId)
        }
    }

    override fun create(session: ChatSession) {
        println(
            "[SessionRepository] insert session id=${session.id} title=${session.title} " +
                "modelId=${session.modelId} providerId=${session.providerId}"
        )
        db.spolaDbQueries.insertSession(
            session.id,
            session.title,
            session.createdAt,
            session.modelId,
            session.providerId
        )
    }

    override fun delete(id: String) {
        db.spolaDbQueries.deleteMessagesBySessionId(id)
        db.spolaDbQueries.deleteSession(id)
        stateStore.clearSessionProvider(id)
        stateStore.clearSessionSpolaSettings(id)
    }

    override fun updateModel(id: String, modelId: String) {
        db.spolaDbQueries.updateSessionModel(modelId, id)
    }

    override fun getProvider(id: String): String {
        return normalizeProviderId(stateStore.loadSessionProvider(id))
    }

    override fun saveProvider(id: String, providerId: String) {
        stateStore.saveSessionProvider(id, normalizeProviderId(providerId))
    }

    override fun getSpolaSettings(id: String): SpolaSessionSettings {
        return stateStore.loadSessionSpolaSettings(id) ?: SpolaSessionSettings()
    }

    override fun saveSpolaSettings(id: String, settings: SpolaSessionSettings) {
        stateStore.saveSessionSpolaSettings(id, settings)
    }
}

fun normalizeSpolaSettings(raw: SpolaSessionSettings): SpolaSessionSettings {
    val mode = raw.mode?.trim()?.lowercase()?.takeIf { it == "local" || it == "gateway" }
    val thinking = raw.thinking?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    return SpolaSessionSettings(
        agentId = raw.agentId?.trim()?.takeIf { it.isNotEmpty() },
        modelId = raw.modelId?.trim()?.takeIf { it.isNotEmpty() },
        mode = mode,
        thinking = thinking,
    )
}

fun SpolaSessionSettings.isEmpty(): Boolean {
    return agentId.isNullOrBlank() &&
        modelId.isNullOrBlank() &&
        mode.isNullOrBlank() &&
        thinking.isNullOrBlank()
}
