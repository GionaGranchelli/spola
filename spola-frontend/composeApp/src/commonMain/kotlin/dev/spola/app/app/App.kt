package dev.spola.app.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.spola.app.app.decompose.*
import dev.spola.app.models.ChatSession
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.StreamEventType
import dev.spola.app.app.theme.GolemColors
import dev.spola.app.app.theme.GolemTheme

@Composable
fun App(component: RootComponent) {
    GolemTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize(), color = GolemColors.bg) {
            Children(stack = component.stack) {
                when (val child = it.instance) {
                    is RootComponent.Child.DashboardChild -> AppShell(dashboardComponent = child.component)
                    is RootComponent.Child.PairingChild -> PairingContent(child.component)
                }
            }
        }
    }
}

@Composable
fun PairingContent(component: PairingComponent) {
    var payload by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    val error by component.error.collectAsState(initial = null)
    val isLoading by component.isLoading.collectAsState(initial = false)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).then(Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Connect to Golem", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Run `golem --api` on your server, then paste the connection info below, " +
            "or enter the server URL and fetch it automatically.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        // Server URL auto-fetch
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL (e.g., http://192.168.1.100:8082)") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Server URL input"
                    role = Role.Button
                },
            singleLine = true,
            enabled = !isLoading,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { component.pairFromUrl(serverUrl) },
            enabled = serverUrl.isNotBlank() && !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Auto-connect to server"
                    role = Role.Button
                },
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isLoading) "Fetching..." else "Auto-Connect")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("— Or paste JSON manually —", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            label = { Text("Connection payload (JSON)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics {
                    contentDescription = "Connection payload JSON input"
                    role = Role.Button
                },
            enabled = !isLoading,
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { component.pair(payload) },
            enabled = payload.isNotBlank() && !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Save connection"
                    role = Role.Button
                },
        ) {
            Text("Save Connection")
        }

        Spacer(Modifier.height(16.dp))

        // QR Scan button (platform-specific)
        PairingScanButton(
            onScanned = { scannedText ->
                payload = scannedText
                component.pair(scannedText)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        // Expected format hint
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Expected JSON format:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    """{"host": "192.168.1.100", "port": 8082, "token": "your-api-key", "trustId": "optional-uuid"}""",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun DashboardContent(component: DashboardComponent) {
    val errorMessage by component.errorMessage.collectAsState(initial = null)

    Row(modifier = Modifier.fillMaxSize()) {
        // Left sidebar — session list
        SessionListPane(
            component = component.sessionList,
            modifier = Modifier.width(300.dp).fillMaxHeight(),
        )

        // Vertical divider
        Surface(
            modifier = Modifier.fillMaxHeight().width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        ) {}

        // Main content area
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardHeader(component)

            if (errorMessage != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(errorMessage.orEmpty(), modifier = Modifier.padding(12.dp))
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item {
                    Surface(modifier = Modifier.fillMaxWidth().height(540.dp), tonalElevation = 2.dp) {
                        AgentRunPane(component.agentRun, modifier = Modifier.fillMaxSize())
                    }
                }
                item {
                    Surface(modifier = Modifier.fillMaxWidth().height(320.dp), tonalElevation = 2.dp) {
                        ToolBrowserPane(component.toolBrowser)
                    }
                }
                item {
                    Surface(modifier = Modifier.fillMaxWidth().height(360.dp), tonalElevation = 2.dp) {
                        MemorySearchPane(component.memorySearch)
                    }
                }
                item {
                    Surface(modifier = Modifier.fillMaxWidth().height(280.dp), tonalElevation = 2.dp) {
                        SchedulerPane(component.schedulerList)
                    }
                }
            }
        }
    }
}

// ── Session List Sidebar ──────────────────────────────────────────

@Composable
private fun SessionListPane(component: SessionListComponent, modifier: Modifier = Modifier) {
    val sessions by component.sessions.subscribeAsState()
    val selectedId by component.selectedSessionId.subscribeAsState()
    val isLoading by component.isLoading.collectAsState()
    val createVisible by component.createDialogVisible.collectAsState()
    val error by component.error.collectAsState()

    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sessions", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp))
                    }
                    TextButton(
                        onClick = component::showCreateDialog,
                        modifier = Modifier.semantics {
                            contentDescription = "New session"
                            role = Role.Button
                        },
                    ) {
                        Text("+ New", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            HorizontalDivider()

            // Error display
            if (error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Session list
            if (sessions.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No sessions yet", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val modelsList = component.availableModels.subscribeAsState().value
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionListItem(
                            session = session,
                            isSelected = session.id == selectedId,
                            availableModels = modelsList,
                            onSelect = { component.selectSession(session.id) },
                            onDelete = { component.deleteSession(session.id) },
                            onChangeModel = { modelId -> component.changeSessionModel(session.id, modelId) },
                        )
                    }
                }
            }
        }
    }

    // Create session dialog
    if (createVisible) {
        CreateSessionDialog(component = component)
    }
}

@Composable
private fun SessionListItem(
    session: ChatSession,
    isSelected: Boolean,
    availableModels: List<ModelInfo>,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onChangeModel: (String) -> Unit,
) {
    var showModelMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .semantics {
                contentDescription = "Session: ${session.title}, model: ${session.modelId.take(24)}"
                role = if (isSelected) Role.Tab else Role.Button
            },
        color = bgColor,
        shape = MaterialTheme.shapes.small,
        tonalElevation = if (isSelected) 2.dp else 0.dp,
    ) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .height(24.dp)
                        .semantics {
                            contentDescription = "Delete session: ${session.title}"
                            role = Role.Button
                        },
                ) {
                    Text("×", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Model selector
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Model: ", style = MaterialTheme.typography.labelSmall)
                Box {
                    Text(
                        text = session.modelId.take(24),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable { showModelMenu = true }
                            .semantics {
                                contentDescription = "Change model for ${session.title}. Current: ${session.modelId.take(24)}"
                                role = Role.Button
                            },
                    )
                    DropdownMenu(
                        expanded = showModelMenu,
                        onDismissRequest = { showModelMenu = false },
                    ) {
                        if (availableModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No models available") },
                                onClick = { showModelMenu = false },
                            )
                        } else {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            model.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    onClick = {
                                        onChangeModel(model.id)
                                        showModelMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Timestamp
            Text(
                text = formatTimestamp(session.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Delete confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Session") },
            text = { Text("Delete \"${session.title}\"? This cannot be undone.") },
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
                    Text("Delete", color = MaterialTheme.colorScheme.error)
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

@Composable
private fun CreateSessionDialog(component: SessionListComponent) {
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

                // Model picker
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

// ── Existing Dashboard Components ──────────────────────────────────

@Composable
private fun DashboardHeader(component: DashboardComponent) {
    val currentHost by component.currentHost.collectAsState(initial = null)
    val trustedHosts by component.trustedHosts.collectAsState(initial = emptyList())
    var showHosts by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Golem Dashboard", style = MaterialTheme.typography.headlineSmall)

        Box {
            AssistChip(
                onClick = { if (trustedHosts.isNotEmpty()) showHosts = true },
                label = {
                    Text(
                        currentHost?.let { "${it.host}:${it.port}" } ?: "No host configured",
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = "Current host: ${currentHost?.let { "${it.host}:${it.port}" } ?: "none"}"
                    role = Role.Button
                },
            )
            DropdownMenu(expanded = showHosts, onDismissRequest = { showHosts = false }) {
                trustedHosts.forEach { host ->
                    DropdownMenuItem(
                        text = { Text("${host.host}:${host.port}") },
                        onClick = {
                            component.switchHost(host.trustId)
                            showHosts = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = component::openPairing,
            modifier = Modifier.semantics {
                contentDescription = "Configure host"
                role = Role.Button
            },
        ) { Text("Configure Host") }
    }
}

@Composable
private fun AgentRunPane(component: AgentRunComponent, modifier: Modifier = Modifier) {
    val goal by component.goal.subscribeAsState()
    val persona by component.persona.subscribeAsState()
    val events by component.events.subscribeAsState()
    val status by component.status.subscribeAsState()
    val finalResponse by component.finalResponse.subscribeAsState()
    val isRunning by component.isRunning.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.lastIndex)
        }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Agent Run", style = MaterialTheme.typography.titleLarge)
        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = goal,
            onValueChange = component::updateGoal,
            label = { Text("Goal") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Agent goal input"
                    role = Role.Button
                },
            minLines = 4,
        )

        OutlinedTextField(
            value = persona,
            onValueChange = component::updatePersona,
            label = { Text("Persona override (optional)") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Persona override input"
                    role = Role.Button
                },
            minLines = 3,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = component::startRun,
                enabled = goal.isNotBlank() && !isRunning,
                modifier = Modifier.semantics {
                    contentDescription = if (isRunning) "Agent is running" else "Start agent run"
                    role = Role.Button
                },
            ) {
                Text(if (isRunning) "Running..." else "Start Run")
            }
            TextButton(
                onClick = component::clearLog,
                enabled = events.isNotEmpty() || finalResponse.isNotBlank(),
                modifier = Modifier.semantics {
                    contentDescription = "Clear agent log"
                    role = Role.Button
                },
            ) {
                Text("Clear Log")
            }
        }

        if (finalResponse.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Final Response", style = MaterialTheme.typography.titleSmall)
                    Text(finalResponse, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(events) { index, event ->
                EventRow(event = event, index = index)
            }
        }
    }
}

@Composable
internal fun ToolBrowserPane(component: ToolBrowserComponent, modifier: Modifier = Modifier) {
    val tools by component.tools.subscribeAsState()
    val isLoading by component.isLoading.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelHeader(title = "Tool Browser", isLoading = isLoading, onRefresh = component::refresh)
        if (tools.isEmpty() && !isLoading) {
            EmptyPanelMessage("No tools found.")
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(tools) { index, tool ->
                KeyValueCard(
                    title = tool["name"].orEmpty().ifBlank { "Tool ${index + 1}" },
                    rows = listOf(
                        "Description" to tool["description"].orEmpty(),
                        "Parameters" to tool["parameters"].orEmpty(),
                    ),
                )
            }
        }
    }
}

@Composable
internal fun MemorySearchPane(component: MemorySearchComponent, modifier: Modifier = Modifier) {
    val query by component.query.subscribeAsState()
    val results by component.results.subscribeAsState()
    val isLoading by component.isLoading.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Memory Search", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = component::updateQuery,
                label = { Text("Search query") },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "Memory search query input"
                        role = Role.Button
                    },
                singleLine = true,
            )
            Button(
                onClick = component::search,
                enabled = query.isNotBlank() && !isLoading,
                modifier = Modifier.semantics {
                    contentDescription = "Search memory"
                    role = Role.Button
                },
            ) {
                Text("Search")
            }
        }

        if (results.isEmpty() && !isLoading) {
            EmptyPanelMessage("No memory entries loaded.")
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(results, key = { _, item -> item.first }) { _, item ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(item.first, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            Text(item.second, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(
                            onClick = { component.deleteEntry(item.first) },
                            enabled = !isLoading,
                            modifier = Modifier.semantics {
                                contentDescription = "Delete memory entry: ${item.first}"
                                role = Role.Button
                            },
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SchedulerPane(component: SchedulerListComponent, modifier: Modifier = Modifier) {
    val jobs by component.jobs.subscribeAsState()
    val isLoading by component.isLoading.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelHeader(title = "Scheduler", isLoading = isLoading, onRefresh = component::refresh)
        if (jobs.isEmpty() && !isLoading) {
            EmptyPanelMessage("No scheduled jobs found.")
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(jobs) { index, job ->
                KeyValueCard(
                    title = job["name"].orEmpty().ifBlank { "Job ${index + 1}" },
                    rows = listOf(
                        "Schedule" to job["schedule"].orEmpty(),
                        "Enabled" to job["enabled"].orEmpty(),
                        "Goal" to job["goal"].orEmpty(),
                    ),
                )
            }
        }
    }
}

@Composable
internal fun PanelHeader(title: String, isLoading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp))
        }
        TextButton(
            onClick = onRefresh,
            enabled = !isLoading,
            modifier = Modifier.semantics {
                contentDescription = "Refresh $title"
                role = Role.Button
            },
        ) {
            Text("Refresh")
        }
    }
}

@Composable
internal fun KeyValueCard(title: String, rows: List<Pair<String, String>>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.filter { it.second.isNotBlank() }.forEach { (label, value) ->
                Text("$label: $value", style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun EmptyPanelMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EventRow(event: StreamEvent, index: Int) {
    Surface(
        color = when (event.type) {
            StreamEventType.error -> MaterialTheme.colorScheme.errorContainer
            StreamEventType.complete -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "#${index + 1} ${event.type.name}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (!event.toolName.isNullOrBlank()) {
                Text("Tool: ${event.toolName}", style = MaterialTheme.typography.bodySmall)
            }
            if (!event.toolArgs.isNullOrBlank()) {
                Text(event.toolArgs.orEmpty(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (!event.content.isNullOrBlank()) {
                Text(
                    event.content.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (event.type == StreamEventType.token) FontFamily.Monospace else FontFamily.Default,
                )
            }
        }
    }
}

// ── Utility ────────────────────────────────────────────────────────

private fun formatTimestamp(millis: Long): String {
    // Simple UTC timestamp formatting without external dependencies
    val totalSeconds = millis / 1000
    val days = totalSeconds / 86400
    val timeOfDay = totalSeconds % 86400
    val hours = timeOfDay / 3600
    val minutes = (timeOfDay % 3600) / 60

    // Approximate date from epoch (days since 1970-01-01)
    val year = 1970 + (days / 365).toInt()
    val month = ((days % 365) / 30).toInt().coerceIn(1, 12)
    val day = ((days % 365) % 30).toInt().coerceIn(1, 31)

    return "${year}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} " +
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}
