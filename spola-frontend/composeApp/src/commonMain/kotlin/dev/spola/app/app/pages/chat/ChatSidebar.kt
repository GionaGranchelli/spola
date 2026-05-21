package dev.spola.app.app.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.spola.app.app.components.EmptyState
import dev.spola.app.app.components.LoadingSkeletonList
import dev.spola.app.app.decompose.SessionListComponent
import dev.spola.app.app.theme.SpolaColors
import dev.spola.app.models.ChatSession
import dev.spola.app.models.ModelInfo

@Composable
fun ChatSidebar(
    sessions: List<ChatSession>,
    selectedSessionId: String,
    isLoading: Boolean,
    models: List<ModelInfo>,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onChangeModel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = SpolaColors.bgSurface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sessions", color = SpolaColors.textPrimary, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = SpolaColors.textMuted,
                        )
                    }
                    TextButton(
                        onClick = onNewSession,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "New session"
                            role = Role.Button
                        },
                    ) {
                        Text("+ New", fontSize = 12.sp, color = SpolaColors.accent)
                    }
                }
            }

            HorizontalDivider(color = SpolaColors.bgElevated)

            when {
                isLoading -> {
                    LoadingSkeletonList(
                        count = 4,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                sessions.isEmpty() -> {
                    EmptyState(
                        emoji = "💬",
                        title = "No sessions yet",
                        message = "Create a new session to start chatting with the Spola agent.",
                        actionLabel = "New Session",
                        onAction = onNewSession,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            AnimatedVisibility(
                                visible = true,
                                enter = slideInHorizontally { -it / 2 } + fadeIn(animationSpec = tween(300)),
                                label = "sessionItemAnim",
                            ) {
                                ChatSidebarItem(
                                    session = session,
                                    isSelected = session.id == selectedSessionId,
                                    models = models,
                                    onSelect = { onSelectSession(session.id) },
                                    onDelete = { onDeleteSession(session.id) },
                                    onChangeModel = { modelId -> onChangeModel(session.id, modelId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatCreateSessionDialog(component: SessionListComponent) {
    val name by component.newSessionName.subscribeAsState()
    val modelId by component.newSessionModelId.subscribeAsState()
    val models by component.availableModels.subscribeAsState()
    val isLoading by component.isLoading.collectAsState()
    var showModelMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) component.hideCreateDialog() },
        title = { Text("New Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = component::updateNewSessionName,
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "New session name"
                            role = Role.Button
                        },
                )

                Column {
                    Text("Model:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    val selectedModel = models.find { it.id == modelId }
                    Box {
                        OutlinedButton(
                            onClick = { showModelMenu = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Select model for new session"
                                    role = Role.Button
                                },
                        ) {
                            Text(
                                selectedModel?.name ?: modelId.ifBlank { "Select model..." },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false },
                        ) {
                            if (models.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No models found") },
                                    onClick = { showModelMenu = false },
                                )
                            } else {
                                models.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    "${model.provider} — ${model.description ?: ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        onClick = {
                                            component.updateNewSessionModel(model.id)
                                            showModelMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = component::createSession,
                enabled = name.isNotBlank() && !isLoading && models.isNotEmpty(),
                modifier = Modifier.semantics {
                    contentDescription = "Create new session"
                    role = Role.Button
                },
            ) {
                Text(if (isLoading) "Creating..." else "Create")
            }
        },
        dismissButton = {
            TextButton(
                onClick = component::hideCreateDialog,
                enabled = !isLoading,
                modifier = Modifier.semantics {
                    contentDescription = "Cancel creating session"
                    role = Role.Button
                },
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ChatSidebarItem(
    session: ChatSession,
    isSelected: Boolean,
    @Suppress("UNUSED_PARAMETER") models: List<ModelInfo>,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onChangeModel: (String) -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val bgColor = if (isSelected) SpolaColors.accent.copy(alpha = 0.12f) else SpolaColors.bgSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .semantics {
                contentDescription = "Session: ${session.title}, model: ${session.modelId.take(20)}"
                role = if (isSelected) Role.Tab else Role.Button
            }
            .padding(horizontal = 6.dp),
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.title,
                    color = if (isSelected) SpolaColors.textPrimary else SpolaColors.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.82f),
                )
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier
                        .size(24.dp)
                        .semantics {
                            contentDescription = "Delete session: ${session.title}"
                            role = Role.Button
                        },
                ) {
                    Text("×", color = SpolaColors.textMuted, fontSize = 14.sp)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = session.modelId.take(20),
                    color = SpolaColors.accent.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatShortTimestamp(session.createdAt),
                    color = SpolaColors.textMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Session") },
            text = { Text("Delete \"${session.title}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm delete session"
                        role = Role.Button
                    },
                ) {
                    Text("Delete", color = SpolaColors.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel delete session"
                        role = Role.Button
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}
