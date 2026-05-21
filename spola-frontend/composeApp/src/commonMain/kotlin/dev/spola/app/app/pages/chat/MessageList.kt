package dev.spola.app.app.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.components.EmptyState
import dev.spola.app.app.theme.SpolaColors
import dev.spola.app.models.Message
import dev.spola.app.models.MessageRole
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.StreamEventType

@Composable
fun MessageList(
    messages: List<Message>,
    isStreaming: Boolean,
    toolEvents: List<StreamEvent>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(modifier = modifier.background(SpolaColors.bg)) {
        if (messages.isEmpty()) {
            EmptyState(
                emoji = "💬",
                title = "Select or create a session",
                message = "Choose a session from the sidebar or create a new one to start chatting with the Spola agent.",
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
                if (isStreaming) {
                    item(key = "tool-timeline") {
                        ToolTimeline(toolEvents = toolEvents)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTimeline(toolEvents: List<StreamEvent>) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 12.dp))
                .background(SpolaColors.thinkingBg)
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = if (expanded) "Collapse tool call timeline" else "Expand tool call timeline"
                    role = Role.Button
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(SpolaColors.toolRunning),
            )
            Text("Thinking...", color = SpolaColors.textSecondary, fontSize = 12.sp)
            Text(if (expanded) "▲" else "▼", color = SpolaColors.textMuted, fontSize = 9.sp)
            if (toolEvents.isNotEmpty()) {
                Text(
                    text = "${toolEvents.count { it.type == StreamEventType.tool_result }} tools used",
                    color = SpolaColors.textMuted,
                    fontSize = 10.sp,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            label = "toolTimelineExpand",
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(SpolaColors.toolCallBg)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (toolEvents.isEmpty()) {
                    Text(
                        text = "⏳ No tool calls yet…",
                        color = SpolaColors.textMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    val grouped = buildToolTimeline(toolEvents)
                    grouped.forEach { (call, result) ->
                        ToolTimelineItem(
                            toolName = call?.toolName ?: result?.toolName ?: "unknown",
                            isError = result?.content?.startsWith("Error") == true ||
                                result?.content?.startsWith("error") == true,
                            isPending = result == null,
                        )
                    }
                }
            }
        }
    }
}

private fun buildToolTimeline(toolEvents: List<StreamEvent>): List<Pair<StreamEvent?, StreamEvent?>> {
    val grouped = mutableListOf<Pair<StreamEvent?, StreamEvent?>>()
    var pendingCall: StreamEvent? = null
    for (event in toolEvents) {
        when (event.type) {
            StreamEventType.tool_call -> pendingCall = event
            StreamEventType.tool_result -> {
                if (pendingCall != null) {
                    grouped += pendingCall to event
                    pendingCall = null
                } else {
                    grouped += null to event
                }
            }

            else -> Unit
        }
    }
    if (pendingCall != null) {
        grouped += pendingCall to null
    }
    return grouped
}

@Composable
private fun ToolTimelineItem(
    toolName: String,
    isError: Boolean,
    isPending: Boolean,
) {
    val iconColor = when {
        isPending -> SpolaColors.toolRunning
        isError -> SpolaColors.toolError
        else -> SpolaColors.toolSuccess
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics {
            contentDescription = "Tool: $toolName, status: ${if (isPending) "running" else if (isError) "error" else "complete"}"
        },
    ) {
        Text(if (isPending) "🔄" else if (isError) "✗" else "✓", fontSize = 12.sp)
        Text(
            toolName,
            color = SpolaColors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Text(
            text = if (isPending) "running…" else if (isError) "error" else "done",
            color = iconColor,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = if (isUser) "You" else "Assistant",
                color = SpolaColors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )

            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp,
                        )
                    )
                    .background(if (isUser) SpolaColors.userBubble else SpolaColors.assistantBubble)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics {
                        contentDescription = "${if (isUser) "Your" else "Assistant"} message: ${message.content.take(120)}"
                    },
            ) {
                val content = message.content
                if (content.startsWith("```") || content.startsWith("  ") || content.contains("\n  ")) {
                    Text(
                        text = content,
                        color = SpolaColors.assistantBubbleText,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        text = content,
                        color = if (isUser) SpolaColors.userBubbleText else SpolaColors.assistantBubbleText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            Text(
                text = formatShortTimestamp(message.timestamp),
                color = SpolaColors.textMuted.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

internal fun formatShortTimestamp(millis: Long): String {
    val totalSeconds = millis / 1000
    val timeOfDay = totalSeconds % 86400
    val hours = timeOfDay / 3600
    val minutes = (timeOfDay % 3600) / 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}
