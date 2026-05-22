# T-405: Voice/TTS Tool

## Goal
Add a `tts_say` tool that converts text to speech audio, plus config for multiple TTS providers. The tool returns an audio file path that the agent can deliver to the user.

## Background
Spola already has multi-platform delivery tools (telegram_send, email_send). A TTS tool lets the agent speak responses aloud or send voice messages via delivery channels (e.g. Telegram voice messages).

## Requirements

### 1. SpolaTtsProvider interface
- Create `dev.spola.tts.TtsProvider` interface
- Method: `suspend fun synthesize(text: String): ByteArray`
- Returns WAV or MP3 audio bytes

```kotlin
interface TtsProvider : AutoCloseable {
    suspend fun synthesize(text: String, voice: String? = null): ByteArray
    val name: String
}
```

### 2. Built-in providers

#### Provider A: Edge TTS (free, no API key)
- Uses `com.microsoft.cognitiveservices.speech` or an embedded approach
- Alternative: shell out to `edge-tts` Python CLI if available (simpler, cross-platform)
- Falls back to a local HTTP endpoint if edge-tts is not installed
- **Primary approach**: use `java.net.http.HttpClient` to call Mozilla's TTS or a local Piper TTS
- **Simpler fallback**: if `edge-tts` CLI is detected at `$HOME/.local/bin/edge-tts` or in PATH, use it via ProcessBuilder; otherwise fail with helpful error

#### Provider B: ElevenLabs (API key required)
- `POST https://api.elevenlabs.io/v1/text-to-speech/{voiceId}`
- Headers: `xi-api-key`, `Content-Type: application/json`
- Returns MP3 bytes
- Configurable voiceId (default: `21m00Tcm4TlvDq8ikWAM` — Rachel)
- Text limit: 5000 chars per call (auto-truncate)

```kotlin
class ElevenLabsTtsProvider(
    private val apiKey: String,
    private val voiceId: String = "21m00Tcm4TlvDq8ikWAM",
) : TtsProvider
```

### 3. Tool: `tts_say`
- Parameters:
  - `text` (string, required) — text to speak
  - `voice` (string, optional) — voice override
  - `provider` (string, optional) — provider override: `"edge"` or `"elevenlabs"`
  - `output_path` (string, optional) — custom file path for the audio (default: `~/.spola/audio/<md5>.wav`)
- Returns: path to the generated audio file, duration info
- Saves WAV/MP3 to `~/.spola/audio/` directory (auto-create)
- Caches: if same text was already synthesized, returns cached file path (MD5 of text + voice)

### 4. Configuration

```kotlin
data class SpolaTtsConfig(
    val provider: String = "edge",        // "edge" or "elevenlabs"
    val elevenlabsApiKey: String? = null,
    val elevenlabsVoiceId: String = "21m00Tcm4TlvDq8ikWAM",
    val cacheDir: String = "~/.spola/audio",
)
```

Add to `SpolaConfig`:
- `ttsProvider: String = "edge"`
- `elevenlabsApiKey: String? = null`
- `elevenlabsVoiceId: String = "21m00Tcm4TlvDq8ikWAM"`

### 5. Integration
- SpolaFactory: create TtsProvider, register `tts_say` tool when config has TTS enabled
- Register in `SpolaFactory.create()` next to delivery tools
- No new CLI flags needed — uses config/ENV

### 6. Tests
- ElevenLabsTtsProvider.synthesize — mock HTTP server returns MP3 bytes
- EdgeTtsProvider — test via CLI invocation (mock ProcessBuilder)
- tts_say tool — test parameter parsing, file output, caching
- tts_say with empty text → ToolResult.fail
- tts_say with oversize text → auto-truncate to 5000 chars
- All existing 143+ tests pass

## Files
```
spola-backend-core/src/main/kotlin/dev/spola/
├── tts/
│   ├── TtsProvider.kt            — NEW: interface
│   ├── ElevenLabsTtsProvider.kt  — NEW: ElevenLabs implementation
│   ├── EdgeTtsProvider.kt        — NEW: Edge TTS (CLI) implementation
│   └── TtsTools.kt               — NEW: tts_say tool registration

spola-backend-core/src/main/kotlin/dev/spola/
├── SpolaFactory.kt               — MODIFY: wire TTS provider + tool
├── SpolaConfig.kt                — MODIFY: add tts config fields

spola-backend-core/src/test/kotlin/dev/spola/tts/
├── ElevenLabsTtsProviderTest.kt  — NEW
├── TtsToolsTest.kt               — NEW
```

## Dependencies
```kotlin
// No new dependencies for ElevenLabs (uses built-in java.net.http.HttpClient)
// Edge TTS uses ProcessBuilder (no deps)
```

## Future
- Streaming TTS (WebSocket for real-time audio)
- STT (speech-to-text) as `stt_listen` tool — T-406 (future)
- Voice activity detection
