# Voice Integration Proposal for Spola Client

## Overview

Add voice input (speech-to-text) and voice output (text-to-speech) capabilities to Spola Client, enabling users to interact with the AI via voice in both the desktop app and Telegram.

## Goals

1. **Voice Input**: Users can speak to Spola Client; audio is transcribed and sent as a prompt
2. **Voice Output**: AI responses can be spoken aloud via TTS
3. **Telegram Voice**: Support voice messages in Telegram integration

## Architecture

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Compose UI │───▶│   Backend   │───▶│   Ollama    │
│  (Mic btn)  │    │  (STT/TTS)  │    │  (Whisper)  │
└─────────────┘    └─────────────┘    └─────────────┘
       │                  │
       │                  ▼
       │           ┌─────────────┐
       │           │  Telegram   │
       └──────────▶│  (voice)    │
                  └─────────────┘
```

## Technical Implementation

### 1. Speech-to-Text (STT)

#### Backend: `/speech/transcribe` endpoint

**File**: `backend/src/main/kotlin/dev/spola/app/backend/routes/SpeechRoute.kt`

```kotlin
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable

@Serializable
data class TranscribeResponse(
    val text: String,
    val confidence: Float? = null
)

fun Route.speechRoutes(services: BackendServices) {
    post("/speech/transcribe") {
        val bytes = call.receiveChannel().readRemaining().readBytes()
        val result = services.speechService.transcribe(bytes)
        call.respond(TranscribeResponse(result.text, result.confidence))
    }
}
```

#### Speech Service

**File**: `backend/src/main/kotlin/dev/spola/app/backend/SpeechService.kt`

```kotlin
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface SpeechToText {
    suspend fun transcribe(audioBytes: ByteArray): TranscriptionResult
}

data class TranscriptionResult(
    val text: String,
    val confidence: Float? = null
)

class OllamaWhisperSTT(
    private val client: HttpClient,
    private val baseUrl: String = "http://localhost:11434"
) : SpeechToText {
    override suspend fun transcribe(audioBytes: ByteArray): TranscriptionResult {
        val base64Audio = java.util.Base64.getEncoder().encodeToString(audioBytes)
        
        val response = client.post("$baseUrl/api/transcribe") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", "whisper")
                put("file", base64Audio)
            })
        }
        
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val text = json["text"]?.jsonPrimitive?.content ?: ""
        
        return TranscriptionResult(text)
    }
}
```

### 2. Text-to-Speech (TTS)

#### Backend: `/speech/synthesize` endpoint

```kotlin
fun Route.speechRoutes(services: BackendServices) {
    post("/speech/transcribe") { /* ... */ }
    
    post("/speech/synthesize") {
        val request = call.receive<SynthesizeRequest>()
        val audio = services.speechService.synthesize(request.text, request.voice)
        call.respondBytes(audio, ContentType.Audio.WAV)
    }
}

@Serializable
data class SynthesizeRequest(
    val text: String,
    val voice: String? = null  // Optional voice selection
)
```

#### TTS Implementation using ElevenLabs (existing `sag`)

```kotlin
interface TextToSpeech {
    suspend fun synthesize(text: String, voice: String? = null): ByteArray
}

class ElevenLabsTTS(
    private val apiKey: String,
    private val voiceId: String = "r21GMgckVtNgqY2x1dVQ" // Default voice
) : TextToSpeech {
    private val client = HttpClient()
    
    override suspend fun synthesize(text: String, voice: String?): ByteArray {
        val response = client.post("https://api.elevenlabs.io/v1/text-to-speech/${voice ?: voiceId}") {
            contentType(ContentType.Application.Json)
            header("xi-api-key", apiKey)
            setBody(buildJsonObject {
                put("text", text)
                put("voice_settings", buildJsonObject {
                    put("stability", 0.5)
                    put("similarity_boost", 0.5)
                })
            })
        }
        return response.bodyAsChannel().toByteArray()
    }
}
```

### 3. Compose Client UI

#### ChatPane additions

**File**: `composeApp/src/commonMain/kotlin/dev/spola/app/app/App.kt`

Add voice input button to the chat input area:

```kotlin
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceToggle: () -> Unit,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onVoiceToggle) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "Stop recording" else "Voice input",
                tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
        
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (isRecording) "Recording..." else "Type message...") }
        )
        
        IconButton(onClick = onSend) {
            Icon(Icons.Default.Send, contentDescription = "Send")
        }
    }
}
```

#### Voice recording manager

**File**: `composeApp/src/commonMain/kotlin/dev/spola/app/app/VoiceManager.kt`

```kotlin
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VoiceManager {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()
    
    // Platform-specific audio recording would be implemented here
    // For desktop: use Java AudioSystem or a library like Ktor client for streaming
    
    fun startRecording() {
        _isRecording.value = true
        // Start recording audio from microphone
    }
    
    fun stopRecording(): ByteArray {
        _isRecording.value = false
        // Stop and return recorded bytes
        return ByteArray(0) // Placeholder
    }
    
    suspend fun transcribe(audio: ByteArray, client: SpolaClient): String {
        return withContext(Dispatchers.IO) {
            // Call backend transcription endpoint
            ""
        }
    }
}
```

### 4. Telegram Voice Support

#### Message handler for voice

**File**: (Telegram plugin - handled separately, but backend would receive voice file)

```kotlin
// When receiving a voice message from Telegram:
// 1. Download the voice file (OGG/WebM)
// 2. Convert to PCM/WAV if needed
// 3. Send to /speech/transcribe
// 4. Use transcribed text as the prompt
```

### 5. State Management

Add voice settings to session state:

```kotlin
@Serializable
data class VoiceSettings(
    val sttProvider: String = "ollama-whisper",  // or "openai-whisper", "browser"
    val ttsProvider: String = "elevenlabs",      // or "ollama-tts"
    val voiceId: String? = null,
    val autoPlayTTS: Boolean = false
)
```

## File Changes Summary

| File | Action |
|------|--------|
| `backend/src/main/kotlin/dev/spola/app/backend/SpeechService.kt` | New - STT/TTS implementations |
| `backend/src/main/kotlin/dev/spola/app/backend/routes/SpeechRoute.kt` | New - REST endpoints |
| `backend/src/main/kotlin/dev/spola/app/backend/Main.kt` | Modify - register speech routes |
| `backend/src/main/kotlin/dev/spola/app/backend/BackendServices.kt` | Modify - add SpeechService |
| `shared/src/commonMain/kotlin/dev/spola/app/models/Models.kt` | Modify - add VoiceSettings |
| `shared/src/commonMain/kotlin/dev/spola/app/network/SpolaClient.kt` | Modify - add transcribe/synthesize calls |
| `composeApp/src/commonMain/kotlin/dev/spola/app/app/App.kt` | Modify - add voice input UI |
| `composeApp/src/commonMain/kotlin/dev/spola/app/app/VoiceManager.kt` | New - voice recording logic |

## Dependencies

```kotlin
// backend/build.gradle.kts
dependencies {
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    // For audio processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines:$coroutinesVersion")
}
```

## Configuration

```properties
# application.conf (or environment variables)
spola.speech.stt.provider=ollama-whisper
spola.speech.stt.ollama.url=http://localhost:11434
spola.speech.tts.provider=elevenlabs
spola.speech.tts.elevenlabs.api-key=${ELEVENLABS_API_KEY}
```

## Testing Plan

1. **Unit tests**: SpeechService transcription/synthesis logic
2. **Integration tests**: Backend endpoints with real Ollama/Whisper
3. **UI tests**: Voice button states, recording indicator
4. **Manual tests**: End-to-end voice conversation in desktop app

## Timeline Estimate

- **Backend STT endpoint**: 2-3 hours
- **Backend TTS endpoint**: 1-2 hours  
- **Compose UI voice button**: 2-3 hours
- **Telegram voice handling**: 3-4 hours (if needed)
- **Testing & polish**: 2-3 hours

**Total**: ~10-15 hours for full implementation

## Future Enhancements

- Real-time streaming STT (WebSocket)
- Voice activity detection (VAD)
- Multiple voice options for TTS
- Voice cloning
- Custom hotword detection ("Hey Spola Client")