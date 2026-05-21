package dev.spola.app.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.theme.SpolaColors

/**
 * A command palette action that can be invoked.
 */
data class CommandPaletteAction(
    val id: String,
    val label: String,
    val emoji: String,
    val shortcut: String = "",
    val action: () -> Unit,
)

/**
 * Ctrl+K Command Palette overlay.
 *
 * Shows a searchable list of actions. Opens with Ctrl+K, closes on Esc or click outside.
 */
@Composable
fun CommandPalette(
    visible: Boolean,
    onDismiss: () -> Unit,
    actions: List<CommandPaletteAction>,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    val filteredActions = remember(query, actions) {
        filterActions(actions = actions, query = query)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        label = "commandPaletteAnim",
    ) {
        // Full-screen semi-transparent overlay
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SpolaColors.bg.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .semantics {
                    contentDescription = "Command palette overlay. Press Escape to close."
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            CommandPaletteContent(
                query = query,
                onQueryChange = { query = it },
                actions = actions,
                filteredActions = filteredActions,
                onDismiss = onDismiss,
                focusRequester = focusRequester,
            )

            // Auto-focus the search field when opened
            LaunchedEffect(visible) {
                if (visible) {
                    query = ""
                    focusRequester.requestFocus()
                }
            }
        }
    }
}

@Composable
private fun CommandPaletteContent(
    query: String,
    onQueryChange: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER")
    actions: List<CommandPaletteAction>,
    filteredActions: List<CommandPaletteAction>,
    onDismiss: () -> Unit,
    focusRequester: FocusRequester,
) {
    Surface(
        modifier = Modifier
            .padding(top = 80.dp)
            .widthIn(min = 320.dp, max = 480.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = false) {} // prevent click-through to overlay
            .semantics {
                contentDescription = "Command palette"
            },
        color = SpolaColors.bgSurface,
        shadowElevation = 16.dp,
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onKeyEvent { keyEvent -> handleSearchKeyEvent(keyEvent = keyEvent, onDismiss = onDismiss) }
                    .semantics {
                        contentDescription = "Search commands"
                        role = Role.Button
                    },
                placeholder = {
                    Text(
                        "Search commands…",
                        color = SpolaColors.textMuted,
                        fontSize = 13.sp,
                    )
                },
                textStyle = TextStyle(
                    color = SpolaColors.textPrimary,
                    fontSize = 14.sp,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SpolaColors.accent.copy(alpha = 0.5f),
                    unfocusedBorderColor = SpolaColors.bgElevated,
                    cursorColor = SpolaColors.accent,
                    focusedContainerColor = SpolaColors.bg,
                    unfocusedContainerColor = SpolaColors.bg,
                ),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (filteredActions.isNotEmpty()) {
                            filteredActions.first().action()
                            onDismiss()
                        }
                    },
                ),
            )

            Text(
                text = "Type to filter commands, Esc to close",
                color = SpolaColors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            HorizontalDivider(color = SpolaColors.bgElevated)

            if (filteredActions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No matching commands",
                        color = SpolaColors.textMuted,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filteredActions, key = { it.id }) { action ->
                        CommandPaletteItem(
                            action = action,
                            query = query,
                            onClick = {
                                action.action()
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun filterActions(
    actions: List<CommandPaletteAction>,
    query: String,
): List<CommandPaletteAction> {
    if (query.isBlank()) {
        return actions
    }

    val normalizedQuery = query.lowercase()
    return actions.filter { action ->
        action.label.lowercase().contains(normalizedQuery) ||
            action.id.lowercase().contains(normalizedQuery)
    }
}

private fun handleSearchKeyEvent(
    keyEvent: androidx.compose.ui.input.key.KeyEvent,
    onDismiss: () -> Unit,
): Boolean {
    if (keyEvent.key != Key.Escape) {
        return false
    }

    onDismiss()
    return true
}

@Composable
private fun CommandPaletteItem(
    action: CommandPaletteAction,
    @Suppress("UNUSED_PARAMETER") query: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Command: ${action.label}"
                role = Role.Button
            },
        color = SpolaColors.bgSurface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = action.emoji,
                fontSize = 16.sp,
            )
            Text(
                text = action.label,
                color = SpolaColors.textPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (action.shortcut.isNotBlank()) {
                Surface(
                    color = SpolaColors.bgElevated,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = action.shortcut,
                        color = SpolaColors.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
