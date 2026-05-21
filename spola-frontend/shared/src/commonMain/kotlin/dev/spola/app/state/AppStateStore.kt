package dev.spola.app.state

import dev.spola.app.db.SpolaDb
import dev.spola.app.models.AuditEvent
import dev.spola.app.models.SpolaSessionSettings
import dev.spola.app.models.SelectedSessionState
import dev.spola.app.models.TrustState
import dev.spola.app.state.currentTimeMillis
import kotlinx.serialization.json.Json

class AppStateStore(private val db: SpolaDb) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun sessionProviderKey(sessionId: String) = "session-provider:$sessionId"
    private fun sessionSpolaKey(sessionId: String) = "session-spola:$sessionId"

    fun loadSelectedSessionState(): SelectedSessionState? {
        val raw = db.spolaDbQueries.getAppState("selected-session").executeAsOneOrNull()
        return raw?.let { json.decodeFromString(SelectedSessionState.serializer(), it) }
    }

    fun saveSelectedSessionState(state: SelectedSessionState) {
        val payload = json.encodeToString(SelectedSessionState.serializer(), state)
        db.spolaDbQueries.upsertAppState("selected-session", payload)
    }

    fun loadTrustedHosts(): List<TrustState> {
        val raw = db.spolaDbQueries.getAppState("trusted-hosts").executeAsOneOrNull()
        return raw?.let { json.decodeFromString<List<TrustState>>(it) } ?: emptyList()
    }

    fun saveTrustedHosts(hosts: List<TrustState>) {
        val payload = json.encodeToString(hosts)
        db.spolaDbQueries.upsertAppState("trusted-hosts", payload)
    }

    fun loadTrustedHost(): TrustState? {
        val all = loadTrustedHosts()
        val activeId = db.spolaDbQueries.getAppState("active-trust-id").executeAsOneOrNull()
        return all.find { it.trustId == activeId } ?: all.find { it.active } ?: all.firstOrNull()
    }

    fun saveTrustedHost(state: TrustState) {
        val all = loadTrustedHosts().toMutableList()
        val index = all.indexOfFirst { it.trustId == state.trustId }
        if (index != -1) all[index] = state else all.add(state)
        saveTrustedHosts(all)
        db.spolaDbQueries.upsertAppState("active-trust-id", state.trustId)
    }

    fun revokeTrustedHost() {
        val current = loadTrustedHost()
        if (current != null) {
            saveTrustedHost(current.copy(active = false, revokedAt = currentTimeMillis()))
        }
    }

    fun loadSessionProvider(sessionId: String): String? {
        return db.spolaDbQueries.getAppState(sessionProviderKey(sessionId)).executeAsOneOrNull()
    }

    fun saveSessionProvider(sessionId: String, providerId: String) {
        db.spolaDbQueries.upsertAppState(sessionProviderKey(sessionId), providerId)
    }

    fun clearSessionProvider(sessionId: String) {
        db.spolaDbQueries.deleteAppState(sessionProviderKey(sessionId))
    }

    fun loadSessionSpolaSettings(sessionId: String): SpolaSessionSettings? {
        val raw = db.spolaDbQueries.getAppState(sessionSpolaKey(sessionId)).executeAsOneOrNull()
        return raw?.let { json.decodeFromString(SpolaSessionSettings.serializer(), it) }
    }

    fun saveSessionSpolaSettings(sessionId: String, settings: SpolaSessionSettings) {
        val payload = json.encodeToString(SpolaSessionSettings.serializer(), settings)
        db.spolaDbQueries.upsertAppState(sessionSpolaKey(sessionId), payload)
    }

    fun clearSessionSpolaSettings(sessionId: String) {
        db.spolaDbQueries.deleteAppState(sessionSpolaKey(sessionId))
    }

    fun writeAuditEvent(event: AuditEvent) {
        db.spolaDbQueries.insertAuditEvent(
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
