package dev.spola.app.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

expect fun randomUUID(): String

/**
 * Saves a file to the platform's standard downloads/documents folder.
 * returns true if successful.
 */
expect fun saveToDownloads(fileName: String, content: ByteArray): Boolean

/**
 * Opens a system file picker.
 * Callback provides fileName and content.
 */
expect fun pickFile(onPicked: (String, ByteArray) -> Unit)

/**
 * Platform-specific QR scanner composable button.
 * On Android, opens camera to scan a QR code.
 * On Desktop, renders a disabled button (no camera).
 */
@Composable
expect fun PairingScanButton(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
)

interface AudioRecorder {
    val isRecording: StateFlow<Boolean>
    fun start()
    fun stop(): ByteArray?
}

fun interface AudioPlayer {
    fun play(audioData: ByteArray)
}

expect fun getAudioRecorder(): AudioRecorder
expect fun getAudioPlayer(): AudioPlayer
