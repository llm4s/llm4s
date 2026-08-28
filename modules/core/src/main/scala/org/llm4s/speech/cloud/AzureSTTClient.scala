package org.llm4s.speech.cloud

import org.llm4s.error.LLMError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.AudioInput
import org.llm4s.speech.stt.{ STTOptions, SpeechToText, Transcription }
import org.llm4s.types.Result

import java.nio.file.Files
import scala.util.Try

/**
 * Cloud STT client for Azure Cognitive Services Speech-to-Text API.
 *
 * Transcribes audio via `POST /speech/recognition/conversation/cognitiveservices/v1`.
 *
 * @param subscriptionKey Azure Speech subscription key
 * @param baseUrl         Override endpoint URL (for testing or sovereign clouds)
 * @param http            HTTP client (injected for testing)
 */
final class AzureSTTClient private (
  subscriptionKey: String,
  baseUrl: String,
  http: Llm4sHttpClient
) extends SpeechToText {

  override val name: String = "azure-stt"

  override val supportedFormats: List[String] =
    List("audio/wav", "audio/ogg", "audio/mp3")

  override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] = {
    val lang = options.language.getOrElse("en-US")
    val url  = s"$baseUrl?language=$lang&format=simple"
    val headers = Map(
      "Ocp-Apim-Subscription-Key" -> subscriptionKey,
      "Content-Type"              -> "audio/wav; codecs=audio/pcm; samplerate=16000",
      "Accept"                    -> "application/json"
    )

    input match {
      case AudioInput.FileAudio(path) =>
        transcribeBytes(Files.readAllBytes(path), url, headers, options)
      case AudioInput.BytesAudio(bytes, _, _) =>
        transcribeBytes(bytes, url, headers, options)
      case AudioInput.StreamAudio(stream, _, _) =>
        val bytesResult = Try(stream.readAllBytes()).toEither.left
          .map(t => CloudSpeechError.fromThrowable(t, url): LLMError)
        bytesResult.flatMap(bytes => transcribeBytes(bytes, url, headers, options))
    }
  }

  private def transcribeBytes(
    bytes: Array[Byte],
    url: String,
    headers: Map[String, String],
    options: STTOptions
  ): Result[Transcription] =
    Try {
      http.postBytes(url, headers, bytes)
    }.toEither.left
      .map(t => CloudSpeechError.fromThrowable(t, url): LLMError)
      .flatMap { response =>
        if (response.statusCode >= 200 && response.statusCode < 300) {
          parseTranscription(response.body, options)
        } else {
          Left(CloudSpeechError.fromHttpStatus(response.statusCode, name, response.body))
        }
      }

  private def parseTranscription(body: String, options: STTOptions): Result[Transcription] =
    Try {
      val json = ujson.read(body)
      val text = json("DisplayText").str
      Transcription(
        text = text,
        language = options.language
      )
    }.toEither.left.map(t => CloudSpeechError.fromThrowable(t, "parse-response"): LLMError)
}

object AzureSTTClient {

  def sttUrl(region: String): String =
    s"https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"

  def apply(
    subscriptionKey: String,
    region: String
  ): AzureSTTClient =
    new AzureSTTClient(subscriptionKey, sttUrl(region), Llm4sHttpClient.create())

  private[speech] def forTest(
    subscriptionKey: String,
    http: Llm4sHttpClient,
    baseUrl: String = "https://eastus.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
  ): AzureSTTClient =
    new AzureSTTClient(subscriptionKey, baseUrl, http)
}
