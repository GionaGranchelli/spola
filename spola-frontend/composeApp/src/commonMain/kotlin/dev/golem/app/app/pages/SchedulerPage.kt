package dev.spola.app.app.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.spola.app.app.SchedulerPane
import dev.spola.app.app.components.EmptyState
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.app.theme.GolemColors

@Composable
fun SchedulerPage(
    dashboardComponent: DashboardComponent? = null,
    modifier: Modifier = Modifier,
) {
    if (dashboardComponent == null) {
        EmptyState(
            emoji = "🔌",
            title = "Not connected",
            message = "Connect via the Settings page to manage scheduled jobs.",
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "⏰ Scheduler",
                color = GolemColors.textPrimary,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.weight(1f))
            val currentHost by dashboardComponent.currentHost.collectAsState()
            Surface(
                color = if (currentHost != null) GolemColors.success.copy(alpha = 0.15f) else GolemColors.error.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.semantics {
                    contentDescription = if (currentHost != null) "Connected to ${currentHost!!.host}:${currentHost!!.port}" else "Disconnected"
                },
            ) {
                Text(
                    text = currentHost?.let { "${it.host}:${it.port}" } ?: "Disconnected",
                    color = if (currentHost != null) GolemColors.success else GolemColors.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = GolemColors.bgElevated)
        Spacer(Modifier.height(12.dp))

        // Scheduler content
        SchedulerPane(
            component = dashboardComponent.schedulerList,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
