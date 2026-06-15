package org.llm4s.speech.cloud

import org.llm4s.error.LLMError
import org.llm4s.http.{ HttpRawResponse, Llm4sHttpClient }
import org.llm4s.speech.{ AudioFormat, AudioMeta, GeneratedAudio }
import org.llm4s.speech.tts.{ TTSOptions, TextToSpeech }
import org.llm4s.types.Result

import scala.util.Try

/**
 * Cloud TTS client for OpenAI text-to-speech API.
 *
 * Synthesizes audio via `POST /v1/audio/speech` and returns raw MP3 bytes.
 *
 * @param apiKey   OpenAI API key (Bearer token)
 * @param model    TTS model, e.g. "tts-1" or "tts-1-hd"
 * @param baseUrl  API base URL, defaults to OpenAI production endpoint
 * @param http     HTTP client (injected for testing)
 */
final class OpenAITTSClient private (
  apiKey: String,
  model: String,
  baseUrl: String,
  http: Llm4sHttpClient
) extends TextToSpeech {

  override val name: String = "openai-tts"

  override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] = {
    val voice = options.voice.getOrElse("alloy")
    val body =
      s"""{"model":"$model","input":${ujson.write(text)},"voice":"$voice","response_format":"mp3"}"""

    val headers = Map(
      "Authorization" -> s"Bearer $apiKey",
      "Content-Type"  -> "application/json"
    )

    Try {
      http.postRaw(s"$baseUrl/audio/speech", headers, body)
    }.toEither
      .left
      .map(t => CloudSpeechError.fromThrowable(t, s"$baseUrl/audio/speech"): LLMError)
      .flatMap { (raw: HttpRawResponse) =>
        if (raw.statusCode >= 200 && raw.statusCode < 300) {
          Right(
            GeneratedAudio(
              data = raw.body,
              meta = AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 16),
              format = AudioFormat.WavPcm16
            )
          )
        } else {
          Left(CloudSpeechError.fromHttpStatus(raw.statusCode, name, new String(raw.body, "UTF-8")))
        }
      }
  }
}

object OpenAITTSClient {

  val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
  val DEFAULT_MODEL: String    = "tts-1"

  def apply(
    apiKey: String,
    model: String = DEFAULT_MODEL,
    baseUrl: String = DEFAULT_BASE_URL
  ): OpenAITTSClient =
    new OpenAITTSClient(apiKey, model, baseUrl, Llm4sHttpClient.create())

  private[speech] def forTest(
    apiKey: String,
    http: Llm4sHttpClient,
    model: String = DEFAULT_MODEL,
    baseUrl: String = DEFAULT_BASE_URL
  ): OpenAITTSClient =
    new OpenAITTSClient(apiKey, model, baseUrl, http)
}
