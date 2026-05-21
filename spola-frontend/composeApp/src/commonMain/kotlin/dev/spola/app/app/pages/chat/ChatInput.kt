package dev.spola.app.app.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.components.ModelSelector
import dev.spola.app.app.theme.SpolaColors
import dev.spola.app.models.ModelInfo

@Composable
fun ChatStatusBar(
    modelId: String,
    models: List<ModelInfo>,
    status: String,
    isConnected: Boolean,
    onModelSelected: (String) -> Unit,
    onToggleSidebar: () -> Unit,
) {
    Surface(
        color = SpolaColors.bgSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "☰",
                    color = SpolaColors.textMuted,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable(onClick = onToggleSidebar)
                        .semantics {
                            contentDescription = "Collapse sidebar"
                            role = Role.Button
                        },
                )

                if (modelId.isNotBlank()) {
                    ModelSelector(
                        models = models,
                        selectedModelId = modelId,
                        onModelSelected = onModelSelected,
                    )
                } else {
                    Text("No session selected", color = SpolaColors.textMuted, fontSize = 12.sp)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isConnected) SpolaColors.success else SpolaColors.error),
                )
                Text(
                    text = if (isConnected) "Connected" else "Disconnected",
                    color = if (isConnected) SpolaColors.textSecondary else SpolaColors.error,
                    fontSize = 11.sp,
                )
                Text(text = status.uppercase(), color = SpolaColors.textMuted, fontSize = 11.sp)
            }
        }
    }

    HorizontalDivider(color = SpolaColors.bgElevated)
}

@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isRunning: Boolean,
    isSessionSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = SpolaColors.bgSurface,
    ) {
        Column {
            HorizontalDivider(color = SpolaColors.bgElevated)

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val compactLayout = maxWidth < 720.dp
                val inputFieldWidth = maxWidth - 104.dp

                if (compactLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatInputField(
                            value = value,
                            onValueChange = onValueChange,
                            onSend = onSend,
                            isRunning = isRunning,
                            isSessionSelected = isSessionSelected,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = onSend,
                            enabled = value.isNotBlank() && !isRunning && isSessionSelected,
                            modifier = Modifier
                                .align(Alignment.End)
                                .height(48.dp)
                                .semantics {
                                    contentDescription = if (isSessionSelected) "Send message" else "Send message — select a session first"
                                    role = Role.Button
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SpolaColors.accent,
                                disabledContainerColor = SpolaColors.accent.copy(alpha = 0.3f),
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = SpolaColors.textPrimary,
                                )
                            } else {
                                Text("Send", fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChatInputField(
                            value = value,
                            onValueChange = onValueChange,
                            onSend = onSend,
                            isRunning = isRunning,
                            isSessionSelected = isSessionSelected,
                            modifier = Modifier.width(inputFieldWidth),
                        )

                        Button(
                            onClick = onSend,
                            enabled = value.isNotBlank() && !isRunning && isSessionSelected,
                            modifier = Modifier
                                .height(48.dp)
                                .semantics {
                                    contentDescription = if (isSessionSelected) "Send message" else "Send message — select a session first"
                                    role = Role.Button
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SpolaColors.accent,
                                disabledContainerColor = SpolaColors.accent.copy(alpha = 0.3f),
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = SpolaColors.textPrimary,
                                )
                            } else {
                                Text("Send", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isRunning: Boolean,
    isSessionSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onKeyEvent { keyEvent ->
                if (keyEvent.key == Key.Enter && !isRunning && value.isNotBlank() && isSessionSelected) {
                    onSend()
                    true
                } else {
                    false
                }
            }
            .semantics {
                contentDescription = if (isSessionSelected) "Chat input" else "Chat input — select a session first"
                role = Role.Button
            },
        placeholder = {
            Text(
                if (isSessionSelected) "Type a message..." else "Select a session first",
                color = SpolaColors.textMuted,
                fontSize = 13.sp,
            )
        },
        textStyle = TextStyle(color = SpolaColors.textPrimary, fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SpolaColors.accent.copy(alpha = 0.5f),
            unfocusedBorderColor = SpolaColors.bgElevated,
            cursorColor = SpolaColors.accent,
            focusedContainerColor = SpolaColors.bg,
            unfocusedContainerColor = SpolaColors.bg,
        ),
        minLines = 1,
        maxLines = 6,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(
            onSend = {
                if (!isRunning && value.isNotBlank() && isSessionSelected) {
                    onSend()
                }
            },
        ),
        enabled = !isRunning && isSessionSelected,
    )
}
