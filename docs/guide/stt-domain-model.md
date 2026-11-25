---
layout: page
title: STT Domain Model
description: "The speech-to-text domain model of LLM4S: STTOptions, WordTimestamp, Transcription and the STTError cases, with validation and examples."
search_title: "STT domain model in LLM4S: options, transcripts, errors"
parent: Core
nav_order: 118
grand_parent: User Guide
---

# STT Domain Model

The types every speech-to-text engine in `llm4s-speech` (`org.llm4s.speech.stt`) takes and returns: the options of a
request, the transcription with its word timings, and the errors. See [Speech](speech) for the engines themselves.

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-speech" % "{{ site.data.project.latest_release }}"
```

**Construction checks.** `STTOptions`, `WordTimestamp` and `Transcription` check their arguments with `require`, so
constructing one with an invalid value throws an `IllegalArgumentException`. To get a `Result` instead, build options
and words with `STTOptions.validate` and `WordTimestamp.validate` ([below](#typed-validation-result-based)).

## Types

### STTOptions
The options of a transcription request:

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
- `confidenceThreshold`: must be in [0.0, 1.0]; the constructor throws otherwise
- `language`: a BCP 47 tag (e.g., "en", "en-US", "fr-FR") or None; checked by `STTOptions.validate`, not by the
  constructor

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
  processingTime: Option[FiniteDuration] = None  // Transcription duration
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
import scala.concurrent.duration.*

val trans = Transcription(
  text = "Hello world",
  language = Some("en"),
  confidence = Some(0.88),
  timestamps = List(
    WordTimestamp("Hello", 0.0, 0.5, speakerId = Some(1), confidence = Some(0.92)),
    WordTimestamp("world", 0.6, 1.1, speakerId = Some(1), confidence = Some(0.84))
  ),
  processingTime = Some(245.millis)
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

**Retrying:** `EngineNotAvailable` is `isRetryable`; `UnsupportedFormat`, `InvalidInput` and `ProcessingFailed`
are not, so the library's automatic retries leave a `ProcessingFailed` alone although its own `retryable` flag is
`true`: the clients raise it for
an empty transcription, audio with no recognisable speech and an unparseable response.

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

## Error Handling Strategy

### Validation and runtime errors
An invalid constructor argument throws, so build values from untrusted input with the `validate` factories. A
transcription's own failures are always a `Left`:

```scala
val options: Result[STTOptions] = STTOptions.validate(confidenceThreshold = userThreshold)

val result: Result[Transcription] = options.flatMap(o => provider.transcribe(input, o))
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
  trans.processingTime.foreach { took =>
    println(s"Transcription took ${took.toMillis}ms")
    println(s"Average time per second of audio: ${took.toMillis / trans.totalDuration.getOrElse(1.0)}ms")
  }
}
```

## Troubleshooting

### Validation Errors
```scala
// An invalid language tag is a Left from validate; the constructor would accept it
STTOptions.validate(language = Some("english")) match {
  case Left(error)    => System.err.println(s"Invalid option: ${error.message}")
  case Right(options) => // use options
}
```

### Metadata Not Populated
Word timings, confidence and speaker ids are filled in only when the engine reports them: ask for timings with
`enableTimestamps = true`, and check `transcription.hasTimestamps` before reading them. `provider.supportedFormats`
lists the audio formats an engine accepts.

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
| Transcription.processingTime | Option[FiniteDuration] | No | None |
| STTError.retryable | Boolean | Always | Variant-specific |
| STTError.userFriendly | String | Always | Variant-specific |
