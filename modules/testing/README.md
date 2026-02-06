# llm4s-testing

A testing utilities module for deterministic testing of LLM applications built with llm4s.

## Features

- **MockLLMClient** - Define expected responses programmatically
- **RecordingLLMClient** - Capture real API interactions for replay
- **PlaybackLLMClient** - Replay recorded interactions for deterministic tests
- **Scrubber** - Remove sensitive data (API keys, tokens) from recordings

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-testing" % "version" % Test
```

## Quick Start

### MockLLMClient

```scala
import org.llm4s.testing.MockLLMClient
import org.llm4s.llmconnect.model._

val mock = new MockLLMClient()

// Simple exact match
mock.whenExactly(conversation, options)(response)

// Partial content matching
mock.whenContains("hello").thenReturn(response)

// Error simulation
mock.whenContains("error").thenFail(ValidationError("test", "Simulated"))

// Always return same response
mock.alwaysReturn(response)
```

### Recording & Playback

```scala
import org.llm4s.testing.{RecordingLLMClient, PlaybackLLMClient, Scrubber}

// Record interactions from a real client
val recorder = new RecordingLLMClient(realOpenAIClient)
recorder.complete(conversation, options)

// Save with API keys scrubbed
recorder.saveWithScrubbing("test-fixtures/chat.json", Scrubber.default)

// Replay in tests
val playback = PlaybackLLMClient.fromFile("test-fixtures/chat.json")
val result = playback.complete(conversation, options) // Deterministic!
```

### Matching Modes

```scala
import org.llm4s.testing.{PlaybackLLMClient, MatchingMode}

// Strict: exact match (default)
PlaybackLLMClient.fromFile("chat.json", MatchingMode.Strict)

// Lenient: ignores whitespace differences
PlaybackLLMClient.fromFile("chat.json", MatchingMode.Lenient)

// ContentOnly: matches message content, ignores options
PlaybackLLMClient.fromFile("chat.json", MatchingMode.ContentOnly)
```

### Scrubbing

```scala
import org.llm4s.testing.Scrubber

// Default patterns: OpenAI, Anthropic, Google keys, Bearer tokens
val scrubbed = Scrubber.default.scrub(jsonContent)

// Custom patterns
val customScrubber = Scrubber.custom(
  "my-secret-[0-9]+".r -> "[REDACTED]"
)

// Chain patterns
val combined = Scrubber.default.addPattern("custom-key-.*".r, "[CUSTOM_KEY]")
```

## API Reference

### MockLLMClient

| Method | Description |
|--------|-------------|
| `when(handler)` | Register custom matching function |
| `whenExactly(conv, opts)(response)` | Match exact conversation and options |
| `whenContains(text).thenReturn(response)` | Match if any message contains text |
| `whenContains(text).thenFail(error)` | Return error if message contains text |
| `alwaysReturn(response)` | Always return the same response |
| `alwaysFail(error)` | Always return the same error |
| `reset()` | Clear all expectations |

### RecordingLLMClient

| Method | Description |
|--------|-------------|
| `save(path)` | Save recordings to JSON file |
| `saveWithScrubbing(path, scrubber)` | Save with sensitive data removed |
| `getRecordings` | Get list of recorded interactions |
| `clear()` | Clear all recordings |

### PlaybackLLMClient

| Method | Description |
|--------|-------------|
| `fromFile(path)` | Load recordings with strict matching |
| `fromFile(path, mode)` | Load with specified matching mode |
| `fromRecordings(list, mode)` | Create from in-memory recordings |
| `recordingCount` | Number of loaded recordings |

### Scrubber

| Method | Description |
|--------|-------------|
| `Scrubber.default` | Pre-configured with common API key patterns |
| `Scrubber.none` | No scrubbing (keep all data) |
| `Scrubber.custom(patterns*)` | Create with custom patterns |
| `scrubber.addPattern(regex, replacement)` | Add pattern to existing scrubber |
