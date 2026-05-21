package dev.spola.app.app.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.decompose.DashboardComponent
import dev.spola.app.app.theme.SpolaColors
import dev.spola.app.models.TrustState

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
        Text("⚙️ Settings", color = SpolaColors.textPrimary, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = SpolaColors.bgElevated)
        Spacer(Modifier.height(4.dp))

        if (dashboardComponent == null) {
            dev.spola.app.app.components.EmptyState(
                emoji = "🔌",
                title = "Not connected",
                message = "Connect a server to manage settings.",
            )
            return
        }

        AppearanceSection(
            isDarkMode = SpolaColors.isDarkMode,
            showThemeDialog = showThemeDialog,
            onShowThemeDialogChange = { showThemeDialog = it },
            onThemeSelected = { SpolaColors.isDarkMode = it },
        )
        ProviderSection()
        ServerSection(
            currentHost = currentHost,
            trustedHosts = trustedHosts,
            onSwitchHost = dashboardComponent::switchHost,
            onOpenPairing = dashboardComponent::openPairing,
        )
        AboutSection(versionLabel = "Spola Client v0.1.0")
    }
}

@Composable
private fun AppearanceSection(
    isDarkMode: Boolean,
    showThemeDialog: Boolean,
    onShowThemeDialogChange: (Boolean) -> Unit,
    onThemeSelected: (Boolean) -> Unit,
) {
    SettingsSection("Appearance") {
        SettingsCard(
            modifier = Modifier
                .clickable { onShowThemeDialogChange(true) }
                .semantics {
                    contentDescription = "Theme settings — ${if (isDarkMode) "Dark (Spola)" else "Light"} currently active"
                    role = Role.Button
                },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Theme", color = SpolaColors.textPrimary, fontSize = 14.sp)
                    Text(
                        if (isDarkMode) "Dark (Spola) — default" else "Light",
                        color = SpolaColors.textMuted,
                        fontSize = 12.sp,
                    )
                }
                Text("→", color = SpolaColors.accent, fontSize = 16.sp)
            }
        }

        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { onShowThemeDialogChange(false) },
                title = { Text("Theme", color = SpolaColors.textPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Select your preferred appearance.",
                            color = SpolaColors.textSecondary,
                            fontSize = 13.sp,
                        )
                        ThemeOption(
                            emoji = "☀️",
                            title = "Light",
                            subtitle = "Light appearance",
                            selected = !isDarkMode,
                            contentDescription = "Light theme",
                            onClick = {
                                onThemeSelected(false)
                                onShowThemeDialogChange(false)
                            },
                        )
                        ThemeOption(
                            emoji = "🌙",
                            title = "Dark (Spola)",
                            subtitle = "Dark appearance",
                            selected = isDarkMode,
                            contentDescription = "Dark theme",
                            onClick = {
                                onThemeSelected(true)
                                onShowThemeDialogChange(false)
                            },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onShowThemeDialogChange(false) },
                        modifier = Modifier.semantics {
                            contentDescription = "Close theme dialog"
                            role = Role.Button
                        },
                    ) {
                        Text("Close", color = SpolaColors.accent)
                    }
                },
            )
        }
    }
}

@Composable
private fun ProviderSection() {
    SettingsSection("Provider") {
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Model & Provider", color = SpolaColors.textPrimary, fontSize = 14.sp)
                Text(
                    "Session model, provider, and API-backed behavior are configured from Chat and the paired backend.",
                    color = SpolaColors.textMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ServerSection(
    currentHost: TrustState?,
    trustedHosts: List<TrustState>,
    onSwitchHost: (String) -> Unit,
    onOpenPairing: () -> Unit,
) {
    SettingsSection("Server") {
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Server", color = SpolaColors.textPrimary, fontSize = 14.sp)
                    Text(
                        currentHost?.let { "${it.host}:${it.port}" } ?: "No server configured",
                        color = if (currentHost != null) SpolaColors.textSecondary else SpolaColors.textMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (currentHost != null) SpolaColors.success else SpolaColors.error)
                        .semantics {
                            contentDescription = if (currentHost != null) "Connected" else "Disconnected"
                        },
                )
            }
        }

        if (currentHost != null) {
            SettingsCard {
                Column {
                    Text("Trust ID", color = SpolaColors.textPrimary, fontSize = 14.sp)
                    Text(
                        currentHost.trustId,
                        color = SpolaColors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (trustedHosts.isNotEmpty()) {
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trusted Hosts (${trustedHosts.size})", color = SpolaColors.textPrimary, fontSize = 14.sp)
                    trustedHosts.forEach { host ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(SpolaColors.bgElevated.copy(alpha = 0.5f))
                                .clickable { onSwitchHost(host.trustId) }
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
                                color = if (host.active) SpolaColors.accent else SpolaColors.textSecondary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            if (host.active) {
                                Text("Active", color = SpolaColors.success, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = onOpenPairing,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .semantics {
                    contentDescription = "Revoke pairing and reconnect to a different server"
                    role = Role.Button
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = SpolaColors.error.copy(alpha = 0.15f),
                contentColor = SpolaColors.error,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Revoke Pairing & Re-connect", fontSize = 13.sp)
        }

        Surface(
            color = SpolaColors.bgElevated.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "This will disconnect from the current server and return to the pairing screen to connect to a different host.",
                color = SpolaColors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun AboutSection(versionLabel: String) {
    SettingsSection("About") {
        SettingsCard {
            Text(versionLabel, color = SpolaColors.textMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ThemeOption(
    emoji: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SpolaColors.accent.copy(alpha = 0.12f) else SpolaColors.bgElevated)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(emoji, fontSize = 20.sp)
        Column {
            Text(title, color = SpolaColors.textPrimary, fontSize = 14.sp)
            Text(subtitle, color = SpolaColors.textMuted, fontSize = 11.sp)
        }
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
            color = SpolaColors.accent,
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
        color = SpolaColors.bgSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content,
        )
    }
}
