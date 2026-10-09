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

  /**
   * An error body with `secret` removed. `HttpErrorMapper` redacts well-known key shapes, but not
   * every provider's (an Azure subscription key is 32 hex digits), so the configured key itself is
   * scrubbed before the body can reach an error message.
   */
  private def scrub(body: String, secret: String): String =
    if (secret.isEmpty) body else body.replace(secret, "[REDACTED]")

  /** The body of a 2xx response, or the mapped error (with `secret`, the API key, scrubbed from it). */
  def textBody(provider: String, response: HttpResponse, secret: String): Result[String] =
    if (success(response.statusCode)) Right(response.body)
    else HttpErrorMapper.mapHttpError(response.statusCode, scrub(response.body, secret), provider, response.headers)

  /** The bytes of a 2xx response, or the mapped error (with `secret`, the API key, scrubbed from it). */
  def rawBody(provider: String, response: HttpRawResponse, secret: String): Result[Array[Byte]] =
    if (success(response.statusCode)) Right(response.body)
    else
      HttpErrorMapper.mapHttpError(
        response.statusCode,
        scrub(new String(response.body, StandardCharsets.UTF_8), secret),
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

  private def ascii(bytes: Array[Byte], offset: Int, length: Int): String =
    new String(bytes, offset, length, StandardCharsets.US_ASCII)

  private def le32(bytes: Array[Byte], at: Int): Long =
    (bytes(at) & 0xffL) | ((bytes(at + 1) & 0xffL) << 8) | ((bytes(at + 2) & 0xffL) << 16) | ((bytes(
      at + 3
    ) & 0xffL) << 24)

  /**
   * The sample rate in the `fmt ` chunk of a RIFF/WAVE file, when `bytes` is one.
   *
   * The chunks are walked, so a `LIST` or `fact` chunk before `fmt ` (and the pad byte after an odd-sized
   * chunk) does not move the field being read. `None` for anything that is not a WAVE file with a
   * complete `fmt ` chunk and a positive rate.
   */
  def wavSampleRate(bytes: Array[Byte]): Option[Int] = {
    val FmtMinSize = 16
    @scala.annotation.tailrec
    def fromChunk(offset: Long): Option[Int] =
      if (offset + 8 > bytes.length) None
      else {
        val at   = offset.toInt
        val size = le32(bytes, at + 4)
        if (ascii(bytes, at, 4) == "fmt ") {
          if (size >= FmtMinSize && offset + 8 + FmtMinSize <= bytes.length)
            Some(le32(bytes, at + 12)).filter(r => r > 0 && r <= Int.MaxValue).map(_.toInt)
          else None
        } else fromChunk(offset + 8 + size + (size & 1L))
      }
    if (bytes.length >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") fromChunk(12L)
    else None
  }

  /** The ISO 639-1 language of a BCP 47 (`en-US`) or POSIX (`en_US`) tag: both are `en`. */
  def primaryLanguage(tag: String): String =
    tag.takeWhile(c => c != '-' && c != '_').toLowerCase(java.util.Locale.ROOT)
}
