# TramAI Multimodal Support — Requirements from Spola

**Purpose:** Spola needs TramAI to support image/multimodal content in LLM messages. This document specifies exactly what Spola needs, not how TramAI should implement it.

**Date:** 2026-05-14
**Author:** Spola Agent Framework

---

## 1. Why Spola Needs This

Spola is an autonomous agent framework. Agents operate in an environment where they encounter:
- **Screenshots** — browser automation captures page screenshots for the LLM to analyze
- **Diagrams/architectures** — users share system diagrams, the agent needs to read them
- **Error screens** — debug workflow captures error screenshots
- **Scanned documents** — invoices, contracts, forms in image/PDF format
- **Generated images** — the agent creates images and needs to review them

Currently Spola's tools extract text from images via OCR (Tess4J), but this loses visual context — layout, colors, icons, charts, handwriting. For a general-purpose agent, LLM-native vision is essential.

---

## 2. Current TramAI Message Model (Baseline)

```kotlin
// Current — tramai-core 0.1.0
data class Message(
    val role: MessageRole,          // system, user, assistant, tool
    val content: String,             // ← PLAIN TEXT ONLY
    val toolCallId: String?,         // null for non-tool messages
    val toolCalls: List<ToolCall>?  // null for non-tool messages
)

data class ModelRequest(
    val messages: List<Message>,
    val tools: List<ToolDefinition>?,
    val temperature: Double?,
    val maxTokens: Int?,
    // ... other params
)
```

**Limitation:** `content` is a single `String`. Cannot represent:
- Mixed text + image content
- Multiple images
- Image URLs vs base64-encoded images
- Different image formats with metadata

---

## 3. Requirements

### 3.1 Content Model — `MessageContent` sealed hierarchy

Spola needs a `Message` whose `content` can hold multiple **content blocks** (text, image, tool_result, etc.), matching how the major providers actually work under the hood.

#### Required content types

| Content Type | Fields | Description |
|-------------|--------|-------------|
| **TextContent** | `text: String` | Plain text content (replaces the current `content: String`) |
| **ImageContent** | `imageData: ImageData` | An image embedded in the message |
| **ToolResultContent** | `toolCallId: String`, `content: List<MessageContent>` | Result of a tool call (may contain images) |

#### `ImageData` format

```kotlin
sealed interface ImageData {
    /** Base64-encoded image */
    data class Base64(
        val data: String,          // base64-encoded bytes (no prefix)
        val mediaType: MediaType,  // image/jpeg, image/png, image/webp
    ) : ImageData
    
    /** URL-referenced image */
    data class Url(
        val url: String,
        val mediaType: MediaType? = null,  // optional, may be inferred from URL
    ) : ImageData
}

enum class MediaType(val mime: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    GIF("image/gif"),
}
```

#### New `Message` model

```kotlin
data class Message(
    val role: MessageRole,
    val content: List<MessageContent>,    // ← LIST of content blocks
    val toolCallId: String?,
    val toolCalls: List<ToolCall>?,
) {
    companion object {
        /** Convenience for text-only messages (backward compat) */
        fun text(role: MessageRole, text: String): Message
    }
}
```

**Backward compatibility:** `Message.text(role, "hello")` creates `Message(role, listOf(TextContent("hello")))`. All existing Spola code that constructs `Message(role, "hello", ...)` needs a compatibility bridge or migration.

### 3.2 Provider Interface Changes

Each provider (OpenAI, Anthropic, Gemini, Ollama, openai-compat) serializes `MessageContent` differently. TramAI's `ModelProvider` interface must:

1. **Accept `List<MessageContent>`** in the request
2. **Serialize content blocks** to the provider's native format
3. **Deserialize image data** from provider responses (e.g., if the provider returns generated images)

#### Provider-specific serialization patterns

| Provider | Image Format | Max Images per Message | Max Size |
|----------|-------------|----------------------|----------|
| **OpenAI** | Content array with `type: "image_url"` → `url: "data:image/jpeg;base64,..."` or URL | Many | 20MB total |
| **Anthropic** | Content blocks, `type: "image"` → `source: { type: "base64", media_type: "image/jpeg", data: "..." }` | Many (within context) | 100MB total |
| **Gemini** | `inlineData` with `mimeType` and `data`, or `fileData` with `fileUri` | 10-16 (varies) | 20MB per image |
| **Ollama** | `images: ["base64..."]` array alongside text | 1 per message part | — |

#### `ModelRequest` changes

```kotlin
data class ModelRequest(
    val messages: List<Message>,
    val tools: List<ToolDefinition>?,
    val temperature: Double?,
    val maxTokens: Int?,
    // NEW:
    val imageDetail: ImageDetail = ImageDetail.AUTO,  // low/high/auto
)

enum class ImageDetail {
    LOW,    // 512px max dimension, cheaper
    HIGH,   // Full resolution, more tokens
    AUTO,   // Let the provider decide
}
```

### 3.3 Streaming Support

Images affect streaming in two ways:

1. **Image input is never streamed** (it's in the request, not response) — no change needed for image→LLM
2. **Vision responses may include annotations or bounding boxes** — `StreamChunk` should support structured vision output (e.g., coordinates of detected objects)

No critical requirement here for Phase 1 — basic vision works with the existing streaming model.

### 3.4 Tool Calling + Images

Two scenarios:

**A) Tool result contains an image (LLM generated an image)**

When a tool call produces an image (e.g., `image_generate`), the tool result should be able to include the image as a content block so the LLM can "see" what it generated.

```kotlin
// tool returns:
ToolResult.Success(content = listOf(
    TextContent("Generated image of a sunset"),
    ImageContent(ImageData.Url("file:///tmp/spola-backend/images/sunset.png")),
))
```

**B) LLM needs to analyze an image from a file**

The `analyze_image` tool reads a file and creates a `MessageContent` with the image. This image must be included in **subsequent** LLM requests in the ReAct loop.

This means the ReAct loop (in Spola, `SpolaAgent.kt`) needs to support `MessageContent` — it currently assembles `List<Message>` where each message has `content: String`. Requires changes both in TramAI (to support the new Message model) and in Spola (to construct the new Message model).

### 3.5 Token Counting

Vision tokens are significantly more expensive than text tokens. TramAI's `UsageMetrics` and token budget system must account for image tokens:

```kotlin
data class UsageMetrics(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    // NEW:
    val imageCount: Int = 0,           // Number of images in the request
    val imageTokensEstimate: Int = 0,  // Estimated tokens consumed by images
)
```

Spola uses `TokenJuice` for context compression. Image content blocks should be excluded from compression (or compressed differently — e.g., replace with OCR text).

### 3.6 Error Handling

| Error Scenario | Expected Behavior |
|---------------|-------------------|
| Provider doesn't support vision | `ProviderCapabilityException` with clear message |
| Image too large | Truncation/resize hint in error |
| Unsupported media type | Clear error listing supported types |
| Invalid base64 | `ToolInvalidInputException` |
| Image URL unreachable | Retry or fallback to base64 |

TramAI should expose a capability check:

```kotlin
interface ModelProvider {
    suspend fun chat(modelRequest: ModelRequest): ModelResponse
    // NEW:
    fun supportsCapability(capability: ProviderCapability): Boolean
}

enum class ProviderCapability {
    VISION,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    STREAMING,
}
```

### 3.7 Configuration

Spola needs to configure image handling per provider:

```yaml
# In Spola's ~/.spola/config.yaml (or TramAI's provider config)
model: gpt-4o
provider: openai
image:
  detail: auto           # low | high | auto
  max_size_mb: 10
  supported_types: [image/jpeg, image/png, image/webp]
```

### 3.8 Backward Compatibility

**Critical requirement:** TramAI's current users (including Spola) construct messages as `Message(role, "text content", toolCallId, toolCalls)`. The new model must not break this pattern.

Option: Keep the old `content: String` as a convenience that auto-wraps in `TextContent`:

```kotlin
data class Message(
    val role: MessageRole,
    val content: List<MessageContent>,  // primary
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
) {
    // Backward compat constructor
    constructor(role: MessageRole, text: String, toolCallId: String? = null, toolCalls: List<ToolCall>? = null)
        : this(role, listOf(TextContent(text)), toolCallId, toolCalls)
}
```

This way all existing `Message(role, "text", ...)` calls compile unchanged.

---

## 4. Spola Integration Points

Once TramAI supports multimodal, Spola needs to:

| Component | Change Required | Complexity |
|-----------|----------------|-----------|
| **ChatMessage.kt** | Replace `content: String` with `content: List<MessageContent>` | Medium |
| **SpolaAgent.kt** | Update `callLlm()` to construct new Message model | Low |
| **Image Analysis Tool** | Read file → create ImageContent → inject into conversation | Low |
| **Browser Screenshot** | Capture screenshot → create ImageContent → inject | Medium |
| **Image Generation Tool** | Return generated image as ImageContent in tool result | Low |
| **CheckpointManager** | Serialize/deserialize `List<MessageContent>` instead of String | Medium |
| **SqliteSessionStore** | Update JSON serialization for new Message format | Medium |
| **TokenJuice** | Add image-aware compression rules | Low |

---

## 5. Priority Order

| Priority | Feature | Depends On |
|----------|---------|-----------|
| P0 | `MessageContent` sealed hierarchy (TextContent, ImageContent) | — |
| P0 | `ImageData` (Base64 + Url) | — |
| P0 | Backward-compatible `Message` with dual content | MessageContent |
| P0 | OpenAI provider: content array with image_url | Message |
| P1 | Anthropic provider: content blocks with image type | Message |
| P1 | Gemini provider: inlineData/fileData | Message |
| P1 | `ProviderCapability.VISION` check | ModelProvider |
| P2 | Ollama provider: images array | Message |
| P2 | ToolResultContent for image-generating tools | MessageContent |
| P2 | Image-aware token counting | UsageMetrics |
| P2 | Image-aware compression (TokenJuice skip rule) | TokenJuice |
| P3 | Detail parameter (low/high/auto) | ModelRequest |

---

## 6. What Spola Does While TramAI Implements Multimodal

In parallel, Spola will:
- Add **OCR-based image analysis** (Tess4J from ainvoice) as a fallback — works with any TramAI provider today
- Build all other tools (browser, code exec, image gen, clarify) — none depend on TramAI changes
- When TramAI multimodal lands, replace OCR fallback with native vision support
