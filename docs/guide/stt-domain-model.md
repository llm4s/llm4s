# STT Domain Model Expansion (#894) - Implementation Guide

## Overview
This document describes the expanded STT domain model that includes richer metadata, validation, and error handling.

## Domain Model Changes

### STTOptions
Extended speech-to-text configuration with the following fields:

```scala
final case class STTOptions(
  language: Option[String] = None,           // BCP 47 language tag (e.g., "en-US")
  prompt: Option[String] = None,             // Context to guide transcription
  enableTimestamps: Boolean = false,         // Include word-level timings
  diarization: Boolean = false,              // Detect and separate speakers
  confidenceThreshold: Double = 0.0          // Minimum confidence [0.0, 1.0]
)
```

**Validation:**
- `confidenceThreshold`: Must be in range [0.0, 1.0]
- `language`: Must be valid BCP 47 tag (e.g., "en", "en-US", "fr-FR") or None

**Example:**
```scala
val options = STTOptions(
  language = Some("en-US"),
  enableTimestamps = true,
  confidenceThreshold = 0.85
)
```

### WordTimestamp
Word-level metadata with timing and confidence information:

```scala
final case class WordTimestamp(
  word: String,
  startSec: Double,
  endSec: Double,
  speakerId: Option[Int] = None,             // Speaker ID (for diarization)
  confidence: Option[Double] = None          // Confidence score [0.0, 1.0]
)
```

**Validation:**
- `startSec` >= 0 and `endSec` >= `startSec`
- Time values must represent valid audio positions

**Helper Methods:**
- `duration: Double` - Returns duration in seconds

**Example:**
```scala
val word = WordTimestamp(
  word = "hello",
  startSec = 0.0,
  endSec = 0.5,
  speakerId = Some(1),
  confidence = Some(0.95)
)

val duration = word.duration  // 0.5 seconds
```

### Transcription
Complete transcription result with rich metadata:

```scala
final case class Transcription(
  text: String,
  language: Option[String],
  confidence: Option[Double] = None,         // Overall confidence
  timestamps: List[WordTimestamp] = Nil,     // Word-level timings
  meta: Option[AudioMeta] = None,            // Source audio metadata
  processingTimeMs: Option[Long] = None      // Transcription duration
)
```

**Helper Methods:**
- `hasTimestamps: Boolean` - True if timestamps are available
- `totalDuration: Option[Double]` - Maximum end time (handles unsorted timestamps)
- `filterByConfidence(threshold: Double): Transcription` - Keep only words with confidence >= threshold
- `uniqueSpeakers: Set[Int]` - Set of speaker IDs in transcription
- `wordCount: Int` - Number of words (from timestamps if available, else estimated from text)
- `averageConfidence: Option[Double]` - Average confidence of timestamped words
- `minConfidence: Option[Double]` - Minimum confidence among timestamped words
- `maxConfidence: Option[Double]` - Maximum confidence among timestamped words
- `wordsBySpeaker(speakerId: Int): List[String]` - Words spoken by a specific speaker
- `speakerSegments: Map[Int, List[(Double, Double)]]` - Time segments for each speaker
- `meetsQualityThreshold(minConfidence: Double = 0.5, minWords: Int = 1): Boolean` - Check if meets quality standards

**Example:**
```scala
val trans = Transcription(
  text = "Hello world",
  language = Some("en"),
  confidence = Some(0.88),
  timestamps = List(
    WordTimestamp("Hello", 0.0, 0.5, speakerId = Some(1), confidence = Some(0.92)),
    WordTimestamp("world", 0.6, 1.1, speakerId = Some(1), confidence = Some(0.84))
  ),
  processingTimeMs = Some(245)
)

// Use helper methods
trans.totalDuration          // Some(1.1) seconds
trans.uniqueSpeakers         // Set(1)
trans.hasTimestamps          // true
trans.wordCount              // 2
trans.averageConfidence      // Some(0.88)
trans.maxConfidence          // Some(0.92)
trans.wordsBySpeaker(1)      // List("Hello", "world")

val highConfidence = trans.filterByConfidence(0.9)  // Only "Hello"
trans.meetsQualityThreshold(minConfidence = 0.8, minWords = 2)  // true
```

### STTError (Sealed Trait)
Structured error handling with specific variants:

#### EngineNotAvailable
Provider/engine is unavailable or crashed.
- **Retryable:** Yes
- **Fields:** `message`, `context`

```scala
STTError.EngineNotAvailable(
  "Model not found at /usr/share/vosk",
  context = Map("model_path" -> "/usr/share/vosk", "version" -> "0.12.0")
)
```

#### UnsupportedFormat
Audio format not supported by provider.
- **Retryable:** No
- **Fields:** `message`, `format`, `supported`, `context`

```scala
STTError.UnsupportedFormat(
  "Format not supported",
  format = "audio/wma",
  supported = List("audio/wav", "audio/mp3", "audio/flac")
)
```

#### ProcessingFailed
Transcription processing failed (network, timeout, etc).
- **Retryable:** Yes
- **Fields:** `message`, `cause`, `context`

```scala
STTError.ProcessingFailed(
  "Network timeout during transcription",
  cause = Some(new TimeoutException()),
  context = Map("retry_count" -> "2", "timeout_ms" -> "30000")
)
```

#### InvalidInput
Invalid input or configuration.
- **Retryable:** No
- **Fields:** `message`, `context`

```scala
STTError.InvalidInput(
  "Confidence threshold must be between 0.0 and 1.0",
  context = Map("provided_value" -> "1.5", "field" -> "confidenceThreshold")
)
```

**Common Trait Methods:**
- `retryable: Boolean` - Indicates if operation should be retried
- `userFriendly: String` - End-user friendly error message
- `context: Map[String, String]` - Structured error context

## Typed Validation (Result-based)

### STTOptions.validate Factory
For Result-based validation of configuration at runtime:

```scala
val result: Result[STTOptions] = STTOptions.validate(
  language = Some("en-US"),
  confidenceThreshold = 0.85,
  enableTimestamps = true
)

result match {
  case Right(options) => 
    // Use validated options
    val trans = provider.transcribe(audio, options)
  case Left(error: STTError.InvalidInput) =>
    // Handle validation error
    println(s"Validation failed: ${error.userFriendly}")
    println(s"Details: ${error.context}")
}
```

**Validation Rules:**
- `confidenceThreshold`: Must be in [0.0, 1.0]
- `language`: Must be valid BCP 47 tag or None
- `prompt`: Must be <= 4000 characters

### WordTimestamp.validate Factory
Create timestamped words with validation:

```scala
val result: Result[WordTimestamp] = WordTimestamp.validate(
  word = "hello",
  startSec = 0.0,
  endSec = 0.5,
  confidence = Some(0.95)
)

result match {
  case Right(ts) => println(s"Valid: ${ts.word} (${ts.duration}s)")
  case Left(error) => println(s"Invalid: ${error.message}")
}
```

**Validation Rules:**
- `word`: Must not be empty
- `startSec`: Must be >= 0
- `endSec`: Must be >= startSec
- `confidence`: If provided, must be in [0.0, 1.0]

## Migration Guide

### For Code Creating STTOptions
**Before (direct construction):**
```scala
val options = STTOptions(
  language = Some("en"),
  confidenceThreshold = 0.85
)
// Could throw IllegalArgumentException if values invalid
```

**After (recommended - use typed validation):**
```scala
val result = STTOptions.validate(
  language = Some("en"),
  confidenceThreshold = 0.85
)

result match {
  case Right(options) => /* use options */
  case Left(error) => /* handle error */
}
```

**Backward Compatibility:**
Direct construction still works for valid values, but typed validation is recommended for robustness.

### For Code Using filterByConfidence
**Before:**
```scala
// Would filter based on meetsConfidence logic
val filtered = trans.filterByConfidence(0.8)
```

**After (breaking change):**
```scala
// Now filters to ONLY keep words with confidence >= threshold
// Words without confidence scores are excluded
val filtered = trans.filterByConfidence(0.8)
```

**Migration note:** If you relied on keeping words without confidence scores, you need to adjust your logic.

### For Code Using Additional Helper Methods
**New in this version:**
```scala
traits.averageConfidence      // Average of all word confidences
trans.minConfidence            // Minimum word confidence
trans.maxConfidence            // Maximum word confidence
trans.wordCount                // Total word count
trans.wordsBySpeaker(id)       // Filter words by speaker
trans.speakerSegments          // Get time ranges by speaker
trans.meetsQualityThreshold()  // Quality checks
```

These methods are additive and don't affect existing code.

### Updated Provider Implementations

Providers now populate additional metadata:

```scala
// WhisperSpeechToText and VoskSpeechToText
// - Track processingTimeMs during transcription
// - Extract confidence scores from output (when available)
// - Extract word-level timestamps (when enableTimestamps=true)
// - Handle diarization metadata (when available)
```

**Example of updated provider result:**
```scala
val transcription = Transcription(
  text = "Hello world",
  language = Some("en"),
  confidence = Some(0.88),           // Extracted from provider
  timestamps = List(                 // Extracted when enabled
    WordTimestamp("Hello", 0.0, 0.5, confidence = Some(0.92)),
    WordTimestamp("world", 0.6, 1.1, confidence = Some(0.84))
  ),
  processingTimeMs = Some(234)       // Tracked by provider
)
```

## Error Handling Strategy

### Provider-level Validation
Validation happens at construction and public API boundaries:

```scala
// This validates immediately
try {
  val options = STTOptions(confidenceThreshold = 1.5)  // Throws IllegalArgumentException
} catch {
  case e: IllegalArgumentException => 
    // Handle validation error
}

// Use Result type for runtime errors
val result: Result[Transcription] = provider.transcribe(input, options)
```

### Error Classification
Errors are classified for automatic retry strategies:

```scala
def shouldRetry(error: STTError): Boolean = error.retryable

val result = provider.transcribe(audio, options)
result match {
  case Left(error: STTError) if shouldRetry(error) =>
    // Retry the operation
  case Left(error: STTError) =>
    // Don't retry - user error or unsupported format
    println(error.userFriendly)  // Show to user
  case Right(transcription) =>
    // Success - use transcription
}
```

## Feature Examples

### Using Word-Level Timestamps
```scala
val options = STTOptions(enableTimestamps = true)
val result = provider.transcribe(audio, options)

result.map { trans =>
  trans.timestamps.foreach { ts =>
    println(s"${ts.word}: ${ts.startSec}s - ${ts.endSec}s")
  }
}
```

### Filtering Low-Confidence Words
```scala
val trans = Transcription(...)
val highConfidence = trans.filterByConfidence(0.85)

println(s"Total words: ${trans.timestamps.length}")
println(s"High-confidence words: ${highConfidence.timestamps.length}")
```

### Speaker Diarization
```scala
val options = STTOptions(diarization = true)
val result = provider.transcribe(audio, options)

result.map { trans =>
  println(s"Detected speakers: ${trans.uniqueSpeakers}")
  trans.timestamps.foreach { ts =>
    println(s"Speaker ${ts.speakerId}: ${ts.word}")
  }
}
```

### Processing Metrics
```scala
val result = provider.transcribe(audio, options)

result.map { trans =>
  trans.processingTimeMs.foreach { ms =>
    println(s"Transcription took ${ms}ms")
    println(s"Average time per second of audio: ${ms / trans.totalDuration.getOrElse(1.0)}")
  }
}
```

## Testing Guide

### Unit Tests
- Domain model validation (STTOptions, WordTimestamp, Transcription)
- STTError variants and their properties
- Helper method correctness (filterByConfidence, totalDuration, uniqueSpeakers)

### Integration Tests
- Provider adapters populate metadata correctly
- Error handling in real transcription scenarios
- Confidence threshold validation with actual provider output

### Edge Cases Covered
- Unicode and multilingual text
- Very long transcriptions
- Many speakers (diarization stress test)
- Zero-duration words
- Unsorted timestamps
- Missing optional fields in various combinations

## Performance Considerations

1. **Processing Time Tracking:**
   - Minimal overhead - simple time delta
   - Helps identify slow providers or configurations

2. **Timestamp Extraction:**
   - Regex-based parsing for JSON/VTT output
   - Optional - only parsed if enableTimestamps=true

3. **Confidence Filtering:**
   - Lazy: creates new list only when called
   - Can be used to reduce downstream processing

## Troubleshooting

### Validation Errors
```scala
// Validate options before use
try {
  val options = STTOptions(language = Some("invalid"))
} catch {
  case e: IllegalArgumentException =>
    System.err.println(s"Invalid option: ${e.getMessage}")
}
```

### Metadata Not Populated
```scala
// Check if provider supports the feature
if (provider.supportedFeatures.contains("timestamps")) {
  // Use enableTimestamps=true
} else {
  // Timestamps won't be extracted
}
```

### Error Classification
```scala
// Know which errors are retryable
val result = provider.transcribe(audio, options)
result match {
  case Left(e: STTError.ProcessingFailed) if e.retryable =>
    // Safe to retry
  case Left(e: STTError.InvalidInput) =>
    // Never retryable - fix input
  case _ => ()
}
```

## API Summary

| Item | Type | Required | Default |
|------|------|----------|---------|
| STTOptions.language | Option[String] | No | None |
| STTOptions.prompt | Option[String] | No | None |
| STTOptions.enableTimestamps | Boolean | No | false |
| STTOptions.diarization | Boolean | No | false |
| STTOptions.confidenceThreshold | Double | No | 0.0 |
| WordTimestamp.speakerId | Option[Int] | No | None |
| WordTimestamp.confidence | Option[Double] | No | None |
| Transcription.confidence | Option[Double] | No | None |
| Transcription.processingTimeMs | Option[Long] | No | None |
| STTError.retryable | Boolean | Always | Variant-specific |
| STTError.userFriendly | String | Always | Variant-specific |
