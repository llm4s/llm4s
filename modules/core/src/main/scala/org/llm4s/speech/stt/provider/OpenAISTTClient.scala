// scalafix:off DisableSyntax.NoKeywordCatch
package org.llm4s.speech.stt.provider

import org.llm4s.http.{ Llm4sHttpClient, MultipartPart }
import org.llm4s.speech.AudioInput
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.stt.{ STTError, STTOptions, SpeechToText, Transcription }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.nio.file.Files
import scala.util.Try
import scala.util.control.NonFatal

/**
 * OpenAI Speech-to-Text client (Whisper API).
 *
 * Calls POST /v1/audio/transcriptions with audio file bytes + model.
 * Returns transcribed text.
 *
 * Auth: reuses OPENAI_API_KEY
 * Config: SPEECH_STT_MODEL=openai/whisper-1
 */
object OpenAISTTClient {

  /**
   * Creates an OpenAISTTClient backed by the real JDK HTTP client.
   */
  def fromConfig(cfg: STTConfig): SpeechToText =
    create(cfg, Llm4sHttpClient.create())

  private[provider] def forTest(cfg: STTConfig, httpClient: Llm4sHttpClient): SpeechToText =
    create(cfg, httpClient)

  private def create(cfg: STTConfig, httpClient: Llm4sHttpClient): SpeechToText =
    new SpeechToText {
      private val logger = LoggerFactory.getLogger(getClass)

      override val name: String = "openai-stt"

      override val supportedFormats: List[String] =
        List("audio/wav", "audio/mp3", "audio/mp4", "audio/mpeg", "audio/mpga", "audio/m4a", "audio/ogg", "audio/webm")

      override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] = {
        val model = cfg.model
        val url   = s"${cfg.baseUrl}/v1/audio/transcriptions"

        logger.debug(s"[OpenAISTTClient] POST $url model=$model")

        val headers = Map(
          "Authorization" -> s"Bearer ${cfg.apiKey}"
        )

        val parts = buildMultipartParts(input, model, options)

        parts.flatMap { multipartParts =>
          try {
            val response = httpClient.postMultipart(url, headers, multipartParts, timeout = 120000)
            response.statusCode match {
              case 200 =>
                parseTranscriptionResponse(response.body, options)
              case 401 =>
                Left(STTError.EngineNotAvailable(s"OpenAI STT authentication failed: ${response.body}"))
              case 400 =>
                Left(STTError.InvalidInput(s"OpenAI STT invalid request: ${response.body}"))
              case status =>
                Left(STTError.ProcessingFailed(s"OpenAI STT API returned HTTP $status: ${response.body}"))
            }
          } catch {
            case NonFatal(e) =>
              logger.error(s"[OpenAISTTClient] Request failed: ${e.getMessage}")
              Left(STTError.ProcessingFailed(s"OpenAI STT request failed: ${e.getMessage}", Some(e)))
          }
        }
      }

      private def buildMultipartParts(
        input: AudioInput,
        model: String,
        options: STTOptions
      ): Result[Seq[MultipartPart]] = {
        val modelPart = MultipartPart.TextField("model", model)

        val languageParts = options.language
          .map(lang => MultipartPart.TextField("language", lang))
          .toSeq

        val promptParts = options.prompt
          .map(p => MultipartPart.TextField("prompt", p))
          .toSeq

        input match {
          case AudioInput.FileAudio(path) =>
            val filename = path.getFileName.toString
            val filePart = MultipartPart.FilePart("file", path, filename)
            Right(Seq(filePart, modelPart) ++ languageParts ++ promptParts)

          case AudioInput.BytesAudio(bytes, _, _) =>
            Try {
              val tmpPath = Files.createTempFile("llm4s-openai-stt-", ".wav")
              Files.write(tmpPath, bytes)
              tmpPath
            }.fold(
              err =>
                Left(
                  STTError.ProcessingFailed(s"Failed to write audio bytes to temp file: ${err.getMessage}", Some(err))
                ),
              tmpPath => {
                val filePart = MultipartPart.FilePart("file", tmpPath, "audio.wav")
                Right(Seq(filePart, modelPart) ++ languageParts ++ promptParts)
              }
            )

          case AudioInput.StreamAudio(stream, _, _) =>
            Try {
              val tmpPath = Files.createTempFile("llm4s-openai-stt-", ".wav")
              Files.write(tmpPath, stream.readAllBytes())
              tmpPath
            }.fold(
              err =>
                Left(STTError.ProcessingFailed(s"Failed to write stream to temp file: ${err.getMessage}", Some(err))),
              tmpPath => {
                val filePart = MultipartPart.FilePart("file", tmpPath, "audio.wav")
                Right(Seq(filePart, modelPart) ++ languageParts ++ promptParts)
              }
            )
        }
      }

      private def parseTranscriptionResponse(body: String, options: STTOptions): Result[Transcription] =
        Try {
          val json = ujson.read(body)
          val text = json("text").str.trim
          if (text.isEmpty) {
            Left(STTError.ProcessingFailed("OpenAI STT returned empty transcription"))
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
        }.fold(
          err => Left(STTError.ProcessingFailed(s"Failed to parse OpenAI STT response: ${err.getMessage}", Some(err))),
          result => result
        )
    }
}
