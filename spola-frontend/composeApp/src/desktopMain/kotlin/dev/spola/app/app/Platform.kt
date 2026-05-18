package dev.spola.app.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.FileDialog
import java.awt.Frame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.sound.sampled.*

actual fun randomUUID(): String = UUID.randomUUID().toString()

actual fun saveToDownloads(fileName: String, content: ByteArray): Boolean {
    return try {
        val userHome = System.getProperty("user.home")
        val downloadsDir = File(userHome, "Downloads")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val target = File(downloadsDir, fileName)
        target.writeBytes(content)
        println("Saved to: ${target.absolutePath}")
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

actual fun pickFile(onPicked: (String, ByteArray) -> Unit) {
    val dialog = FileDialog(null as Frame?, "Select File to Upload", FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file
    val directory = dialog.directory
    if (file != null && directory != null) {
        val target = File(directory, file)
        onPicked(file, target.readBytes())
    }
}

class JvmAudioRecorder : AudioRecorder {
    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private var line: TargetDataLine? = null
    private var out: ByteArrayOutputStream? = null

    override fun start() {
        try {
            val format = AudioFormat(16000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return
            
            line = AudioSystem.getLine(info) as TargetDataLine
            line?.open(format)
            line?.start()
            
            _isRecording.value = true
            out = ByteArrayOutputStream()
            
            Thread {
                val buffer = ByteArray(4096)
                while (_isRecording.value) {
                    val count = line?.read(buffer, 0, buffer.size) ?: 0
                    if (count > 0) out?.write(buffer, 0, count)
                }
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun stop(): ByteArray? {
        _isRecording.value = false
        line?.stop()
        line?.close()
        val raw = out?.toByteArray()
        if (raw == null || raw.isEmpty()) return null
        
        // Wrap in WAV header for Whisper
        val format = AudioFormat(16000f, 16, 1, true, false)
        val bais = ByteArrayInputStream(raw)
        val ais = AudioInputStream(bais, format, raw.size.toLong() / format.frameSize)
        val finalOut = ByteArrayOutputStream()
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, finalOut)
        return finalOut.toByteArray()
    }
}

class JvmAudioPlayer : AudioPlayer {
    override fun play(audioData: ByteArray) {
        try {
            val bais = ByteArrayInputStream(audioData)
            val ais = AudioSystem.getAudioInputStream(bais)
            val format = ais.format
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format)
            line.start()
            
            val buffer = ByteArray(4096)
            var count: Int
            while (ais.read(buffer).also { count = it } != -1) {
                line.write(buffer, 0, count)
            }
            line.drain()
            line.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private val recorder = JvmAudioRecorder()
private val player = JvmAudioPlayer()

actual fun getAudioRecorder(): AudioRecorder = recorder
actual fun getAudioPlayer(): AudioPlayer = player
