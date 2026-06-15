// scalafix:off DisableSyntax.NoKeywordCatch
package org.llm4s.speech.stt.provider

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.AudioInput
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.stt.{ STTError, STTOptions, SpeechToText, Transcription }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.nio.file.Files
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Azure Speech-to-Text REST API client.
 *
 * Calls the Azure Speech-to-Text REST API with audio bytes.
 * Returns transcription result.
 *
 * Auth: AZURE_SPEECH_KEY, AZURE_SPEECH_REGION
 */
object AzureSTTClient {

  /**
   * Creates an AzureSTTClient backed by the real JDK HTTP client.
   */
  def fromConfig(cfg: STTConfig): SpeechToText =
    create(cfg, Llm4sHttpClient.create())

  private[provider] def forTest(cfg: STTConfig, httpClient: Llm4sHttpClient): SpeechToText =
    create(cfg, httpClient)

  private def create(cfg: STTConfig, httpClient: Llm4sHttpClient): SpeechToText =
    new SpeechToText {
      private val logger = LoggerFactory.getLogger(getClass)

      override val name: String = "azure-stt"

      override val supportedFormats: List[String] =
        List("audio/wav", "audio/ogg; codecs=opus", "audio/mp3")

      override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] = {
        val region = cfg.region.getOrElse("eastus")
        val url = cfg.baseUrl match {
          case u if u.nonEmpty && u != "default" => s"$u/speech/recognition/conversation/cognitiveservices/v1"
          case _ =>
            s"https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
        }

        val language = options.language.getOrElse("en-US")
        val fullUrl  = s"$url?language=$language"

        logger.debug(s"[AzureSTTClient] POST $fullUrl region=$region language=$language")

        val headers = Map(
          "Ocp-Apim-Subscription-Key" -> cfg.apiKey,
          "Content-Type"              -> "audio/wav; codecs=audio/pcm; samplerate=16000",
          "Accept"                    -> "application/json"
        )

        readAudioBytes(input).flatMap { audioBytes =>
          try {
            val response = httpClient.postBytes(fullUrl, headers, audioBytes, timeout = 120000)
            response.statusCode match {
              case 200 =>
                parseAzureResponse(response.body, options)
              case 401 =>
                Left(STTError.EngineNotAvailable(s"Azure STT authentication failed: ${response.body}"))
              case 400 =>
                Left(STTError.InvalidInput(s"Azure STT invalid request: ${response.body}"))
              case status =>
                Left(STTError.ProcessingFailed(s"Azure STT API returned HTTP $status: ${response.body}"))
            }
          } catch {
            case NonFatal(e) =>
              logger.error(s"[AzureSTTClient] Request failed: ${e.getMessage}")
              Left(STTError.ProcessingFailed(s"Azure STT request failed: ${e.getMessage}", Some(e)))
          }
        }
      }

      private def readAudioBytes(input: AudioInput): Result[Array[Byte]] =
        input match {
          case AudioInput.FileAudio(path) =>
            Try(Files.readAllBytes(path)).fold(
              err => Left(STTError.ProcessingFailed(s"Failed to read audio file: ${err.getMessage}", Some(err))),
              bytes => Right(bytes)
            )
          case AudioInput.BytesAudio(bytes, _, _) =>
            Right(bytes)
          case AudioInput.StreamAudio(stream, _, _) =>
            Try(stream.readAllBytes()).fold(
              err => Left(STTError.ProcessingFailed(s"Failed to read audio stream: ${err.getMessage}", Some(err))),
              bytes => Right(bytes)
            )
        }

      private def parseAzureResponse(body: String, options: STTOptions): Result[Transcription] =
        Try {
          val json              = ujson.read(body)
          val recognitionStatus = json.obj.get("RecognitionStatus").flatMap(_.strOpt).getOrElse("Unknown")

          recognitionStatus match {
            case "Success" =>
              val text = json.obj.get("DisplayText").flatMap(_.strOpt).getOrElse("").trim
              if (text.isEmpty) {
                Left(STTError.ProcessingFailed("Azure STT returned empty transcription"))
              } else {
                Right(
                  Transcription(
                    text = text,
                    language = options.language,
                    confidence = None,
                    timestamps = Nil,
                    meta = None
                  )
                )
              }
            case "NoMatch" =>
              Left(STTError.ProcessingFailed("Azure STT: No speech could be recognized in the audio"))
            case "InitialSilenceTimeout" =>
              Left(STTError.ProcessingFailed("Azure STT: Input audio starts with silence, exceeding timeout"))
            case other =>
              Left(STTError.ProcessingFailed(s"Azure STT recognition failed with status: $other"))
          }
        }.fold(
          err => Left(STTError.ProcessingFailed(s"Failed to parse Azure STT response: ${err.getMessage}", Some(err))),
          result => result
        )
    }
}
