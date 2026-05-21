package dev.spola.app.app.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.spola.app.app.components.EmptyState
import dev.spola.app.app.components.LoadingSkeletonCard
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.app.theme.SpolaColors
import dev.spola.app.models.KanbanCard
import dev.spola.app.models.TrustState
import dev.spola.app.network.SpolaClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

private const val NO_CONNECTED_HOST = "No connected host"

private data class KanbanBoardCallbacks(
    val onDraftChange: (String) -> Unit,
    val onSavingChange: (Boolean) -> Unit,
    val onErrorChange: (String?) -> Unit,
    val onRefresh: suspend () -> Unit,
)

private suspend fun refreshKanbanCards(
    client: SpolaClient?,
    onCardsChange: (List<KanbanCard>) -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    onErrorChange: (String?) -> Unit,
) {
    if (client == null) {
        onCardsChange(emptyList())
        onLoadingChange(false)
        onErrorChange(NO_CONNECTED_HOST)
        return
    }
    onLoadingChange(true)
    runCatching { client.getKanbanCards() }
        .onSuccess {
            onCardsChange(it.sortedByDescending(KanbanCard::createdAt))
            onErrorChange(null)
        }
        .onFailure {
            onErrorChange(it.message ?: "Failed to load cards")
        }
    onLoadingChange(false)
}

@Composable
fun KanbanPage(
    dashboardComponent: DashboardComponent? = null,
    modifier: Modifier = Modifier,
) {
    val currentHost by dashboardComponent?.currentHost?.collectAsState() ?: remember { mutableStateOf<TrustState?>(null) }
    val client = rememberKanbanClient(currentHost)
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var cards by remember(currentHost) { mutableStateOf<List<KanbanCard>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var isLoading by remember(currentHost) { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember(currentHost) { mutableStateOf<String?>(null) }

    LaunchedEffect(client) {
        refreshKanbanCards(
            client = client,
            onCardsChange = { cards = it },
            onLoadingChange = { isLoading = it },
            onErrorChange = { error = it },
        )
    }

    when {
        client == null -> EmptyState(
            emoji = "📋",
            title = "Kanban unavailable",
            message = "Connect to a host to manage the board.",
            modifier = modifier,
        )

        isLoading -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(4) { LoadingSkeletonCard(height = 120.dp) }
        }

        else -> KanbanBoardContent(
            modifier = modifier,
            scrollState = scrollState,
            scope = scope,
            client = client,
            cards = cards,
            draft = draft,
            isSaving = isSaving,
            error = error,
            callbacks = KanbanBoardCallbacks(
                onDraftChange = { draft = it },
                onSavingChange = { isSaving = it },
                onErrorChange = { error = it },
                onRefresh = {
                refreshKanbanCards(
                    client = client,
                    onCardsChange = { cards = it },
                    onLoadingChange = { isLoading = it },
                    onErrorChange = { error = it },
                )
                },
            ),
        )
    }
}

@Composable
private fun KanbanBoardContent(
    modifier: Modifier,
    scrollState: androidx.compose.foundation.ScrollState,
    scope: kotlinx.coroutines.CoroutineScope,
    client: SpolaClient?,
    cards: List<KanbanCard>,
    draft: String,
    isSaving: Boolean,
    error: String?,
    callbacks: KanbanBoardCallbacks,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        KanbanCardForm(
            scope = scope,
            client = client,
            draft = draft,
            isSaving = isSaving,
            error = error,
            callbacks = callbacks,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KanbanColumn(
                title = "To Do",
                status = "todo",
                cards = cards.filter { it.status == "todo" },
                accent = SpolaColors.warning,
                onMoveLeft = null,
                onMoveRight = { card ->
                    scope.launch {
                        updateCard(client, card, "in_progress", callbacks.onRefresh).also(callbacks.onErrorChange)
                    }
                },
                onDelete = { card ->
                    scope.launch { deleteCard(client, card.id, callbacks.onRefresh).also(callbacks.onErrorChange) }
                },
            )
            KanbanColumn(
                title = "In Progress",
                status = "in_progress",
                cards = cards.filter { it.status == "in_progress" },
                accent = SpolaColors.accent,
                onMoveLeft = { card ->
                    scope.launch { updateCard(client, card, "todo", callbacks.onRefresh).also(callbacks.onErrorChange) }
                },
                onMoveRight = { card ->
                    scope.launch { updateCard(client, card, "done", callbacks.onRefresh).also(callbacks.onErrorChange) }
                },
                onDelete = { card ->
                    scope.launch { deleteCard(client, card.id, callbacks.onRefresh).also(callbacks.onErrorChange) }
                },
            )
            KanbanColumn(
                title = "Done",
                status = "done",
                cards = cards.filter { it.status == "done" },
                accent = SpolaColors.success,
                onMoveLeft = { card ->
                    scope.launch {
                        updateCard(client, card, "in_progress", callbacks.onRefresh).also(callbacks.onErrorChange)
                    }
                },
                onMoveRight = null,
                onDelete = { card ->
                    scope.launch { deleteCard(client, card.id, callbacks.onRefresh).also(callbacks.onErrorChange) }
                },
            )
        }
    }
}

private fun launchCreateCard(
    scope: kotlinx.coroutines.CoroutineScope,
    client: SpolaClient?,
    draft: String,
    isSaving: Boolean,
    callbacks: KanbanBoardCallbacks,
) {
    scope.launch {
        val text = draft.trim()
        if (text.isBlank() || client == null || isSaving) return@launch
        callbacks.onSavingChange(true)
        runCatching { client.createKanbanCard(text) }
            .onSuccess {
                callbacks.onDraftChange("")
                callbacks.onRefresh()
            }
            .onFailure {
                callbacks.onErrorChange(it.message ?: "Failed to create card")
            }
        callbacks.onSavingChange(false)
    }
}

@Composable
private fun KanbanCardForm(
    scope: kotlinx.coroutines.CoroutineScope,
    client: SpolaClient?,
    draft: String,
    isSaving: Boolean,
    error: String?,
    callbacks: KanbanBoardCallbacks,
) {
    Surface(
        color = SpolaColors.bgSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Kanban Board",
                color = SpolaColors.textPrimary,
                fontSize = 18.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Track lightweight work across three stages.",
                color = SpolaColors.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = callbacks.onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Add a task", color = SpolaColors.textMuted, fontSize = 13.sp)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpolaColors.accent.copy(alpha = 0.5f),
                        unfocusedBorderColor = SpolaColors.bgElevated,
                        focusedContainerColor = SpolaColors.bg,
                        unfocusedContainerColor = SpolaColors.bg,
                        cursorColor = SpolaColors.accent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                Button(
                    onClick = { launchCreateCard(scope, client, draft, isSaving, callbacks) },
                    enabled = draft.isNotBlank() && !isSaving,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpolaColors.accent),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = SpolaColors.textPrimary,
                        )
                    } else {
                        Text("Add")
                    }
                }
            }
            if (error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = error,
                    color = SpolaColors.error,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun KanbanColumn(
    title: String,
    status: String,
    cards: List<KanbanCard>,
    accent: androidx.compose.ui.graphics.Color,
    onMoveLeft: ((KanbanCard) -> Unit)?,
    onMoveRight: ((KanbanCard) -> Unit)?,
    onDelete: (KanbanCard) -> Unit,
) {
    Surface(
        color = SpolaColors.bgSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    color = SpolaColors.textPrimary,
                    fontSize = 15.sp,
                )
                Surface(
                    color = accent.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = cards.size.toString(),
                        color = accent,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            HorizontalDivider(color = SpolaColors.bgElevated)
            if (cards.isEmpty()) {
                KanbanColumnEmptyState(status = status)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        KanbanCardItem(
                            card = card,
                            onMoveLeft = onMoveLeft,
                            onMoveRight = onMoveRight,
                            onDelete = onDelete,
                            accent = accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KanbanColumnEmptyState(status: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (status) {
                "todo" -> "No tasks queued"
                "in_progress" -> "Nothing active"
                else -> "No completed cards"
            },
            color = SpolaColors.textMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun KanbanCardItem(
    card: KanbanCard,
    onMoveLeft: ((KanbanCard) -> Unit)?,
    onMoveRight: ((KanbanCard) -> Unit)?,
    onDelete: (KanbanCard) -> Unit,
    accent: androidx.compose.ui.graphics.Color,
) {
    Surface(
        color = SpolaColors.bg,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = card.text,
                color = SpolaColors.textPrimary,
                fontSize = 13.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onMoveLeft != null) {
                    Button(
                        onClick = { onMoveLeft(card) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SpolaColors.bgElevated),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Text("←", color = SpolaColors.textPrimary)
                    }
                }
                if (onMoveRight != null) {
                    Button(
                        onClick = { onMoveRight(card) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Text("→")
                    }
                }
                Button(
                    onClick = { onDelete(card) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpolaColors.error.copy(alpha = 0.85f)),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

private suspend fun updateCard(
    client: SpolaClient?,
    card: KanbanCard,
    status: String,
    onSuccess: suspend () -> Unit,
): String? {
    if (client == null) {
        return NO_CONNECTED_HOST
    }
    return runCatching {
        client.updateKanbanCard(card.id, card.text, status)
        onSuccess()
    }.fold(
        onSuccess = { null },
        onFailure = { it.message ?: "Update failed" }
    )
}

private suspend fun deleteCard(
    client: SpolaClient?,
    id: String,
    onSuccess: suspend () -> Unit,
): String? {
    if (client == null) {
        return NO_CONNECTED_HOST
    }
    return runCatching {
        client.deleteKanbanCard(id)
        onSuccess()
        null
    }.getOrElse {
        it.message ?: "Failed to delete card"
    }
}

@Composable
private fun rememberKanbanClient(currentHost: TrustState?): SpolaClient? {
    val client = remember(currentHost?.trustId, currentHost?.host, currentHost?.port, currentHost?.token) {
        currentHost?.let { trust ->
            SpolaClient(
                httpClient = HttpClient(),
                baseUrl = "http://${trust.host}:${trust.port}/",
                authToken = trust.token,
            )
        }
    }

    DisposableEffect(client) {
        onDispose {
            client?.close()
        }
    }

    return client
}
