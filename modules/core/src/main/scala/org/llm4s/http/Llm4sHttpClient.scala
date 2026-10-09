package org.llm4s.http

import org.llm4s.annotation.Stable
import org.llm4s.error.{
  CancelledError,
  LLMError,
  NetworkError,
  ServiceError,
  TimeoutError,
  UnknownError,
  ValidationError
}
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.util.Redaction

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.{
  HttpClient => JHttpClient,
  HttpConnectTimeoutException,
  HttpHeaders => JHttpHeaders,
  HttpRequest,
  HttpResponse => JHttpResponse,
  HttpTimeoutException
}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.{ Locale, UUID }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Case-insensitive lookup over a multi-valued header map, as carried by every response type
 * in this package.
 */
@Stable
object HttpHeaders {

  /**
   * The first value of header `name`, matched case-insensitively, if present.
   *
   * @param headers response headers, as carried by [[HttpResponse]] and its siblings
   * @param name    header name in any case, e.g. `"Retry-After"`
   */
  def first(headers: Map[String, Seq[String]], name: String): Option[String] =
    headers.get(name).orElse(headers.get(name.toLowerCase(Locale.ROOT))) match {
      case Some(values) => values.headOption
      case None         => headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) && v.nonEmpty => v.head }
    }
}

/**
 * HTTP response wrapper exposing status code, body, and headers.
 *
 * @param statusCode HTTP status code (e.g. 200, 404)
 * @param body       Response body as a string
 * @param headers    Response headers as a multi-valued map (lowercase keys)
 */
@Stable
case class HttpResponse(
  statusCode: Int,
  body: String,
  headers: Map[String, Seq[String]] = Map.empty
) {

  /** First value of header `name`, matched case-insensitively. */
  def header(name: String): Option[String] = HttpHeaders.first(headers, name)
}

@Stable
final case class JsonHttpResponse(
  statusCode: Int,
  body: ujson.Value,
  headers: Map[String, Seq[String]] = Map.empty
) {

  /** First value of header `name`, matched case-insensitively. */
  def header(name: String): Option[String] = HttpHeaders.first(headers, name)
}

object HttpResponse:
  extension (response: HttpResponse)
    /**
     * The response when its status is 2xx, else a `ServiceError` carrying the status and the body. The body in the
     * error is redacted and capped (`Redaction.safeBody`, 2048 characters), since a provider's error body can echo
     * the request's credentials.
     */
    def ensureSuccess(provider: String): Result[HttpResponse] =
      if response.statusCode >= 200 && response.statusCode < 300 then Right(response)
      else Left(ServiceError(response.statusCode, provider, Redaction.safeBody(response.body)))

    def toJson(fieldName: String = "responseBody"): Result[JsonHttpResponse] =
      Try(ujson.read(response.body)).toResult.left
        .map(err => ValidationError(fieldName, s"Failed to parse JSON response: ${err.message}"))
        .map(json => JsonHttpResponse(response.statusCode, json, response.headers))

  extension [A](result: Result[A])
    def mapServiceError(
      provider: String,
      message: String
    ): Result[A] =
      result.left.map:
        case service: ServiceError =>
          ServiceError(service.httpStatus, provider, s"$message: ${service.message}")
        case other =>
          other

/**
 * HTTP response wrapper for binary (non-text) response bodies.
 *
 * Use this instead of [[HttpResponse]] when the response body is binary data
 * (e.g. image bytes) to avoid lossy charset decoding.
 *
 * @param statusCode HTTP status code
 * @param body       Raw response bytes — exact wire representation, no charset conversion
 * @param headers    Response headers as a multi-valued map (lowercase keys)
 */
@Stable
case class HttpRawResponse(
  statusCode: Int,
  body: Array[Byte],
  headers: Map[String, Seq[String]] = Map.empty
) {

  /** First value of header `name`, matched case-insensitively. */
  def header(name: String): Option[String] = HttpHeaders.first(headers, name)
}

/**
 * HTTP response wrapper for streaming (InputStream-based) response bodies.
 *
 * Use this instead of [[HttpResponse]] when the response body must be consumed
 * incrementally (e.g. server-sent events, JSON lines).
 *
 * @param statusCode HTTP status code
 * @param body       Response body as an InputStream — caller is responsible for closing it,
 *                   on error statuses too
 * @param headers    Response headers as a multi-valued map (lowercase keys)
 */
@Stable
case class StreamingHttpResponse(
  statusCode: Int,
  body: java.io.InputStream,
  headers: Map[String, Seq[String]] = Map.empty
) {

  /** First value of header `name`, matched case-insensitively. */
  def header(name: String): Option[String] = HttpHeaders.first(headers, name)
}

/**
 * Represents a single part in a multipart/form-data request.
 */
@Stable
sealed trait MultipartPart {
  def name: String
}

object MultipartPart {

  /** A text field in a multipart request. */
  case class TextField(name: String, value: String) extends MultipartPart

  /** A file field in a multipart request. */
  case class FilePart(name: String, path: Path, filename: String) extends MultipartPart
}

/**
 * The HTTP client provider modules and built-in tools use, abstracted so it can be
 * injected and replaced by a test double.
 *
 * Every request method returns a `Result` and never throws for a transport failure:
 *
 *  - a request that times out is a `Left(`[[org.llm4s.error.TimeoutError]]`)`;
 *  - a connection failure, unknown host or other I/O error is a `Left(`[[org.llm4s.error.NetworkError]]`)`;
 *  - an invalid URL, header or timeout, or an unreadable multipart file, is a
 *    `Left(`[[org.llm4s.error.ValidationError]]`)`;
 *  - an interrupted request, or a stream read interrupted mid-body, is a
 *    `Left(`[[org.llm4s.error.CancelledError]]`)`, with the thread's interrupt flag kept.
 *
 * A non-2xx status is '''not''' an error at this layer: it is a `Right` response, which the
 * caller inspects (`HttpResponse.ensureSuccess`, or
 * [[org.llm4s.llmconnect.provider.HttpErrorMapper.mapHttpError]] with the response headers).
 *
 * Headers are passed as single-valued maps. Response headers are multi-valued, keyed in
 * lower case; look one up with `header(name)`.
 *
 * The trait is deliberately not sealed, so tests can implement it. Methods added to it
 * after 1.0 will have default implementations, so a test double keeps compiling.
 */
@Stable
trait Llm4sHttpClient extends AutoCloseable {

  def get(
    url: String,
    headers: Map[String, String] = Map.empty,
    params: Map[String, String] = Map.empty,
    timeout: FiniteDuration = 10.seconds
  ): Result[HttpResponse]

  def post(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: FiniteDuration = 10.seconds
  ): Result[HttpResponse]

  def postBytes(
    url: String,
    headers: Map[String, String] = Map.empty,
    data: Array[Byte] = Array.empty,
    timeout: FiniteDuration = 10.seconds
  ): Result[HttpResponse]

  def postMultipart(
    url: String,
    headers: Map[String, String] = Map.empty,
    parts: Seq[MultipartPart] = Seq.empty,
    timeout: FiniteDuration = 10.seconds
  ): Result[HttpResponse]

  def put(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: FiniteDuration = 10.seconds
  ): Result[HttpResponse]

  def delete(
    url: String,
    headers: Map[String, String] = Map.empty,
    timeout: FiniteDuration = 10.seconds
  ): Result[HttpResponse]

  /**
   * POST with a string body and return the response as raw bytes, bypassing charset decoding.
   *
   * Use this when the response body is binary (e.g. image data) where decoding to a String
   * and back would corrupt bytes that are not valid in the chosen charset.
   */
  def postRaw(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: FiniteDuration = 10.seconds
  ): Result[HttpRawResponse]

  /**
   * POST with a string body and return the response as a streaming InputStream.
   *
   * Use this for server-sent events or JSON-lines endpoints where the body must be
   * consumed incrementally. The caller is responsible for closing the InputStream,
   * including on a non-2xx status. A failure while reading the stream surfaces as an
   * `IOException` from the stream itself, not through the returned `Result`.
   *
   * Default timeout is 10 minutes to accommodate long-running streams.
   */
  def postStream(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: FiniteDuration = 10.minutes
  ): Result[StreamingHttpResponse]

  /**
   * Releases the connections and threads this client holds. A no-op unless the implementation
   * holds any; a client that owns one of these closes it when it is itself closed.
   */
  override def close(): Unit = ()
}

object Llm4sHttpClient {

  /** Creates the default JDK-backed HTTP client. */
  def create(): Llm4sHttpClient = new JdkHttpClient(None)

  /**
   * Creates the JDK-backed HTTP client with a limit on establishing each connection, separate
   * from every request's own `timeout`.
   */
  def create(connectTimeout: FiniteDuration): Llm4sHttpClient = new JdkHttpClient(Some(connectTimeout))
}

/**
 * Maps a transport-level `Throwable` raised while sending a request to an [[LLMError]].
 * Shared by [[JdkHttpClient]] and by test doubles that want the same mapping.
 */
private[llm4s] object HttpFailures {

  /**
   * Classifies only: a cancellation becomes a [[CancelledError]], but this never sets the
   * interrupt flag. [[attempt]] restores it when it catches an `InterruptedException`; an
   * interrupt that closes a socket (a virtual thread) or that the JDK's body stream reports
   * leaves the flag set itself.
   *
   * @param t       what the transport threw
   * @param method  HTTP method, for the message
   * @param url     request URL; only its scheme, host, port and path reach the error, so a
   *                key carried in the query string is never surfaced
   * @param timeout the request's timeout, carried on a [[TimeoutError]]
   */
  def toLLMError(t: Throwable, method: String, url: String, timeout: FiniteDuration): LLMError = {
    val endpoint = safeEndpoint(url)
    val detail   = Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.getClass.getSimpleName)
    t match {
      case e if CancelledError.isCancellation(e) =>
        CancelledError(s"http.$method", Some(e))
      case e: HttpConnectTimeoutException =>
        TimeoutError(s"$method $endpoint: connection timed out after $timeout", timeout, s"http.$method", Some(e))
          .withContext("endpoint", endpoint)
      case e: HttpTimeoutException =>
        TimeoutError(s"$method $endpoint: request timed out after $timeout", timeout, s"http.$method", Some(e))
          .withContext("endpoint", endpoint)
      case e: java.net.SocketTimeoutException =>
        TimeoutError(s"$method $endpoint: socket timed out after $timeout", timeout, s"http.$method", Some(e))
          .withContext("endpoint", endpoint)
      case _: IllegalArgumentException =>
        ValidationError("request", s"Invalid $method request to $endpoint: $detail")
      case e: java.net.ConnectException =>
        NetworkError(s"$method $endpoint: connection failed: $detail", Some(e), endpoint)
      case e: java.net.UnknownHostException =>
        NetworkError(s"$method $endpoint: unknown host: $detail", Some(e), endpoint)
      case e: IOException =>
        NetworkError(s"$method $endpoint: I/O error: $detail", Some(e), endpoint)
      case e =>
        UnknownError(s"$method $endpoint: unexpected HTTP client failure: $detail", e)
    }
  }

  /**
   * A failure while reading a response body that is already open - a streamed reply cut off
   * mid-read. An I/O failure is classified as it would be during the request (a reset or
   * dropped connection is a recoverable [[NetworkError]], a socket timeout a [[TimeoutError]]),
   * so retry logic treats it the same; anything else, such as a malformed chunk, keeps the
   * default mapping.
   */
  def streamReadError(t: Throwable, url: String, timeout: FiniteDuration): LLMError =
    t match {
      case e if CancelledError.isCancellation(e) => toLLMError(e, "POST", url, timeout)
      case e: IOException                        => toLLMError(e, "POST", url, timeout)
      case e                                     => org.llm4s.error.ThrowableOps.RichThrowable(e).toLLMError
    }

  /** Scheme, host, port and path of `url`; never its query string or user info. */
  def safeEndpoint(url: String): String =
    Try(URI.create(url)).toOption
      .filter(u => u.getScheme != null && u.getHost != null)
      .map { u =>
        val port = if (u.getPort >= 0) s":${u.getPort}" else ""
        s"${u.getScheme}://${u.getHost}$port${Option(u.getRawPath).getOrElse("")}"
      }
      .getOrElse(url.takeWhile(c => c != '?' && c != '#'))

  /**
   * Runs `thunk`, catching non-fatal throwables and `InterruptedException` (which `NonFatal`
   * excludes, as do `Try` and `scala.util.control.Exception`) and mapping them with
   * [[toLLMError]]. An interruption restores the thread's interrupt flag before returning.
   *
   * This is the one place transport exceptions become `Result`s, so no caller of
   * [[Llm4sHttpClient]] needs a `try`/`catch` of its own.
   */
  // scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
  def attempt[A](method: String, url: String, timeout: FiniteDuration)(thunk: => A): Result[A] =
    try Right(thunk)
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(toLLMError(e, method, url, timeout))
      case NonFatal(e) => Left(toLLMError(e, method, url, timeout))
  // scalafix:on DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch

  /** JDK response headers as an immutable multi-valued map with lower-case keys. */
  def headerMap(headers: JHttpHeaders): Map[String, Seq[String]] =
    headers
      .map()
      .asScala
      .map { case (key, values) => key.toLowerCase(Locale.ROOT) -> values.asScala.toSeq }
      .toMap
}

/**
 * JDK 11+ `java.net.http.HttpClient` implementation of [[Llm4sHttpClient]].
 *
 * Uses a single shared `HttpClient` instance for connection pooling.
 * Never fails on non-2xx responses — the caller is responsible for
 * checking `statusCode`.
 */
private[llm4s] class JdkHttpClient(connectTimeout: Option[FiniteDuration]) extends Llm4sHttpClient {
  private val client =
    connectTimeout.fold(JHttpClient.newHttpClient())(t =>
      JHttpClient.newBuilder().connectTimeout(java.time.Duration.ofNanos(t.toNanos)).build()
    )

  /** Closes the JDK client where the running JDK supports it (21+): its pool and executor. */
  override def close(): Unit =
    (client: Any) match {
      case c: AutoCloseable => c.close()
      case _                => ()
    }

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] = {
    val fullUrl = appendQueryParams(url, params)
    sendString("GET", fullUrl, timeout)(buildRequest(fullUrl, headers, timeout).GET().build())
  }

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    sendString("POST", url, timeout)(
      buildRequest(url, headers, timeout).POST(HttpRequest.BodyPublishers.ofString(body)).build()
    )

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    sendString("POST", url, timeout)(
      buildRequest(url, headers, timeout).POST(HttpRequest.BodyPublishers.ofByteArray(data)).build()
    )

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: FiniteDuration
  ): Result[HttpResponse] = {
    val boundary = UUID.randomUUID().toString
    Try(buildMultipartBody(parts, boundary)).toEither.left
      .map(e => ValidationError("parts", s"Failed to read multipart body: ${e.getMessage}"))
      .flatMap { body =>
        val allHeaders = headers + ("Content-Type" -> s"multipart/form-data; boundary=$boundary")
        sendString("POST", url, timeout)(
          buildRequest(url, allHeaders, timeout).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        )
      }
  }

  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    sendString("PUT", url, timeout)(
      buildRequest(url, headers, timeout).PUT(HttpRequest.BodyPublishers.ofString(body)).build()
    )

  override def delete(
    url: String,
    headers: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    sendString("DELETE", url, timeout)(buildRequest(url, headers, timeout).DELETE().build())

  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpRawResponse] =
    HttpFailures.attempt("POST", url, timeout) {
      val request = buildRequest(url, headers, timeout)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val response = client.send(request, JHttpResponse.BodyHandlers.ofByteArray())
      HttpRawResponse(response.statusCode(), response.body(), HttpFailures.headerMap(response.headers()))
    }

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[StreamingHttpResponse] =
    HttpFailures.attempt("POST", url, timeout) {
      val request = buildRequest(url, headers, timeout)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val response = client.send(request, JHttpResponse.BodyHandlers.ofInputStream())
      StreamingHttpResponse(response.statusCode(), response.body(), HttpFailures.headerMap(response.headers()))
    }

  // ============================================================
  // Internal helpers
  // ============================================================

  private def buildRequest(
    url: String,
    headers: Map[String, String],
    timeout: FiniteDuration
  ): HttpRequest.Builder = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofNanos(timeout.toNanos))

    headers.foreach { case (key, value) =>
      builder.header(key, value)
    }

    builder
  }

  /** Builds the request inside the guarded block, so an invalid URL or header is a `Left` too. */
  private def sendString(method: String, url: String, timeout: FiniteDuration)(
    request: => HttpRequest
  ): Result[HttpResponse] =
    HttpFailures.attempt(method, url, timeout) {
      val response = client.send(request, JHttpResponse.BodyHandlers.ofString())
      HttpResponse(
        statusCode = response.statusCode(),
        body = response.body(),
        headers = HttpFailures.headerMap(response.headers())
      )
    }

  private def appendQueryParams(url: String, params: Map[String, String]): String =
    if (params.isEmpty) url
    else {
      val separator = if (url.contains("?")) "&" else "?"
      val queryParts = params.map { case (k, v) =>
        s"${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
      }
      s"$url$separator${queryParts.mkString("&")}"
    }

  private def buildMultipartBody(parts: Seq[MultipartPart], boundary: String): Array[Byte] = {
    val crlf    = "\r\n"
    val builder = new java.io.ByteArrayOutputStream()

    parts.foreach {
      case MultipartPart.TextField(name, value) =>
        val header = s"--$boundary$crlf" +
          s"Content-Disposition: form-data; name=\"$name\"$crlf" +
          crlf
        builder.write(header.getBytes(StandardCharsets.UTF_8))
        builder.write(value.getBytes(StandardCharsets.UTF_8))
        builder.write(crlf.getBytes(StandardCharsets.UTF_8))

      case MultipartPart.FilePart(name, path, filename) =>
        val header = s"--$boundary$crlf" +
          s"Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"$crlf" +
          s"Content-Type: application/octet-stream$crlf" +
          crlf
        builder.write(header.getBytes(StandardCharsets.UTF_8))
        builder.write(java.nio.file.Files.readAllBytes(path))
        builder.write(crlf.getBytes(StandardCharsets.UTF_8))
    }

    builder.write(s"--$boundary--$crlf".getBytes(StandardCharsets.UTF_8))
    builder.toByteArray
  }
}
