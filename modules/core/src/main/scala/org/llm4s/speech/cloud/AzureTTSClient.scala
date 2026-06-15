package org.llm4s.speech.cloud

import org.llm4s.error.LLMError
import org.llm4s.http.{ HttpRawResponse, Llm4sHttpClient }
import org.llm4s.speech.{ AudioFormat, AudioMeta, GeneratedAudio }
import org.llm4s.speech.tts.{ TTSOptions, TextToSpeech }
import org.llm4s.types.Result

import scala.util.Try

/**
 * Cloud TTS client for Azure Cognitive Services Speech API.
 *
 * Synthesizes audio via SSML POST to the Azure TTS endpoint.
 *
 * @param subscriptionKey Azure Speech subscription key
 * @param region          Azure region, e.g. "eastus"
 * @param voiceName       SSML voice name, e.g. "en-US-JennyNeural"
 * @param baseUrl         Override endpoint URL (for testing or sovereign clouds)
 * @param http            HTTP client (injected for testing)
 */
final class AzureTTSClient private (
  subscriptionKey: String,
  region: String,
  voiceName: String,
  baseUrl: String,
  http: Llm4sHttpClient
) extends TextToSpeech {

  override val name: String = "azure-tts"

  override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] = {
    val voice = options.voice.getOrElse(voiceName)
    val ssml =
      s"""<speak version='1.0' xml:lang='en-US'>
         |<voice name='$voice'>$text</voice>
         |</speak>""".stripMargin

    val headers = Map(
      "Ocp-Apim-Subscription-Key" -> subscriptionKey,
      "Content-Type"              -> "application/ssml+xml",
      "X-Microsoft-OutputFormat"  -> "audio-16khz-128kbitrate-mono-mp3"
    )

    Try {
      http.postRaw(baseUrl, headers, ssml)
    }.toEither
      .left
      .map(t => CloudSpeechError.fromThrowable(t, baseUrl): LLMError)
      .flatMap { (raw: HttpRawResponse) =>
        if (raw.statusCode >= 200 && raw.statusCode < 300) {
          Right(
            GeneratedAudio(
              data = raw.body,
              meta = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16),
              format = AudioFormat.WavPcm16
            )
          )
        } else {
          Left(CloudSpeechError.fromHttpStatus(raw.statusCode, name, new String(raw.body, "UTF-8")))
        }
      }
  }
}

object AzureTTSClient {

  val DEFAULT_VOICE_NAME: String = "en-US-JennyNeural"

  def ttsUrl(region: String): String =
    s"https://$region.tts.speech.microsoft.com/cognitiveservices/v1"

  def apply(
    subscriptionKey: String,
    region: String,
    voiceName: String = DEFAULT_VOICE_NAME
  ): AzureTTSClient =
    new AzureTTSClient(subscriptionKey, region, voiceName, ttsUrl(region), Llm4sHttpClient.create())

  private[speech] def forTest(
    subscriptionKey: String,
    region: String,
    http: Llm4sHttpClient,
    voiceName: String = DEFAULT_VOICE_NAME,
    baseUrl: String = "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1"
  ): AzureTTSClient =
    new AzureTTSClient(subscriptionKey, region, voiceName, baseUrl, http)
}
