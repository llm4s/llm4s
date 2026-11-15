package org.llm4s.sttp

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, NetworkError, TimeoutError, UnknownError, ValidationError }
import org.llm4s.http.{
  HttpFailures,
  HttpRawResponse,
  HttpResponse,
  Llm4sHttpClient,
  MultipartPart,
  StreamingHttpResponse
}
import org.llm4s.types.Result
import sttp.client4.*
import sttp.model.{ Header, Method, Part, Uri }

import java.io.InputStream
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.util.Locale
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * An [[org.llm4s.http.Llm4sHttpClient]] over an sttp 4 synchronous backend, so the provider clients send their
 * requests through the HTTP stack a service already has: its connection pool, proxy and TLS settings, logging and
 * tracing middleware, or a stub backend in tests. Pass it with `LlmClientOptions.withHttpClient`.
 *
 * It behaves as `Llm4sHttpClient.create()` does: a non-2xx status is a `Right` response, response headers are keyed
 * in lower case, `postStream` hands back the open body (`asInputStreamAlwaysUnsafe`) for the caller to close, and a
 * transport failure is the same `Left` - a `TimeoutError` for a timeout, a `NetworkError` for a refused connection or
 * an I/O failure, a `ValidationError` for an invalid URL or an unreadable multipart file, a `CancelledError` for an
 * interrupt. Each request's `timeout` is its sttp `readTimeout`.
 *
 * The caller owns `backend`: closing this client does not close it.
 *
 * @param backend any sttp 4 synchronous backend, such as `DefaultSyncBackend()` wrapped in your middleware, or a
 *                `SyncBackendStub`
 */
@Experimental
final class SttpLlm4sHttpClient(backend: SyncBackend) extends Llm4sHttpClient {

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    send(Method.GET, url, timeout, request(headers, timeout), _.addParams(params))(textResponse)

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    send(Method.POST, url, timeout, request(headers, timeout).body(body))(textResponse)

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    send(Method.POST, url, timeout, request(headers, timeout).body(data))(textResponse)

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    // Files are read up front, as the default client does, so an unreadable one is a ValidationError, not an I/O error.
    multipartParts(parts).flatMap(sttpParts =>
      send(Method.POST, url, timeout, request(headers, timeout).multipartBody(sttpParts))(textResponse)
    )

  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    send(Method.PUT, url, timeout, request(headers, timeout).body(body))(textResponse)

  override def delete(
    url: String,
    headers: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    send(Method.DELETE, url, timeout, request(headers, timeout))(textResponse)

  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpRawResponse] =
    send(Method.POST, url, timeout, request(headers, timeout).body(body).response(asByteArrayAlways)) { response =>
      HttpRawResponse(response.code.code, response.body, headerMap(response.headers))
    }

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[StreamingHttpResponse] =
    send(Method.POST, url, timeout, request(headers, timeout).body(body).response(asInputStreamAlwaysUnsafe)) {
      (response: Response[InputStream]) =>
        StreamingHttpResponse(response.code.code, response.body, headerMap(response.headers))
    }

  // ============================================================
  // Internal helpers
  // ============================================================

  private def request(headers: Map[String, String], timeout: FiniteDuration): PartialRequest[String] =
    basicRequest
      .headers(headers)
      .readTimeout(timeout)
      .response(asStringAlways)

  private def textResponse(response: Response[String]): HttpResponse =
    HttpResponse(response.code.code, response.body, headerMap(response.headers))

  /**
   * Parses `url`, builds the request for `method` and sends it, mapping every failure - including one sttp wraps in
   * an `SttpClientException` - as the default client does.
   */
  private def send[T, A](
    method: Method,
    url: String,
    timeout: FiniteDuration,
    partial: PartialRequest[T],
    adjustUri: Uri => Uri = identity
  )(convert: Response[T] => A): Result[A] =
    parseUrl(method, url).flatMap { uri =>
      HttpFailures
        .attempt(method.method, url, timeout)(convert(partial.method(method, adjustUri(uri)).send(backend)))
        .left
        .map(unwrapSttp(_, method.method, url, timeout))
    }

  /** An absolute `http`/`https` URL, else the `ValidationError` the default client gives for an invalid one. */
  private def parseUrl(method: Method, url: String): Result[Uri] =
    Uri
      .parse(url)
      .toOption
      .filter(uri => uri.scheme.exists(s => s == "http" || s == "https") && uri.host.exists(_.nonEmpty))
      .toRight(
        ValidationError("request", s"Invalid ${method.method} request to ${HttpFailures.safeEndpoint(url)}: bad URL")
      )

  /**
   * The error for a failure sttp wrapped: its cause mapped as the default client maps it, so a refused connection is
   * still a `NetworkError` and an `HttpTimeoutException` still a `TimeoutError`.
   */
  private def unwrapSttp(error: LLMError, method: String, url: String, timeout: FiniteDuration): LLMError =
    error match {
      case UnknownError(_, e: SttpClientException) =>
        val endpoint = HttpFailures.safeEndpoint(url)
        (e, Option(e.cause)) match {
          case (_: SttpClientException.TimeoutException, Some(cause))
              if cause.isInstanceOf[HttpTimeoutException] || cause.isInstanceOf[java.net.SocketTimeoutException] =>
            HttpFailures.toLLMError(cause, method, url, timeout)
          case (_: SttpClientException.TimeoutException, _) =>
            TimeoutError(s"$method $endpoint: request timed out after $timeout", timeout, s"http.$method", Some(e))
              .withContext("endpoint", endpoint)
          case (_, Some(cause)) =>
            HttpFailures.toLLMError(cause, method, url, timeout)
          case (_, None) =>
            NetworkError(s"$method $endpoint: ${e.getClass.getSimpleName}", Some(e), endpoint)
        }
      case other => other
    }

  private def multipartParts(parts: Seq[MultipartPart]): Result[Seq[Part[BasicBodyPart]]] =
    parts.foldLeft[Result[Vector[Part[BasicBodyPart]]]](Right(Vector.empty)) { (acc, part) =>
      acc.flatMap { built =>
        val next: Result[Part[BasicBodyPart]] = part match {
          case MultipartPart.TextField(name, value) => Right(multipart(name, value))
          case MultipartPart.FilePart(name, path, filename) =>
            Try(Files.readAllBytes(path)).toEither.left
              .map(e => ValidationError("parts", s"Failed to read multipart body: ${e.getMessage}"))
              .map(bytes => multipart(name, bytes).fileName(filename).contentType("application/octet-stream"))
        }
        next.map(built :+ _)
      }
    }

  private def headerMap(headers: Seq[Header]): Map[String, Seq[String]] =
    headers.groupBy(_.name.toLowerCase(Locale.ROOT)).view.mapValues(_.map(_.value)).toMap
}
