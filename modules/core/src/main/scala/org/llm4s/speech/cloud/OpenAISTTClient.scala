package org.llm4s.speech.cloud

import org.llm4s.error.LLMError
import org.llm4s.http.{ Llm4sHttpClient, MultipartPart }
import org.llm4s.speech.AudioInput
import org.llm4s.speech.stt.{ STTOptions, SpeechToText, Transcription }
import org.llm4s.types.Result

import java.nio.file.{ Files, Path }
import scala.util.Try

/**
 * Cloud STT client for OpenAI Whisper transcription API.
 *
 * Transcribes audio via `POST /v1/audio/transcriptions` and returns the
 * recognized text.
 *
 * @param apiKey  OpenAI API key (Bearer token)
 * @param model   Whisper model, e.g. "whisper-1"
 * @param baseUrl API base URL, defaults to OpenAI production endpoint
 * @param http    HTTP client (injected for testing)
 */
final class OpenAISTTClient private (
  apiKey: String,
  model: String,
  baseUrl: String,
  http: Llm4sHttpClient
) extends SpeechToText {

  override val name: String = "openai-whisper"

  override val supportedFormats: List[String] =
    List("audio/wav", "audio/mp3", "audio/mp4", "audio/mpeg", "audio/ogg", "audio/webm", "audio/flac")

  override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] = {
    val url = s"$baseUrl/audio/transcriptions"
    val headers = Map(
      "Authorization" -> s"Bearer $apiKey"
    )

    val result = input match {
      case AudioInput.FileAudio(path) =>
        transcribeFile(path, url, headers, options)
      case AudioInput.BytesAudio(bytes, _, _) =>
        withTempFile(bytes, "audio.wav") { tmp =>
          transcribeFile(tmp, url, headers, options)
        }
      case AudioInput.StreamAudio(stream, _, _) =>
        val bytes = Try(stream.readAllBytes()).toEither.left
          .map(t => CloudSpeechError.fromThrowable(t, url): LLMError)
        bytes.flatMap { bs =>
          withTempFile(bs, "audio.wav") { tmp =>
            transcribeFile(tmp, url, headers, options)
          }
        }
    }

    result
  }

  private def transcribeFile(
    path: Path,
    url: String,
    headers: Map[String, String],
    options: STTOptions
  ): Result[Transcription] = {
    val parts = Seq(
      MultipartPart.FilePart("file", path, path.getFileName.toString),
      MultipartPart.TextField("model", model),
      MultipartPart.TextField("response_format", "json")
    ) ++ options.language.map(l => MultipartPart.TextField("language", l)).toSeq

    Try {
      http.postMultipart(url, headers, parts)
    }.toEither
      .left
      .map(t => CloudSpeechError.fromThrowable(t, url): LLMError)
      .flatMap { response =>
        if (response.statusCode >= 200 && response.statusCode < 300) {
          parseTranscription(response.body, options)
        } else {
          Left(CloudSpeechError.fromHttpStatus(response.statusCode, name, response.body))
        }
      }
  }

  private def withTempFile[A](bytes: Array[Byte], filename: String)(f: Path => Result[A]): Result[A] = {
    val tmpResult = Try {
      val tmp = Files.createTempFile("llm4s-stt-", s"-$filename")
      Files.write(tmp, bytes)
      tmp
    }.toEither.left.map(t => CloudSpeechError.fromThrowable(t, "tmp-file"): LLMError)

    tmpResult.flatMap { tmp =>
      val result = f(tmp)
      Try(Files.deleteIfExists(tmp))
      result
    }
  }

  private def parseTranscription(body: String, options: STTOptions): Result[Transcription] = {
    Try {
      val json = ujson.read(body)
      val text = json("text").str
      Transcription(
        text = text,
        language = options.language
      )
    }.toEither.left.map { t =>
      CloudSpeechError.fromThrowable(t, s"parse-response"): LLMError
    }
  }
}

object OpenAISTTClient {

  val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
  val DEFAULT_MODEL: String    = "whisper-1"

  def apply(
    apiKey: String,
    model: String = DEFAULT_MODEL,
    baseUrl: String = DEFAULT_BASE_URL
  ): OpenAISTTClient =
    new OpenAISTTClient(apiKey, model, baseUrl, Llm4sHttpClient.create())

  private[speech] def forTest(
    apiKey: String,
    http: Llm4sHttpClient,
    model: String = DEFAULT_MODEL,
    baseUrl: String = DEFAULT_BASE_URL
  ): OpenAISTTClient =
    new OpenAISTTClient(apiKey, model, baseUrl, http)
}
