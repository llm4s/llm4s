package org.llm4s.speech.stt.provider

import org.llm4s.error.ValidationError
import org.llm4s.http.{ Llm4sHttpClient, MultipartPart }
import org.llm4s.speech.{ AudioInput, CloudSpeechSupport }
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.stt.{ STTError, STTOptions, SpeechToText, Transcription, WordTimestamp }
import org.llm4s.types.{ Result, TryOps }

import java.nio.file.{ Files, Path }
import scala.concurrent.duration._
import scala.util.Try

/**
 * OpenAI speech-to-text (`POST /v1/audio/transcriptions`, the Whisper API).
 *
 * `FileAudio` is uploaded as it is; `BytesAudio` and `StreamAudio` are WAV data, as for the other
 * STT engines, and are staged in a temporary file that is deleted afterwards.
 *
 * `options.language` is sent as the ISO 639-1 prefix of the tag (`en-US` becomes `en`),
 * `options.prompt` as `prompt`, and `options.enableTimestamps` switches the request to
 * `verbose_json` with word granularity.
 *
 * @param config     configuration from [[org.llm4s.speech.config.SpeechConfigLoader.stt]]
 * @param httpClient HTTP transport, replaceable in tests
 */
final class OpenAISTTClient(config: STTConfig, httpClient: Llm4sHttpClient = Llm4sHttpClient.create())
    extends SpeechToText {

  override val name: String = "openai-stt"

  override val supportedFormats: List[String] =
    List("audio/wav", "audio/mpeg", "audio/mp4", "audio/m4a", "audio/ogg", "audio/webm", "audio/flac")

  override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] =
    OpenAISTTClient.requireTimestampModel(config.model, options).flatMap(_ => transcribeChecked(input, options))

  private def transcribeChecked(input: AudioInput, options: STTOptions): Result[Transcription] =
    input match {
      case AudioInput.FileAudio(path) => upload(path, options)
      case other =>
        CloudSpeechSupport.readAudio(other).flatMap { bytes =>
          WavFileGenerator.managedTempWavFile("llm4s-openai-stt-").use { tmp =>
            Try(Files.write(tmp, bytes)).toResult.flatMap(_ => upload(tmp, options))
          }
        }
    }

  private def upload(path: Path, options: STTOptions): Result[Transcription] = {
    val fields: Seq[MultipartPart] =
      Seq(
        MultipartPart.FilePart("file", path, path.getFileName.toString),
        MultipartPart.TextField("model", config.model)
      ) ++
        options.language.map(l => MultipartPart.TextField("language", CloudSpeechSupport.primaryLanguage(l))) ++
        options.prompt.map(p => MultipartPart.TextField("prompt", p)) ++
        (if (options.enableTimestamps)
           Seq(
             MultipartPart.TextField("response_format", "verbose_json"),
             MultipartPart.TextField("timestamp_granularities[]", "word")
           )
         else Seq.empty)

    for {
      response <- httpClient.postMultipart(
        s"${config.baseUrl}/v1/audio/transcriptions",
        Map("Authorization" -> s"Bearer ${config.apiKey}"),
        fields,
        120.seconds
      )
      body          <- CloudSpeechSupport.textBody(name, response, config.apiKey)
      transcription <- OpenAISTTClient.parse(body, options)
    } yield transcription
  }
}

object OpenAISTTClient {

  /** OpenAI: "the `timestamp_granularities[]` parameter is only supported for `whisper-1`". */
  private[provider] val TimestampModel = "whisper-1"

  private[provider] def requireTimestampModel(model: String, options: STTOptions): Result[Unit] =
    if (options.enableTimestamps && model != TimestampModel)
      Left(
        ValidationError(
          "enableTimestamps",
          s"word timestamps are only supported by OpenAI's $TimestampModel model, not '$model'"
        )
      )
    else Right(())

  private[provider] def parse(body: String, options: STTOptions): Result[Transcription] =
    Try {
      val json = ujson.read(body)
      val text = json("text").str.trim
      val words = json.obj
        .get("words")
        .map(_.arr.toList.flatMap { w =>
          for {
            word  <- w.obj.get("word").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
            start <- w.obj.get("start").flatMap(_.numOpt)
            end   <- w.obj.get("end").flatMap(_.numOpt)
            if start >= 0 && end >= start
          } yield WordTimestamp(word, start, end)
        })
        .getOrElse(Nil)
      (text, words, json.obj.get("language").flatMap(_.strOpt))
    }.toResult.left
      .map(e => STTError.ProcessingFailed(s"Failed to parse OpenAI STT response: ${e.message}"))
      .flatMap { case (text, words, detected) =>
        if (text.isEmpty) Left(STTError.ProcessingFailed("OpenAI STT returned an empty transcription"))
        else Right(Transcription(text, options.language.orElse(detected), timestamps = words))
      }
}
