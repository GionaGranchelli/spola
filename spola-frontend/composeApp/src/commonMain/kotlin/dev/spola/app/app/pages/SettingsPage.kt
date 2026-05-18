package dev.spola.app.app.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.spola.app.app.components.EmptyState
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.app.theme.GolemColors

@Composable
fun SettingsPage(
    dashboardComponent: DashboardComponent? = null,
    modifier: Modifier = Modifier,
) {
    val currentHost by dashboardComponent?.currentHost?.collectAsState() ?: remember { mutableStateOf(null) }
    val trustedHosts by dashboardComponent?.trustedHosts?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    var showThemeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Text(
            "⚙️ Settings",
            color = GolemColors.textPrimary,
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = GolemColors.bgElevated)
        Spacer(Modifier.height(4.dp))

        if (dashboardComponent == null) {
            EmptyState(
                emoji = "🔌",
                title = "Not connected",
                message = "Connect a server to manage settings.",
            )
            return
        }

        // ── Connection Section ─────────────────────────────────────
        SettingsSection("Connection") {
            // Current host info
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Server",
                            color = GolemColors.textPrimary,
                            fontSize = 14.sp,
                        )
                        Text(
                            currentHost?.let { "${it.host}:${it.port}" } ?: "No server configured",
                            color = if (currentHost != null) GolemColors.textSecondary else GolemColors.textMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (currentHost != null) GolemColors.success else GolemColors.error)
                            .semantics {
                                contentDescription = if (currentHost != null) "Connected" else "Disconnected"
                            },
                    )
                }
            }

            // Trust ID
            if (currentHost != null) {
                SettingsCard {
                    Column {
                        Text(
                            "Trust ID",
                            color = GolemColors.textPrimary,
                            fontSize = 14.sp,
                        )
                        Text(
                            currentHost!!.trustId,
                            color = GolemColors.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Connected hosts
            if (trustedHosts.isNotEmpty()) {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Trusted Hosts (${trustedHosts.size})",
                            color = GolemColors.textPrimary,
                            fontSize = 14.sp,
                        )
                        trustedHosts.forEach { host ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(GolemColors.bgElevated.copy(alpha = 0.5f))
                                    .clickable { dashboardComponent.switchHost(host.trustId) }
                                    .semantics {
                                        contentDescription = "Switch to host ${host.host}:${host.port}${if (host.active) ", currently active" else ""}"
                                        role = Role.Button
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${host.host}:${host.port}",
                                    color = if (host.active) GolemColors.accent else GolemColors.textSecondary,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                if (host.active) {
                                    Text(
                                        "Active",
                                        color = GolemColors.success,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Theme Section ──────────────────────────────────────────
        SettingsSection("Appearance") {
            SettingsCard(
                modifier = Modifier
                    .clickable { showThemeDialog = true }
                    .semantics {
                        contentDescription = "Theme settings — Dark (Golem) currently active"
                        role = Role.Button
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Theme",
                            color = GolemColors.textPrimary,
                            fontSize = 14.sp,
                        )
                        Text(
                            "Dark (Golem) — default",
                            color = GolemColors.textMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        "→",
                        color = GolemColors.accent,
                        fontSize = 16.sp,
                    )
                }
            }

            if (showThemeDialog) {
                AlertDialog(
                    onDismissRequest = { showThemeDialog = false },
                    title = { Text("Theme", color = GolemColors.textPrimary) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Select your preferred appearance.",
                                color = GolemColors.textSecondary,
                                fontSize = 13.sp,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (!GolemColors.isDarkMode) GolemColors.accent.copy(alpha = 0.12f) else GolemColors.bgElevated)
                                    .clickable { GolemColors.isDarkMode = false; showThemeDialog = false }
                                    .semantics {
                                        contentDescription = "Light theme"
                                        role = Role.Button
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("☀️", fontSize = 20.sp)
                                Column {
                                    Text("Light", color = GolemColors.textPrimary, fontSize = 14.sp)
                                    Text("Light appearance", color = GolemColors.textMuted, fontSize = 11.sp)
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (GolemColors.isDarkMode) GolemColors.accent.copy(alpha = 0.12f) else GolemColors.bgElevated)
                                    .clickable { GolemColors.isDarkMode = true; showThemeDialog = false }
                                    .semantics {
                                        contentDescription = "Dark theme"
                                        role = Role.Button
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("🌙", fontSize = 20.sp)
                                Column {
                                    Text("Dark (Golem)", color = GolemColors.textPrimary, fontSize = 14.sp)
                                    Text("Dark appearance", color = GolemColors.textMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { showThemeDialog = false },
                            modifier = Modifier.semantics {
                                contentDescription = "Close theme dialog"
                                role = Role.Button
                            },
                        ) {
                            Text("Close", color = GolemColors.accent)
                        }
                    },
                )
            }
        }

        // ── Actions Section ────────────────────────────────────────
        SettingsSection("Actions") {
            // Revoke / Re-pair
            Button(
                onClick = { dashboardComponent.openPairing() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .semantics {
                        contentDescription = "Revoke pairing and reconnect to a different server"
                        role = Role.Button
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = GolemColors.error.copy(alpha = 0.15f),
                    contentColor = GolemColors.error,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Revoke Pairing & Re-connect", fontSize = 13.sp)
            }

            Spacer(Modifier.height(4.dp))

            Surface(
                color = GolemColors.bgElevated.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "This will disconnect from the current server and return to the pairing screen to connect to a different host.",
                    color = GolemColors.textMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // Version info
        Text(
            "OpenClaw App v0.1.0",
            color = GolemColors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            color = GolemColors.accent,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )
        content()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = GolemColors.bgSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content,
        )
    }
}
