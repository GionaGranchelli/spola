package dev.spola.app.state

import it.openclaw.db.OpenClawDb
import dev.spola.app.models.AuditEvent
import dev.spola.app.models.OpenClawSessionSettings
import dev.spola.app.models.SelectedSessionState
import dev.spola.app.models.TrustState
import dev.spola.app.state.currentTimeMillis
import kotlinx.serialization.json.Json

class AppStateStore(private val db: OpenClawDb) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun sessionProviderKey(sessionId: String) = "session-provider:$sessionId"
    private fun sessionOpenClawKey(sessionId: String) = "session-openclaw:$sessionId"

    fun loadSelectedSessionState(): SelectedSessionState? {
        val raw = db.openClawDbQueries.getAppState("selected-session").executeAsOneOrNull()
        return raw?.let { json.decodeFromString(SelectedSessionState.serializer(), it) }
    }

    fun saveSelectedSessionState(state: SelectedSessionState) {
        val payload = json.encodeToString(SelectedSessionState.serializer(), state)
        db.openClawDbQueries.upsertAppState("selected-session", payload)
    }

    fun loadTrustedHosts(): List<TrustState> {
        val raw = db.openClawDbQueries.getAppState("trusted-hosts").executeAsOneOrNull()
        return raw?.let { json.decodeFromString<List<TrustState>>(it) } ?: emptyList()
    }

    fun saveTrustedHosts(hosts: List<TrustState>) {
        val payload = json.encodeToString(hosts)
        db.openClawDbQueries.upsertAppState("trusted-hosts", payload)
    }

    fun loadTrustedHost(): TrustState? {
        val all = loadTrustedHosts()
        val activeId = db.openClawDbQueries.getAppState("active-trust-id").executeAsOneOrNull()
        return all.find { it.trustId == activeId } ?: all.find { it.active } ?: all.firstOrNull()
    }

    fun saveTrustedHost(state: TrustState) {
        val all = loadTrustedHosts().toMutableList()
        val index = all.indexOfFirst { it.trustId == state.trustId }
        if (index != -1) all[index] = state else all.add(state)
        saveTrustedHosts(all)
        db.openClawDbQueries.upsertAppState("active-trust-id", state.trustId)
    }

    fun revokeTrustedHost() {
        val current = loadTrustedHost()
        if (current != null) {
            saveTrustedHost(current.copy(active = false, revokedAt = currentTimeMillis()))
        }
    }

    fun loadSessionProvider(sessionId: String): String? {
        return db.openClawDbQueries.getAppState(sessionProviderKey(sessionId)).executeAsOneOrNull()
    }

    fun saveSessionProvider(sessionId: String, providerId: String) {
        db.openClawDbQueries.upsertAppState(sessionProviderKey(sessionId), providerId)
    }

    fun clearSessionProvider(sessionId: String) {
        db.openClawDbQueries.deleteAppState(sessionProviderKey(sessionId))
    }

    fun loadSessionOpenClawSettings(sessionId: String): OpenClawSessionSettings? {
        val raw = db.openClawDbQueries.getAppState(sessionOpenClawKey(sessionId)).executeAsOneOrNull()
        return raw?.let { json.decodeFromString(OpenClawSessionSettings.serializer(), it) }
    }

    fun saveSessionOpenClawSettings(sessionId: String, settings: OpenClawSessionSettings) {
        val payload = json.encodeToString(OpenClawSessionSettings.serializer(), settings)
        db.openClawDbQueries.upsertAppState(sessionOpenClawKey(sessionId), payload)
    }

    fun clearSessionOpenClawSettings(sessionId: String) {
        db.openClawDbQueries.deleteAppState(sessionOpenClawKey(sessionId))
    }

    fun writeAuditEvent(event: AuditEvent) {
        db.openClawDbQueries.insertAuditEvent(
            event.id,
            event.kind,
            event.sessionId,
            event.approvalId,
            event.path,
            event.command,
            event.timestamp,
            event.details,
        )
    }
}
