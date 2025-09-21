# LLM4S Speech Module

A comprehensive speech recognition and text-to-speech synthesis module for the LLM4S project, built with Scala and functional programming principles.

## 🎯 Features

### Speech Recognition (STT)
- **Vosk**: Lightweight, offline speech recognition engine (replaces Sphinx4)
- **Whisper**: High-accuracy transcription via CLI integration
- **Audio Preprocessing**: Resampling, channel conversion, silence trimming
- **Multiple Input Formats**: File, bytes, and stream audio support

### Text-to-Speech (TTS)
- **Tacotron2**: Neural speech synthesis via CLI integration
- **Voice Customization**: Language, speaking rate, pitch, volume control
- **Output Formats**: WAV and raw PCM16 audio support
- **Cross-platform**: Works on Windows, Linux, and macOS

## 🏗️ Architecture

The module follows functional programming principles with:
- **Result Types**: `Either[LLMError, T]` for error handling
- **Pure Functions**: Immutable audio transformations
- **ADTs**: Algebraic Data Types for type-safe modeling
- **Composition**: Functional composition for audio processing pipelines

## 📁 File Structure

```
src/main/scala/org/llm4s/speech/
├── Audio.scala                    # Core audio data structures
├── stt/                          # Speech-to-Text implementations
│   ├── SpeechToText.scala        # STT trait interface
│   ├── VoskSpeechToText.scala    # Vosk integration (replaces Sphinx4)
│   └── WhisperSpeechToText.scala # Whisper CLI integration
├── tts/                          # Text-to-Speech implementations
│   ├── TextToSpeech.scala        # TTS trait interface
│   └── Tacotron2TextToSpeech.scala # Tacotron2 CLI integration
├── processing/                    # Audio preprocessing utilities
│   └── AudioPreprocessing.scala  # Audio transformation functions
├── io/                           # Audio I/O operations
│   └── AudioIO.scala             # File saving utilities
└── util/                         # Cross-platform utilities
    └── PlatformCommands.scala    # OS-agnostic command helpers
```

## 🚀 Quick Start

### Basic Usage

```scala
import org.llm4s.speech._
import org.llm4s.speech.stt.{WhisperSpeechToText, STTOptions}
import org.llm4s.speech.tts.{Tacotron2TextToSpeech, TTSOptions}
import org.llm4s.speech.util.PlatformCommands

// Speech Recognition
val stt = new WhisperSpeechToText(PlatformCommands.mockSuccess)
val audioInput = AudioInput.FileAudio(Paths.get("audio.wav"))
val options = STTOptions(language = Some("en"))
val result = stt.transcribe(audioInput, options)

// Text-to-Speech
val tts = new Tacotron2TextToSpeech(PlatformCommands.echo)
val ttsOptions = TTSOptions(
  voice = Some("en-female"),
  language = Some("en"),
  speakingRate = Some(1.2)
)
val audio = tts.synthesize("Hello, world!", ttsOptions)
```

### Audio Preprocessing

```scala
import org.llm4s.speech.processing.AudioPreprocessing

val audioBytes = // ... your audio data
val audioMeta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)

// Convert to mono, resample to 16kHz for STT
val processed = AudioPreprocessing.standardizeForSTT(
  audioBytes, 
  audioMeta, 
  targetRate = 16000
)
```

## 🔧 Configuration

### Vosk Configuration

```scala
import org.llm4s.speech.stt.VoskSpeechToText

// Use default English model
val stt = new VoskSpeechToText()

// Use custom model path
val stt = new VoskSpeechToText(modelPath = Some("/path/to/vosk-model"))
```

### Environment Variables

```bash
# Vosk Model Path (optional)
VOSK_MODEL_PATH=/path/to/vosk-model
```

## 🧪 Testing

The module includes comprehensive tests that work cross-platform:

```bash
# Run all tests
sbt test

# Run specific test suites
sbt "testOnly org.llm4s.speech.*"

# Cross-compile and test
sbt +test
```

### Test Features
- **Cross-platform Commands**: Automatically detects OS and uses appropriate commands
- **Mock Commands**: Safe testing without external dependencies
- **Audio Processing Tests**: Validates audio transformation functions
- **Integration Tests**: End-to-end STT/TTS workflow testing

## 🌐 Cross-Platform Support

The `PlatformCommands` utility automatically provides the right commands:

| Platform | Echo | File Reader | Directory Listing |
|----------|------|-------------|-------------------|
| Windows  | `cmd /c echo` | `cmd /c type` | `cmd /c dir` |
| POSIX    | `echo` | `cat` | `ls` |

## 📦 Dependencies

```scala
// Vosk for lightweight STT (replaces Sphinx4)
"net.java.dev.jna" % "jna" % "5.13.0"
"com.alphacephei" % "vosk" % "0.3.45"

// Core dependencies
"org.typelevel" %% "cats-core" % "2.9.0"
"com.lihaoyi" %% "upickle" % "3.1.0"
"ch.qos.logback" % "logback-classic" % "1.4.7"
```

## 🔌 External Tools

### Whisper
- **Installation**: `pip install openai-whisper`
- **Usage**: The module integrates with Whisper CLI for transcription
- **Models**: Supports various model sizes (tiny, base, small, medium, large)

### Tacotron2
- **Installation**: Requires Tacotron2 CLI tool
- **Usage**: The module integrates with Tacotron2 CLI for synthesis
- **Features**: Voice customization, language support, audio output

## 📝 Examples

See `samples/src/main/scala/org/llm4s/samples/SpeechSamples.scala` for complete usage examples.

## 🚨 Error Handling

The module uses `Result[T]` (alias for `Either[LLMError, T]`) for robust error handling:

```scala
val result: Result[Transcription] = stt.transcribe(audioInput, options)

result match {
  case Right(transcription) => 
    println(s"Transcript: ${transcription.text}")
  case Left(error) => 
    println(s"Error: ${error.formatted}")
}
```

## 🔮 Future Enhancements

- [ ] Real-time audio streaming support
- [ ] Additional STT backends (Google Speech, Azure Speech)
- [ ] Additional TTS backends (Festival, eSpeak)
- [ ] Audio format conversion utilities
- [ ] Voice cloning capabilities
- [ ] Multi-language model support

## 🤝 Contributing

1. Follow Scala functional programming principles
2. Use `Result` types for error handling
3. Write comprehensive tests
4. Ensure cross-platform compatibility
5. Update documentation for new features

## 📄 License

Part of the LLM4S project - see main project license for details.


