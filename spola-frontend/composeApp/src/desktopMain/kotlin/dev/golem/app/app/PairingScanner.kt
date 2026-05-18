package dev.spola.app.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Desktop actual: No camera available. Shows a disabled button explaining
 * that QR scanning is only available on Android.
 */
@Composable
actual fun PairingScanButton(
    @Suppress("UNUSED_PARAMETER") onScanned: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        Button(
            onClick = { /* No camera on desktop */ },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("📷 Scan QR Code")
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                "QR scanning is only available on Android. Paste JSON instead.",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
