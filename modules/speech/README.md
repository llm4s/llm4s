# llm4s-speech

Speech-to-text (STT) and text-to-speech (TTS) for LLM4S: cloud clients for OpenAI, ElevenLabs and Azure, local
engines that run on your machine, and the audio utilities around them (WAV I/O, validation, preprocessing,
conversion). It is not an LLM client: chat completions are in [`llm4s-core`](../core) and the provider modules, and
it does not bundle a speech model or the Whisper/Tacotron2 programs, which you install yourself.

## Installation

Artifact: `org.llm4s::llm4s-speech` (`name := "llm4s-speech"` in `build.sbt`). It depends on `llm4s-core`. See the
[installation guide](../../docs/getting-started/installation.md) for the current version and how to add it.

## What is in it

Cloud clients (HTTP, configured through `reference.conf`):

- TTS: `OpenAITTSClient`, `ElevenLabsTTSClient`, `AzureTTSClient` (`org.llm4s.speech.tts.provider`)
- STT: `OpenAISTTClient`, `AzureSTTClient` (`org.llm4s.speech.stt.provider`)
- `SpeechProviderSelector`: builds the TTS or STT client that `llm4s.speech.tts.model` / `llm4s.speech.stt.model`
  names (`SpeechProviderSelector.tts()`, `.stt()`)
- `SpeechConfigLoader`, `TTSConfig`, `STTConfig` (`org.llm4s.speech.config`): read and hold the settings; both
  configs redact the API key in `toString`

Local engines (no cloud account, but an external program or model):

- `Tacotron2TextToSpeech`: runs a `tacotron2-cli` command (default) that you supply
- `VoskSpeechToText`: Vosk, which needs a Vosk model directory on disk
- `WhisperSpeechToText`: runs a `whisper` command (whisper.cpp or openai-whisper) that you supply

Audio utilities: `AudioIO`, `WavFileGenerator`, `AudioValidator`, `AudioPreprocessing`, `AudioConverter`.

## Configuration

The module reads the `llm4s.speech` block of its `reference.conf`
([`src/main/resources/reference.conf`](src/main/resources/reference.conf)); override it in your
`application.conf` or with `-D` properties. Environment variables are bound through `${?VAR}`:

| Key | Environment variable |
|---|---|
| `llm4s.speech.tts.model` (`provider/model`) | `SPEECH_TTS_MODEL` |
| `llm4s.speech.tts.voice` | `SPEECH_TTS_VOICE` |
| `llm4s.speech.stt.model` (`provider/model`) | `SPEECH_STT_MODEL` |
| `llm4s.speech.openai.apiKey` / `.baseUrl` | `OPENAI_API_KEY` / `OPENAI_SPEECH_BASE_URL` |
| `llm4s.speech.elevenlabs.apiKey` / `.baseUrl` / `.modelId` | `ELEVENLABS_API_KEY` / `ELEVENLABS_BASE_URL` / `ELEVENLABS_MODEL_ID` |
| `llm4s.speech.azure.apiKey` / `.region` | `AZURE_SPEECH_KEY` / `AZURE_SPEECH_REGION` |
| `llm4s.speech.azure.ttsBaseUrl` / `.sttBaseUrl` | `AZURE_SPEECH_TTS_BASE_URL` / `AZURE_SPEECH_STT_BASE_URL` |

A key is only required by the provider the model spec selects. What needs what:

- OpenAI, ElevenLabs and Azure clients need an API key (Azure also a region).
- `Tacotron2TextToSpeech` and `WhisperSpeechToText` need their command-line programs installed.
- `VoskSpeechToText` needs a Vosk model.

## Tests

```bash
sbt speech/test
```

The cloud clients are tested against stubbed or in-process HTTP servers, so no API key is needed. A few suites skip
themselves when a prerequisite is missing: those that use a mock WAV writer need a Python interpreter, the local
engine cancellation suite needs a POSIX shell, and the Whisper integration suite is cancelled when the Whisper CLI is
not installed.

## More

- User guide: [docs/guide/speech.md](../../docs/guide/speech.md)
- Samples: [`SpeechSamples.scala`](../samples/src/main/scala/org/llm4s/samples/SpeechSamples.scala)
