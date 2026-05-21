package dev.spola.app.app.pages

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.spola.app.app.components.EmptyState
import dev.spola.app.app.components.LoadingSkeletonCard
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.app.theme.SpolaColors
import dev.spola.app.models.TrustState
import dev.spola.app.models.WorkflowDefinition
import dev.spola.app.models.WorkflowExecutionRecord
import dev.spola.app.network.SpolaClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import androidx.compose.runtime.collectAsState

private enum class WorkflowsView { DEFINITIONS, EXECUTIONS }

@Composable
fun WorkflowsPage(
    dashboardComponent: DashboardComponent,
    modifier: Modifier = Modifier,
) {
    val currentHost by dashboardComponent.currentHost.collectAsState()
    val client = rememberSpolaClient(currentHost)
    val scope = rememberCoroutineScope()
    var workflows by remember(currentHost) { mutableStateOf<List<WorkflowDefinition>>(emptyList()) }
    var executions by remember(currentHost) { mutableStateOf<List<WorkflowExecutionRecord>>(emptyList()) }
    var isLoading by remember(currentHost) { mutableStateOf(true) }
    var togglingId by remember { mutableStateOf<String?>(null) }
    var error by remember(currentHost) { mutableStateOf<String?>(null) }
    var currentView by remember { mutableStateOf(WorkflowsView.DEFINITIONS) }

    // Dialog state
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingWorkflow by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }

    // Run workflow dialog
    var showRunDialog by remember { mutableStateOf(false) }
    var runGoal by remember { mutableStateOf("") }
    var selectedWorkflowName by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }

    // Execution detail dialog
    var selectedExecution by remember { mutableStateOf<WorkflowExecutionRecord?>(null) }

    // Coming soon dialog for CRUD (definitions are read-only templates)
    var showComingSoon by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client) {
        if (client == null) {
            workflows = emptyList()
            executions = emptyList()
            isLoading = false
            error = "No connected host"
            return@LaunchedEffect
        }

        isLoading = true
        error = null
        runCatching {
            val wfs = client.getWorkflowDefinitions()
            val execs = client.getWorkflowExecutions(limit = 50)
            workflows = wfs
            executions = execs
        }.onFailure {
            error = it.message ?: "Failed to load"
        }
        isLoading = false
    }

    val refresh = {
        scope.launch {
            isLoading = true
            error = null
            runCatching {
                when (currentView) {
                    WorkflowsView.DEFINITIONS -> workflows = client!!.getWorkflowDefinitions()
                    WorkflowsView.EXECUTIONS -> executions = client!!.getWorkflowExecutions()
                }
            }.onFailure {
                error = it.message ?: "Failed to load"
            }
            isLoading = false
        }
    }

    when {
        client == null -> EmptyState(
            emoji = "\uD83D\uDD04",
            title = "Workflows unavailable",
            message = "Connect to a host to manage workflows and executions.",
            modifier = modifier,
        )

        else -> Column(
            modifier = modifier.fillMaxSize(),
        ) {
            // Header with view toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (currentView == WorkflowsView.DEFINITIONS) "Workflows (${workflows.size})" else "Executions (${executions.size})",
                    color = SpolaColors.textPrimary,
                    fontSize = 18.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            currentView = WorkflowsView.DEFINITIONS
                            refresh()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "Definitions",
                            fontSize = 12.sp,
                            color = if (currentView == WorkflowsView.DEFINITIONS) SpolaColors.accent else SpolaColors.textMuted,
                        )
                    }
                    TextButton(
                        onClick = {
                            currentView = WorkflowsView.EXECUTIONS
                            refresh()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "Executions",
                            fontSize = 12.sp,
                            color = if (currentView == WorkflowsView.EXECUTIONS) SpolaColors.accent else SpolaColors.textMuted,
                        )
                    }
                    if (currentView == WorkflowsView.DEFINITIONS) {
                        Button(
                            onClick = { showCreateDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SpolaColors.accent,
                                contentColor = SpolaColors.bgSurface,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("+ New", fontSize = 13.sp)
                        }
                    }
                }
            }

            if (error != null) {
                Surface(
                    color = SpolaColors.error.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = error.orEmpty(),
                        color = SpolaColors.textPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (isLoading) {
                WorkflowLoadingState(modifier = Modifier.weight(1f))
            } else {
                when (currentView) {
                    WorkflowsView.DEFINITIONS -> {
                        if (workflows.isEmpty()) {
                            EmptyState(
                                emoji = "\uD83D\uDD04",
                                title = "No workflows defined",
                                message = "Create a workflow definition to get started.",
                                modifier = Modifier.weight(1f).padding(16.dp),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(workflows, key = { it.id }) { workflow ->
                                    WorkflowCard(
                                        workflow = workflow,
                                        isToggling = togglingId == workflow.id,
                                        isDeleting = deletingId == workflow.id,
                                        onToggle = { showComingSoon = "Coming soon" },
                                        onEdit = { editingWorkflow = workflow },
                            onDelete = {
                                showComingSoon = "Coming soon"
                            },
                                        onRun = {
                                            selectedWorkflowName = workflow.name
                                            runGoal = ""
                                            showRunDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    }

                    WorkflowsView.EXECUTIONS -> {
                        if (executions.isEmpty()) {
                            EmptyState(
                                emoji = "\uD83D\uDCCA",
                                title = "No executions yet",
                                message = "Run a workflow to see its execution history here.",
                                modifier = Modifier.weight(1f).padding(16.dp),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(executions, key = { it.id }) { execution ->
                                    ExecutionCard(
                                        execution = execution,
                                        onClick = { selectedExecution = execution },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        WorkflowFormDialog(
            title = "Create Workflow",
            initialName = "",
            initialDescription = "",
            onConfirm = { _, _ ->
                showCreateDialog = false
                showComingSoon = "Coming soon"
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    // Edit dialog
    editingWorkflow?.let { wf ->
        WorkflowFormDialog(
            title = "Edit Workflow",
            initialName = wf.name,
            initialDescription = wf.description,
            onConfirm = { _, _ ->
                editingWorkflow = null
                showComingSoon = "Coming soon"
            },
            onDismiss = { editingWorkflow = null },
        )
    }

    // Run workflow dialog
    if (showRunDialog) {
        AlertDialog(
            onDismissRequest = { showRunDialog = false },
            title = { Text("Run: $selectedWorkflowName", color = SpolaColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = runGoal,
                        onValueChange = { runGoal = it },
                        label = { Text("Goal") },
                        placeholder = { Text("Describe what this workflow should do...") },
                        minLines = 3,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SpolaColors.textPrimary,
                            unfocusedTextColor = SpolaColors.textPrimary,
                            focusedBorderColor = SpolaColors.accent,
                            unfocusedBorderColor = SpolaColors.bgElevated,
                            cursorColor = SpolaColors.accent,
                            focusedLabelColor = SpolaColors.accent,
                            unfocusedLabelColor = SpolaColors.textMuted,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isRunning) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = SpolaColors.accent,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (runGoal.isBlank()) return@Button
                        isRunning = true
                        scope.launch {
                            error = null
                            runCatching {
                                val resp = client!!.runWorkflow(
                                    workflowName = selectedWorkflowName,
                                    goal = runGoal,
                                )
                                "Execution started: ${resp.executionId}"
                            }.onSuccess { msg ->
                                showRunDialog = false
                                isRunning = false
                                // Switch to executions view and refresh
                                currentView = WorkflowsView.EXECUTIONS
                                refresh()
                            }.onFailure {
                                error = it.message ?: "Failed to run workflow"
                                isRunning = false
                            }
                        }
                    },
                    enabled = runGoal.isNotBlank() && !isRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpolaColors.accent,
                        contentColor = SpolaColors.bgSurface,
                    ),
                ) {
                    Text("Run")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRunDialog = false },
                    enabled = !isRunning,
                ) {
                    Text("Cancel", color = SpolaColors.textMuted)
                }
            },
        )
    }

    // Execution detail dialog
    selectedExecution?.let { exec ->
        ExecutionDetailDialog(
            execution = exec,
            onDismiss = { selectedExecution = null },
        )
    }

    // Coming soon dialog
    showComingSoon?.let { message ->
        AlertDialog(
            onDismissRequest = { showComingSoon = null },
            title = { Text("\uD83D\uDD27", color = SpolaColors.textPrimary) },
            text = {
                Text(
                    text = "Workflow definition CRUD is coming in a future update.\n\nTemplate-based workflows are read-only for now — use the Run button to execute them.",
                    color = SpolaColors.textSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showComingSoon = null }) {
                    Text("OK", color = SpolaColors.accent)
                }
            },
        )
    }
}

@Composable
private fun WorkflowCard(
    workflow: WorkflowDefinition,
    isToggling: Boolean,
    isDeleting: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
) {
    Surface(
        color = SpolaColors.bgSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workflow.name,
                        color = SpolaColors.textPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = workflow.description,
                        color = SpolaColors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
                // Run button
                Button(
                    onClick = onRun,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpolaColors.success.copy(alpha = 0.15f),
                        contentColor = SpolaColors.success,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("\u25B6", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    if (isToggling) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(24.dp),
                            strokeWidth = 2.dp,
                            color = SpolaColors.accent,
                        )
                    } else {
                        Switch(
                            checked = workflow.enabled,
                            onCheckedChange = onToggle,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = SpolaColors.bgElevated)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (workflow.enabled) "Enabled" else "Disabled",
                    color = if (workflow.enabled) SpolaColors.success else SpolaColors.textMuted,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("Edit", fontSize = 12.sp, color = SpolaColors.accent)
                    }
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                            color = SpolaColors.error,
                        )
                    } else {
                        TextButton(
                            onClick = onDelete,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = SpolaColors.error),
                        ) {
                            Text("Delete", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionCard(
    execution: WorkflowExecutionRecord,
    onClick: () -> Unit,
) {
    val statusColor = when (execution.status) {
        "QUEUED" -> SpolaColors.warning
        "RUNNING" -> SpolaColors.accent
        "WAITING_APPROVAL" -> SpolaColors.warning
        "COMPLETED" -> SpolaColors.success
        "FAILED" -> SpolaColors.error
        "CANCELLED" -> SpolaColors.textMuted
        else -> SpolaColors.textMuted
    }

    Surface(
        color = SpolaColors.bgSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status indicator
            Surface(
                color = statusColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = when (execution.status) {
                        "QUEUED" -> "\u23F3"
                        "RUNNING" -> "\u25B6"
                        "WAITING_APPROVAL" -> "\uD83D\uDCAC"
                        "COMPLETED" -> "\u2705"
                        "FAILED" -> "\u274C"
                        "CANCELLED" -> "\uD83D\uDEAB"
                        else -> "\u2753"
                    },
                    modifier = Modifier.padding(6.dp),
                    fontSize = 16.sp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = execution.workflowName,
                    color = SpolaColors.textPrimary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = execution.status,
                        color = statusColor,
                        fontSize = 11.sp,
                    )
                    if (execution.error != null) {
                        Text(
                            text = execution.error ?: "",
                            color = SpolaColors.error,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            Text(
                text = formatTimestamp(execution.createdAt),
                color = SpolaColors.textMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ExecutionDetailDialog(
    execution: WorkflowExecutionRecord,
    onDismiss: () -> Unit,
) {
    val statusColor = when (execution.status) {
        "QUEUED" -> SpolaColors.warning
        "RUNNING" -> SpolaColors.accent
        "WAITING_APPROVAL" -> SpolaColors.warning
        "COMPLETED" -> SpolaColors.success
        "FAILED" -> SpolaColors.error
        "CANCELLED" -> SpolaColors.textMuted
        else -> SpolaColors.textMuted
    }

    val statusEmoji = when (execution.status) {
        "QUEUED" -> "\u23F3"
        "RUNNING" -> "\u25B6"
        "WAITING_APPROVAL" -> "\uD83D\uDCAC"
        "COMPLETED" -> "\u2705"
        "FAILED" -> "\u274C"
        "CANCELLED" -> "\uD83D\uDEAB"
        else -> "\u2753"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(statusEmoji)
                Text(
                    text = execution.workflowName,
                    color = SpolaColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Status
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Status:",
                        color = SpolaColors.textMuted,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = execution.status,
                        color = statusColor,
                        fontSize = 12.sp,
                    )
                }

                // Workflow
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Template:",
                        color = SpolaColors.textMuted,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = execution.workflowName,
                        color = SpolaColors.textPrimary,
                        fontSize = 12.sp,
                    )
                }

                // ID
                Text(
                    text = "ID: ${execution.id.take(8)}...",
                    color = SpolaColors.textMuted,
                    fontSize = 11.sp,
                )

                HorizontalDivider(color = SpolaColors.bgElevated)

                // Created
                execution.createdAt.let {
                    Text(
                        text = "Created: ${formatTimestamp(it)}",
                        color = SpolaColors.textSecondary,
                        fontSize = 12.sp,
                    )
                }

                // Started at
                execution.startedAt?.let {
                    Text(
                        text = "Started: ${formatTimestamp(it)}",
                        color = SpolaColors.textSecondary,
                        fontSize = 12.sp,
                    )
                }

                // Completed at
                execution.completedAt?.let {
                    Text(
                        text = "Completed: ${formatTimestamp(it)}",
                        color = SpolaColors.textSecondary,
                        fontSize = 12.sp,
                    )
                }

                // Trigger
                execution.triggerSource?.let { source ->
                    Text(
                        text = "Trigger: $source${execution.triggerRef?.let { " ($it)" }.orEmpty()}",
                        color = SpolaColors.textSecondary,
                        fontSize = 12.sp,
                    )
                }

                // Session
                execution.sessionId?.let { sid ->
                    Text(
                        text = "Session: ${sid.take(12)}...",
                        color = SpolaColors.textSecondary,
                        fontSize = 12.sp,
                    )
                }

                // Error
                execution.error?.let {
                    HorizontalDivider(color = SpolaColors.bgElevated)
                    Text(
                        text = "Error:",
                        color = SpolaColors.error,
                        fontSize = 12.sp,
                    )
                    Surface(
                        color = SpolaColors.error.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = it,
                            color = SpolaColors.textPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                // Result
                execution.result?.let {
                    HorizontalDivider(color = SpolaColors.bgElevated)
                    Text(
                        text = "Result:",
                        color = SpolaColors.success,
                        fontSize = 12.sp,
                    )
                    Surface(
                        color = SpolaColors.bgElevated,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = it.take(500),
                            color = SpolaColors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp),
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = SpolaColors.accent)
            }
        },
    )
}

@Composable
private fun WorkflowFormDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onConfirm: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    val isValid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = SpolaColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SpolaColors.textPrimary,
                        unfocusedTextColor = SpolaColors.textPrimary,
                        focusedBorderColor = SpolaColors.accent,
                        unfocusedBorderColor = SpolaColors.bgElevated,
                        cursorColor = SpolaColors.accent,
                        focusedLabelColor = SpolaColors.accent,
                        unfocusedLabelColor = SpolaColors.textMuted,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SpolaColors.textPrimary,
                        unfocusedTextColor = SpolaColors.textPrimary,
                        focusedBorderColor = SpolaColors.accent,
                        unfocusedBorderColor = SpolaColors.bgElevated,
                        cursorColor = SpolaColors.accent,
                        focusedLabelColor = SpolaColors.accent,
                        unfocusedLabelColor = SpolaColors.textMuted,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, description) },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpolaColors.accent,
                    contentColor = SpolaColors.bgSurface,
                ),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SpolaColors.textMuted)
            }
        },
    )
}

@Composable
private fun WorkflowLoadingState(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(4) {
            LoadingSkeletonCard(height = 108.dp)
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d ago"
        hours > 0 -> "${hours}h ago"
        minutes > 0 -> "${minutes}m ago"
        seconds > 0 -> "${seconds}s ago"
        else -> "just now"
    }
}

@Composable
private fun rememberSpolaClient(currentHost: TrustState?): SpolaClient? {
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
