package dev.spola.app.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.theme.SpolaColors
import dev.spola.app.models.ModelInfo

/**
 * Compact model selector dropdown.
 * Shows the current model name; click to open a dropdown of available models.
 */
@Composable
fun ModelSelector(
    models: List<ModelInfo>,
    selectedModelId: String,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedModel = models.find { it.id == selectedModelId }
    val modelName = selectedModel?.name ?: selectedModelId.take(24)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(SpolaColors.bgElevated)
                .clickable { expanded = true }
                .semantics {
                    contentDescription = "Selected model: $modelName. Tap to change model."
                    role = Role.Button
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = modelName,
                color = SpolaColors.accent,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "▼",
                color = SpolaColors.textMuted,
                fontSize = 8.sp,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No models available") },
                    onClick = { expanded = false },
                    modifier = Modifier.semantics {
                        contentDescription = "No models available"
                    },
                )
            } else {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    model.name,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = model.provider,
                                    fontSize = 11.sp,
                                    color = SpolaColors.textMuted,
                                )
                            }
                        },
                        onClick = {
                            onModelSelected(model.id)
                            expanded = false
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Model: ${model.name} by ${model.provider}"
                        },
                    )
                }
            }
        }
    }
}
