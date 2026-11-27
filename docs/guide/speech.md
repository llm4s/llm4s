---
layout: page
title: Speech (STT/TTS)
description: "Speech-to-text and text-to-speech in Scala with the LLM4S speech module: Vosk, Whisper, OpenAI, Azure and ElevenLabs, with configuration."
search_title: "Speech-to-text and text-to-speech in Scala with LLM4S"
parent: Core
nav_order: 117
grand_parent: User Guide
---

# LLM4S Speech Module

A comprehensive speech recognition and text-to-speech synthesis module for the LLM4S project, built with Scala and functional programming principles.

---

## Features

### Speech Recognition (STT)
- **Vosk**: Lightweight, offline speech recognition engine
- **Whisper**: High-accuracy transcription via CLI integration
- **Audio Preprocessing**: Resampling, channel conversion, silence trimming
- **Multiple Input Formats**: File, bytes, and stream audio support
- **Cloud**: `OpenAISTTClient` (Whisper API) and `AzureSTTClient` (Azure AI Speech), see [Cloud providers](#cloud-providers)

### Text-to-Speech (TTS)
- **Tacotron2**: Neural speech synthesis via CLI integration
- **Cloud**: `OpenAITTSClient`, `ElevenLabsTTSClient` and `AzureTTSClient`, see [Cloud providers](#cloud-providers)
- **Voice Customization**: Language, speaking rate, pitch, volume control
- **Output Formats**: WAV and raw PCM16 audio support
- **Cross-platform**: Works on Windows, Linux, and macOS

---

## Architecture

Speech is the `llm4s-speech` module:

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-speech" % "{{ site.data.project.latest_release }}"
```

Every engine is a `SpeechToText` (`transcribe(input, options)`) or a `TextToSpeech` (`synthesize(text, options)`), and
every call returns a `Result` (`Either[LLMError, A]`) rather than throwing. The local engines (Vosk, and the Whisper
and Tacotron2 command-line tools) send nothing over the network; the [cloud providers](#cloud-providers) are selected
from configuration.

---

## Quick Start

### Local transcription and synthesis

Whisper and Tacotron2 run as command-line programs that must be on the `PATH` (see [External Tools](#external-tools));
pass another command if yours is installed elsewhere.

```scala
import org.llm4s.speech.AudioInput
import org.llm4s.speech.stt.{ STTOptions, WhisperSpeechToText }
import org.llm4s.speech.tts.{ Tacotron2TextToSpeech, TTSOptions }
import java.nio.file.Paths

// Speech recognition with the openai-whisper CLI and its "base" model
val stt    = new WhisperSpeechToText(command = Seq("whisper"), model = "base")
val result = stt.transcribe(AudioInput.FileAudio(Paths.get("audio.wav")), STTOptions(language = Some("en")))

// Text-to-speech with a Tacotron2 command-line tool
val tts   = new Tacotron2TextToSpeech(command = Seq("tacotron2-cli"))
val audio = tts.synthesize("Hello, world!", TTSOptions(language = Some("en"), speakingRate = Some(1.2)))
```

### Audio Preprocessing

```scala
import org.llm4s.speech.AudioMeta
import org.llm4s.speech.processing.AudioPreprocessing
import java.nio.file.{ Files, Paths }

val audioBytes = Files.readAllBytes(Paths.get("recording.pcm")) // raw 16-bit PCM samples
val audioMeta  = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)

// Convert to mono, resample to 16kHz for STT
val processed = AudioPreprocessing.standardizeForSTT(
  audioBytes,
  audioMeta,
  targetRate = 16000
)
```

`AudioPreprocessing.resamplePcm16` (the resampling step of `standardizeForSTT`) checks its arguments before it
converts anything. The target rate and the source rate must be between 1 and 768000 Hz, the channel count between 1
and 64, and the bit depth a multiple of 8 from 8 to 32; anything else is a `Left(ValidationError)` naming the field
(`targetRate`, `source.sampleRate`, `source.numChannels` or `source.bitDepth`). An output above 256 MiB (about 46
minutes of 48 kHz mono) is a `ValidationError` on `targetRate` too, checked before anything is allocated: resample a
longer recording in pieces. The output has exactly `round(frames * targetRate / sourceRate)` frames, so empty input
gives empty output and a trailing partial frame is ignored; a converter that delivers noticeably fewer frames than
that is a `ProcessingError`, not a silently padded success. Java Sound's converter delays the signal by a fraction of a millisecond, and the length fit drops the
matching tail, so the source's final fraction of a millisecond is not in the output.

---

## Configuration

### Vosk Configuration

Vosk runs in the JVM from a model directory you download from the
[Vosk model list](https://alphacephei.com/vosk/models). With no `modelPath` it loads
`models/vosk-model-small-en-us-0.15`, relative to the working directory; no environment variable is read.

```scala
import org.llm4s.speech.stt.VoskSpeechToText

// The small English model, unpacked into ./models/vosk-model-small-en-us-0.15
val stt = new VoskSpeechToText()

// A model somewhere else
val german = new VoskSpeechToText(modelPath = Some("/opt/vosk/vosk-model-small-de-0.15"))
```

---

## External Tools

### Whisper
- **Installation**: `pip install openai-whisper`
- **Usage**: `WhisperSpeechToText` runs the openai-whisper CLI (whisper.cpp's flags differ and are not supported)
  with `--output_dir` set to a fresh temporary directory and `--verbose False`, reads the transcript from there and
  deletes it: nothing is written to the working directory or beside the input
- **Output format**: `txt` (the default) or `json` (always, when word timestamps are requested); `srt`, `vtt`,
  `tsv` and `all` are refused with `STTError.InvalidInput`
- **Language**: a BCP 47 tag is passed as its primary language (`en-US` as `en`), which is what the CLI accepts
- **Errors**: a failed run reports the exit code and the end of the program's stderr
- **Models**: Supports various model sizes (tiny, base, small, medium, large)

### Tacotron2
- **Installation**: a command-line wrapper around a Tacotron2 model, called as
  `<command> --text <text> --out <file.wav>`, plus `--voice`, `--lang`, `--rate`, `--pitch` and `--gain` from
  `TTSOptions`
- **Output**: WAV (PCM); MP3 is offered only by the cloud clients

---

## Cloud providers

Three cloud TTS clients and two cloud STT clients live in `org.llm4s.speech.tts.provider` and
`org.llm4s.speech.stt.provider`. They are selected like chat models, with a `provider/model`
string, and built through `SpeechProviderSelector`:

| Setting | Values |
|---|---|
| `SPEECH_TTS_MODEL` | `openai/tts-1`, `openai/tts-1-hd`, `elevenlabs/<voice-id>`, `azure/<voice-name>` (e.g. `azure/en-US-JennyNeural`) |
| `SPEECH_TTS_VOICE` | optional voice override (OpenAI default `alloy`) |
| `SPEECH_STT_MODEL` | `openai/whisper-1`, `azure/<default-language>` (e.g. `azure/en-US`) |
| `OPENAI_API_KEY` | OpenAI TTS and STT |
| `ELEVENLABS_API_KEY` | ElevenLabs TTS (optional `ELEVENLABS_MODEL_ID`, default `eleven_multilingual_v2`) |
| `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION` | Azure TTS and STT |

Only the selected provider's credentials are needed. The same keys exist as HOCON under
`llm4s.speech.*` (see this module's `reference.conf`); `OPENAI_SPEECH_BASE_URL`,
`ELEVENLABS_BASE_URL`, `AZURE_SPEECH_TTS_BASE_URL` and `AZURE_SPEECH_STT_BASE_URL` override the
endpoints.

```scala
import org.llm4s.speech.SpeechProviderSelector
import org.llm4s.speech.tts.TTSOptions
import org.llm4s.speech.stt.STTOptions
import org.llm4s.speech.AudioInput

for {
  tts   <- SpeechProviderSelector.tts()   // reads llm4s.speech.tts / SPEECH_TTS_MODEL
  audio <- tts.synthesize("Hello from LLM4S")
  stt   <- SpeechProviderSelector.stt()   // reads llm4s.speech.stt / SPEECH_STT_MODEL
  text  <- stt.transcribe(AudioInput.FileAudio(java.nio.file.Paths.get("hello.wav")), STTOptions())
} yield text.text
```

To pick the model in code instead of `SPEECH_*_MODEL`, use
`SpeechConfigLoader.tts("openai/tts-1")` / `.stt("openai/whisper-1")` (credentials still come from
config) and `SpeechProviderSelector.getTTSClient` / `getSTTClient`.

Behaviour worth knowing:

- **Audio is raw PCM.** The TTS clients request raw 24 kHz, 16-bit, mono PCM from each service, so
  `GeneratedAudio.data` is headerless PCM with an accurate `AudioMeta`, the same shape Tacotron2
  produces. Write a playable file with `WavFileGenerator.saveAsWav(audio, path)`. The default
  `GeneratedAudio.format` is labelled `AudioFormat.WavPcm16` but, as above, the bytes carry no WAV header.
- **MP3 is opt-in.** `TTSOptions(outputFormat = AudioFormat.Mp3)` makes the OpenAI, ElevenLabs and Azure
  clients request the service's MP3 (`response_format=mp3`, `output_format=mp3_44100_128`,
  `audio-24khz-48kbitrate-mono-mp3`) and return its bytes untouched: no decoding, no resampling.
  `GeneratedAudio.format` is `AudioFormat.Mp3`, so the PCM helpers (`WavFileGenerator.saveAsWav`,
  `AudioIO.saveWav` / `saveRawPcm16`, `AudioPreprocessing.standardizeForSTT(audio, rate)`) refuse it with a
  `ValidationError`; save it with `AudioIO.saveMp3(audio, path)`. `AudioMeta` carries the requested
  sample rate and channel count as nominal labels (not verified against the service) and `bitDepth = 0`: MP3 has
  no sample width, so do not compute a duration from the byte count. Tacotron2 produces PCM only and refuses MP3. The default stays PCM.
- **OpenAI TTS limits are checked locally**: text over 4096 characters and a `speakingRate` outside
  0.25 to 4.0 are a `ValidationError` before any request is sent. Split long text yourself; the clients
  do not chunk it.
- **STT input is WAV.** `BytesAudio` and `StreamAudio` are treated as WAV data, as for Whisper and Vosk.
  Azure's REST endpoint recognises about 60 seconds of audio per request.
- **Errors map like the chat providers**: HTTP 401/403 is `AuthenticationError`, 429 is
  `RateLimitError` (with `Retry-After`), 400 is `ValidationError`, other statuses are `ServiceError`,
  and a timeout or refused connection is `TimeoutError` / `NetworkError`.
- The clients take an `Llm4sHttpClient`, so tests inject a stub and never touch the network.

---

## Error Handling

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

---

## Testing

The cloud clients take an `Llm4sHttpClient`, so a test can inject a stub and never touch the network; the local
engines take the command to run, so a test can pass a script of its own.

---

## See Also

- [Examples](/examples/) - Working code examples
- [API Reference](/api/) - Complete API documentation
