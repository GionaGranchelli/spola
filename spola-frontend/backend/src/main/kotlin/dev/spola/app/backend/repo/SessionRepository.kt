package dev.spola.app.backend.repo

import dev.spola.app.db.ChatSessionEntity
import dev.spola.app.db.OpenClawDb
import dev.spola.app.models.ChatSession
import dev.spola.app.models.OpenClawSessionSettings
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
    fun getOpenClawSettings(id: String): OpenClawSessionSettings
    fun saveOpenClawSettings(id: String, settings: OpenClawSessionSettings)
}

class SqlSessionRepository(
    private val db: OpenClawDb,
    private val stateStore: AppStateStore,
) : SessionRepository {

    override fun getAll(): List<ChatSession> {
        return db.openClawDbQueries.getAllSessions().executeAsList().map {
            ChatSession(it.id, it.title, it.createdAt, it.modelId, it.providerId)
        }
    }

    override fun getById(id: String): ChatSession? {
        return db.openClawDbQueries.getSessionById(id).executeAsOneOrNull()?.let {
            ChatSession(it.id, it.title, it.createdAt, it.modelId, it.providerId)
        }
    }

    override fun create(session: ChatSession) {
        println(
            "[SessionRepository] insert session id=${session.id} title=${session.title} " +
                "modelId=${session.modelId} providerId=${session.providerId}"
        )
        db.openClawDbQueries.insertSession(
            session.id,
            session.title,
            session.createdAt,
            session.modelId,
            session.providerId
        )
    }

    override fun delete(id: String) {
        db.openClawDbQueries.deleteMessagesBySessionId(id)
        db.openClawDbQueries.deleteSession(id)
        stateStore.clearSessionProvider(id)
        stateStore.clearSessionOpenClawSettings(id)
    }

    override fun updateModel(id: String, modelId: String) {
        db.openClawDbQueries.updateSessionModel(modelId, id)
    }

    override fun getProvider(id: String): String {
        return normalizeProviderId(stateStore.loadSessionProvider(id))
    }

    override fun saveProvider(id: String, providerId: String) {
        stateStore.saveSessionProvider(id, normalizeProviderId(providerId))
    }

    override fun getOpenClawSettings(id: String): OpenClawSessionSettings {
        return stateStore.loadSessionOpenClawSettings(id) ?: OpenClawSessionSettings()
    }

    override fun saveOpenClawSettings(id: String, settings: OpenClawSessionSettings) {
        stateStore.saveSessionOpenClawSettings(id, settings)
    }
}

fun normalizeOpenClawSettings(raw: OpenClawSessionSettings): OpenClawSessionSettings {
    val mode = raw.mode?.trim()?.lowercase()?.takeIf { it == "local" || it == "gateway" }
    val thinking = raw.thinking?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    return OpenClawSessionSettings(
        agentId = raw.agentId?.trim()?.takeIf { it.isNotEmpty() },
        modelId = raw.modelId?.trim()?.takeIf { it.isNotEmpty() },
        mode = mode,
        thinking = thinking,
    )
}

fun OpenClawSessionSettings.isEmpty(): Boolean {
    return agentId.isNullOrBlank() &&
        modelId.isNullOrBlank() &&
        mode.isNullOrBlank() &&
        thinking.isNullOrBlank()
}
