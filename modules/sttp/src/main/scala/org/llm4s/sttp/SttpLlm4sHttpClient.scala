package org.llm4s.sttp

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, NetworkError, TimeoutError, UnknownError, ValidationError }
import org.llm4s.http.{
  HttpBody,
  HttpFailures,
  HttpRequest,
  Llm4sHttpClient,
  MultipartEncoding,
  StreamingHttpResponse
}
import org.llm4s.types.Result
import sttp.client4.*
import sttp.model.{ Header, Method, Part, Uri }

import java.io.InputStream
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import scala.concurrent.duration.FiniteDuration

/**
 * An [[org.llm4s.http.Llm4sHttpClient]] over an sttp 4 synchronous backend, so the provider clients send their
 * requests through the HTTP stack a service already has: its connection pool, proxy and TLS settings, logging and
 * tracing middleware, or a stub backend in tests. Pass it with `LlmClientOptions.withHttpClient`.
 *
 * It behaves as `Llm4sHttpClient.create()` does: a non-2xx status is a `Right` response, response headers are keyed
 * in lower case, `send` hands back the open body (`asInputStreamAlwaysUnsafe`) for the caller to close, and a
 * transport failure is the same `Left` - a `TimeoutError` for a timeout, a `NetworkError` for a refused connection or
 * an I/O failure, a `ValidationError` for an invalid URL or an unreadable multipart file, a `CancelledError` for an
 * interrupt.
 *
 * A request's `responseTimeout` is its sttp `readTimeout`, which is as much as the backend makes of it: the default
 * `HttpClientSyncBackend` waits that long for the response head. Its `idleTimeout` is enforced here, on the body, for
 * every backend (`StreamingHttpResponse.withIdleTimeout`). A multipart body is sent as sttp's own multipart body,
 * its field and file names escaped as the default client escapes them.
 *
 * The caller owns `backend`: closing this client does not close it.
 *
 * @param backend any sttp 4 synchronous backend, such as `DefaultSyncBackend()` wrapped in your middleware, or a
 *                `SyncBackendStub`
 */
@Experimental
final class SttpLlm4sHttpClient(backend: SyncBackend) extends Llm4sHttpClient {

  override def send(request: HttpRequest): Result[StreamingHttpResponse] = {
    val method = Method(request.method.name)
    val url    = request.fullUrl
    for {
      uri <- parseUrl(method, url)
      // Files are read and names escaped up front, as the default client does, so an unreadable file is a
      // ValidationError, not an I/O error.
      parts <- request.body match {
        case HttpBody.Multipart(parts) => MultipartEncoding.resolve(parts).map(Some(_))
        case _                         => Right(None)
      }
      response <- HttpFailures
        .attempt(method.method, url, request.responseTimeout) {
          val base = basicRequest
            .headers(request.headers)
            .readTimeout(request.responseTimeout)
            .response(asInputStreamAlwaysUnsafe)
          val withBody = request.body match {
            case HttpBody.Text(text)   => base.body(text)
            case HttpBody.Bytes(bytes) => base.body(bytes)
            case HttpBody.Empty        => base
            case _: HttpBody.Multipart => base.multipartBody(parts.getOrElse(Nil).map(sttpPart))
          }
          val sent: Response[InputStream] = withBody.method(method, uri).send(backend)
          StreamingHttpResponse(sent.code.code, sent.body, headerMap(sent.headers))
            .withIdleTimeout(request.idleTimeout)
        }
        .left
        .map(unwrapSttp(_, method.method, url, request.responseTimeout))
    } yield response
  }

  // ============================================================
  // Internal helpers
  // ============================================================

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

  private def sttpPart(part: MultipartEncoding.Resolved): Part[BasicBodyPart] =
    part.file match {
      case None => multipart(part.name, new String(part.bytes, UTF_8))
      case Some((filename, contentType)) =>
        multipart(part.name, part.bytes).fileName(filename).contentType(contentType)
    }

  private def headerMap(headers: Seq[Header]): Map[String, Seq[String]] =
    headers.groupBy(_.name.toLowerCase(Locale.ROOT)).view.mapValues(_.map(_.value)).toMap
}
