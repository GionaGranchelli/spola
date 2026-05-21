package dev.spola.app.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spola.app.app.theme.SpolaColors

/**
 * Reusable empty state component displayed when a list/page has no content.
 *
 * Shows a centered column with emoji, title, description, and an optional action button.
 */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = emoji,
                fontSize = 42.sp,
                textAlign = TextAlign.Center,
            )

            Text(
                text = title,
                color = SpolaColors.textPrimary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )

            Text(
                text = message,
                color = SpolaColors.textMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )

            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpolaColors.accent,
                        contentColor = SpolaColors.userBubbleText,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = actionLabel
                        role = Role.Button
                    },
                ) {
                    Text(text = actionLabel, fontSize = 13.sp)
                }
            }
        }
    }
}
