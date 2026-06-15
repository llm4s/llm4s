// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
package org.llm4s.speech.tts.provider

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.{ AudioMeta, GeneratedAudio }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.{ TTSError, TTSOptions, TextToSpeech }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/**
 * Azure Cognitive Services Text-to-Speech client.
 *
 * Calls the Azure Speech synthesis REST API.
 * Returns WAV/MP3 audio bytes.
 *
 * Auth: AZURE_SPEECH_KEY, AZURE_SPEECH_REGION
 */
object AzureTTSClient {

  /**
   * Creates an AzureTTSClient backed by the real JDK HTTP client.
   *
   * @param cfg speech TTS configuration; must have region set for Azure
   */
  def fromConfig(cfg: TTSConfig): TextToSpeech =
    create(cfg, Llm4sHttpClient.create())

  private[provider] def forTest(cfg: TTSConfig, httpClient: Llm4sHttpClient): TextToSpeech =
    create(cfg, httpClient)

  private def create(cfg: TTSConfig, httpClient: Llm4sHttpClient): TextToSpeech =
    new TextToSpeech {
      private val logger = LoggerFactory.getLogger(getClass)

      override val name: String = "azure-tts"

      override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] = {
        val region = cfg.region.getOrElse("eastus")
        val voice  = options.voice.getOrElse(cfg.voice)
        val url = cfg.baseUrl match {
          case u if u.nonEmpty && u != "default" => s"$u/cognitiveservices/v1"
          case _                                 => s"https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
        }

        val ssml =
          s"""<speak version='1.0' xml:lang='en-US'>
             |  <voice xml:lang='en-US' name='$voice'>
             |    ${escapeXml(text)}
             |  </voice>
             |</speak>""".stripMargin

        logger.debug(s"[AzureTTSClient] POST $url region=$region voice=$voice")

        val headers = Map(
          "Ocp-Apim-Subscription-Key" -> cfg.apiKey,
          "Content-Type"              -> "application/ssml+xml",
          "X-Microsoft-OutputFormat"  -> "audio-16khz-128kbitrate-mono-mp3"
        )

        try {
          val response = httpClient.post(url, headers, ssml, timeout = 60000)
          response.statusCode match {
            case 200 =>
              val audioBytes = response.body.getBytes("ISO-8859-1")
              Right(
                GeneratedAudio(
                  data = audioBytes,
                  meta = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16),
                  format = options.outputFormat
                )
              )
            case 401 =>
              Left(TTSError.EngineNotAvailable(s"Azure TTS authentication failed: ${response.body}"))
            case 400 =>
              Left(TTSError.SynthesisFailed(s"Azure TTS invalid request (SSML): ${response.body}"))
            case status =>
              Left(TTSError.SynthesisFailed(s"Azure TTS API returned HTTP $status: ${response.body}"))
          }
        } catch {
          case NonFatal(e) =>
            logger.error(s"[AzureTTSClient] Request failed: ${e.getMessage}")
            Left(TTSError.SynthesisFailed(s"Azure TTS request failed: ${e.getMessage}"))
        }
      }

      private def escapeXml(text: String): String =
        text
          .replace("&", "&amp;")
          .replace("<", "&lt;")
          .replace(">", "&gt;")
          .replace("\"", "&quot;")
          .replace("'", "&apos;")
    }
}
