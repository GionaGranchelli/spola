package dev.spola.app.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Global application context — set from MainActivity.onCreate */
lateinit var appContext: Context
    private set

fun initAppContext(context: Context) {
    appContext = context.applicationContext
}

actual fun randomUUID(): String = UUID.randomUUID().toString()

actual fun saveToDownloads(fileName: String, content: ByteArray): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsMediaStore(fileName, content)
        } else {
            saveToDownloadsLegacy(fileName, content)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun saveToDownloadsMediaStore(fileName: String, content: ByteArray): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = appContext.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: return false
    resolver.openOutputStream(uri)?.use { outputStream ->
        outputStream.write(content)
    }
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return true
}

@Suppress("DEPRECATION")
private fun saveToDownloadsLegacy(fileName: String, content: ByteArray): Boolean {
    // Pre-Android 10 fallback (API < 29). MediaStore.Downloads is only available from API 29+.
    @Suppress("kotlin:S5324")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!downloadsDir.exists()) {
        downloadsDir.mkdirs()
    }
    val target = File(downloadsDir, fileName)
    target.writeBytes(content)
    return true
}

object AndroidFilePickerRegistry {
    private var callback: ((String, ByteArray) -> Unit)? = null
    private var launcher: (() -> Unit)? = null

    fun setLauncher(launcher: () -> Unit) {
        this.launcher = launcher
    }

    fun pickFile(onPicked: (String, ByteArray) -> Unit) {
        this.callback = onPicked
        launcher?.invoke()
    }

    fun onResult(context: Context, uri: Uri?) {
        if (uri == null) return
        val fileName = getFileName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (fileName != null && bytes != null) {
            callback?.invoke(fileName, bytes)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }
}

actual fun pickFile(onPicked: (String, ByteArray) -> Unit) {
    AndroidFilePickerRegistry.pickFile(onPicked)
}

class AndroidAudioRecorder : AudioRecorder {
    override val isRecording: StateFlow<Boolean> = MutableStateFlow(false)
    // Audio recording not yet implemented — no-op until MediaRecorder integration
    override fun start() {}
    // Audio recording not yet implemented — returns null until recording is available
    override fun stop(): ByteArray? = null
}

class AndroidAudioPlayer : AudioPlayer {
    // Audio playback not yet implemented — no-op until MediaPlayer integration
    override fun play(audioData: ByteArray) {}
}

private val recorder = AndroidAudioRecorder()
private val player = AndroidAudioPlayer()

actual fun getAudioRecorder(): AudioRecorder = recorder
actual fun getAudioPlayer(): AudioPlayer = player
