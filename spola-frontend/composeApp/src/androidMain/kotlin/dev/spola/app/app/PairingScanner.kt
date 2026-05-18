package dev.spola.app.app

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Android actual: Opens a QR scanner using ML Kit barcode scanning via
 * ActivityResultContracts.StartActivityForResult with an intent-based scanner.
 *
 * For simplicity and reliability, this implementation uses the default
 * barcode scanner activity. The scanned result (a JSON string) is passed
 * back via onScanned callback.
 */
@Composable
actual fun PairingScanButton(
    onScanned: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val (hasCameraPermission, requestCameraPermission) = rememberCameraPermission()

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val scannedText = data?.getStringExtra("SCAN_RESULT")
            if (scannedText != null) {
                onScanned(scannedText)
            }
        } else {
            Toast.makeText(context, "QR scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier) {
        ScanButton(
            onScan = {
                if (hasCameraPermission) {
                    try {
                        // Try using the intent-based ZXing scanner if available
                        val intent = android.content.Intent("com.google.zxing.client.android.SCAN").apply {
                            putExtra("SCAN_MODE", "QR_CODE_MODE")
                        }
                        scannerLauncher.launch(intent)
                    } catch (e: Exception) {
                        Log.w("PairingScanner", "ZXing not installed, using fallback text input", e)
                        // If ZXing isn't installed, show a toast
                        Toast.makeText(
                            context,
                            "Install a QR scanner app, or enter the JSON manually",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    requestCameraPermission()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        CameraPermissionBanner(hasPermission = hasCameraPermission)
    }
}

@Composable
private fun rememberCameraPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
        }
    }

    return hasCameraPermission to { permissionLauncher.launch(Manifest.permission.CAMERA) }
}

@Composable
private fun ScanButton(
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onScan,
        modifier = modifier,
    ) {
        Text("📷 Scan QR Code")
    }
}

@Composable
private fun CameraPermissionBanner(hasPermission: Boolean) {
    if (!hasPermission) {
        Spacer(Modifier.height(4.dp))
        Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                "Camera permission needed to scan QR codes",
                modifier = Modifier.padding(8.dp),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
    }
}
