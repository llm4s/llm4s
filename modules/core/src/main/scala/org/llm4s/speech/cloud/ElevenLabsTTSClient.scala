package org.llm4s.speech.cloud

import org.llm4s.error.LLMError
import org.llm4s.http.{ HttpRawResponse, Llm4sHttpClient }
import org.llm4s.speech.{ AudioFormat, AudioMeta, GeneratedAudio }
import org.llm4s.speech.tts.{ TTSOptions, TextToSpeech }
import org.llm4s.types.Result

import scala.util.Try

/**
 * Cloud TTS client for ElevenLabs text-to-speech API.
 *
 * Synthesizes audio via `POST /v1/text-to-speech/{voiceId}` and returns raw MP3 bytes.
 *
 * @param apiKey   ElevenLabs API key
 * @param voiceId  ElevenLabs voice identifier
 * @param baseUrl  API base URL, defaults to ElevenLabs production endpoint
 * @param http     HTTP client (injected for testing)
 */
final class ElevenLabsTTSClient private (
  apiKey: String,
  voiceId: String,
  baseUrl: String,
  http: Llm4sHttpClient
) extends TextToSpeech {

  override val name: String = "elevenlabs-tts"

  override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] = {
    val url  = s"$baseUrl/text-to-speech/$voiceId"
    val body = s"""{"text":${ujson.write(text)},"model_id":"eleven_monolingual_v1"}"""

    val headers = Map(
      "xi-api-key"   -> apiKey,
      "Content-Type" -> "application/json",
      "Accept"       -> "audio/mpeg"
    )

    Try {
      http.postRaw(url, headers, body)
    }.toEither
      .left
      .map(t => CloudSpeechError.fromThrowable(t, url): LLMError)
      .flatMap { (raw: HttpRawResponse) =>
        if (raw.statusCode >= 200 && raw.statusCode < 300) {
          Right(
            GeneratedAudio(
              data = raw.body,
              meta = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 16),
              format = AudioFormat.WavPcm16
            )
          )
        } else {
          Left(CloudSpeechError.fromHttpStatus(raw.statusCode, name, new String(raw.body, "UTF-8")))
        }
      }
  }
}

object ElevenLabsTTSClient {

  val DEFAULT_BASE_URL: String = "https://api.elevenlabs.io/v1"
  val DEFAULT_VOICE_ID: String = "21m00Tcm4TlvDq8ikWAM" // ElevenLabs default "Rachel" voice

  def apply(
    apiKey: String,
    voiceId: String = DEFAULT_VOICE_ID,
    baseUrl: String = DEFAULT_BASE_URL
  ): ElevenLabsTTSClient =
    new ElevenLabsTTSClient(apiKey, voiceId, baseUrl, Llm4sHttpClient.create())

  private[speech] def forTest(
    apiKey: String,
    http: Llm4sHttpClient,
    voiceId: String = DEFAULT_VOICE_ID,
    baseUrl: String = DEFAULT_BASE_URL
  ): ElevenLabsTTSClient =
    new ElevenLabsTTSClient(apiKey, voiceId, baseUrl, http)
}
