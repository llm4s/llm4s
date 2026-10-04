package org.llm4s.speech

import org.llm4s.error.ValidationError
import org.llm4s.http.{ HttpRawResponse, HttpResponse }
import org.llm4s.llmconnect.provider.HttpErrorMapper
import org.llm4s.types.{ Result, TryOps }

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.Try

/**
 * Behaviour the cloud TTS and STT clients share: HTTP status mapping and reading audio input.
 *
 * Status codes map through [[org.llm4s.llmconnect.provider.HttpErrorMapper]], the same as the chat
 * providers: 401/403 are an `AuthenticationError`, 429 a `RateLimitError` carrying `Retry-After`,
 * 400 a `ValidationError` and anything else a `ServiceError`. Transport failures (timeout,
 * connection refused) arrive from the HTTP client as `NetworkError` / `TimeoutError` and are
 * passed through untouched.
 */
private[speech] object CloudSpeechSupport {

  private def success(status: Int): Boolean = status >= 200 && status < 300

  /** The body of a 2xx response, or the mapped error. */
  def textBody(provider: String, response: HttpResponse): Result[String] =
    if (success(response.statusCode)) Right(response.body)
    else HttpErrorMapper.mapHttpError(response.statusCode, response.body, provider, response.headers)

  /** The bytes of a 2xx response, or the mapped error. */
  def rawBody(provider: String, response: HttpRawResponse): Result[Array[Byte]] =
    if (success(response.statusCode)) Right(response.body)
    else
      HttpErrorMapper.mapHttpError(
        response.statusCode,
        new String(response.body, StandardCharsets.UTF_8),
        provider,
        response.headers
      )

  def requireText(text: String): Result[String] =
    if (text.trim.isEmpty) Left(ValidationError("text", "must not be empty")) else Right(text)

  /** The audio of `input` as bytes. `BytesAudio` and `StreamAudio` are WAV data, as for the other STT engines. */
  def readAudio(input: AudioInput): Result[Array[Byte]] =
    input match {
      case AudioInput.FileAudio(path)           => Try(Files.readAllBytes(path)).toResult
      case AudioInput.BytesAudio(bytes, _, _)   => Right(bytes)
      case AudioInput.StreamAudio(stream, _, _) => Try(stream.readAllBytes()).toResult
    }

  /** The sample rate in a WAV header, when `bytes` starts with one. */
  def wavSampleRate(bytes: Array[Byte]): Option[Int] =
    if (bytes.length >= 28 && new String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF") {
      Some(
        (bytes(24) & 0xff) | ((bytes(25) & 0xff) << 8) | ((bytes(26) & 0xff) << 16) | ((bytes(27) & 0xff) << 24)
      )
    } else None

  /** The ISO 639-1 language of a BCP 47 tag: `en-US` is `en`. */
  def primaryLanguage(tag: String): String = tag.takeWhile(_ != '-').toLowerCase(java.util.Locale.ROOT)
}
