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
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URLEncoder
import java.net.http.{
  HttpClient => JHttpClient,
  HttpConnectTimeoutException,
  HttpHeaders => JHttpHeaders,
  HttpRequest => JHttpRequest,
  HttpResponse => JHttpResponse,
  HttpTimeoutException
}
import java.nio.charset.{ Charset, StandardCharsets }
import java.nio.file.Path
import java.time.{ Clock, Duration, ZonedDateTime }
import java.time.format.DateTimeFormatter
import java.util.{ Locale, UUID }
import java.util.concurrent.{ ScheduledFuture, ScheduledThreadPoolExecutor }
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }
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

  /**
   * The headers a provider names its request id in, most common first: `x-request-id` (OpenAI and most
   * OpenAI-compatible APIs), `request-id` (Anthropic), `x-amzn-requestid` (AWS) and `apim-request-id` (Azure).
   */
  private[llm4s] val RequestIdHeaders: Seq[String] =
    Seq("x-request-id", "request-id", "x-amzn-requestid", "apim-request-id")

  /** The first non-blank request id among [[RequestIdHeaders]], trimmed. */
  private[llm4s] def requestId(headers: Map[String, Seq[String]]): Option[String] =
    RequestIdHeaders.iterator.flatMap(first(headers, _)).map(_.trim).find(_.nonEmpty)

  /**
   * The delay a `Retry-After` header asks for.
   *
   * Accepts delta-seconds (`"120"`) or an RFC 1123 HTTP-date, which is measured against
   * `clock` (a date already past gives zero), to millisecond precision. Returns `None` when
   * the header is absent, unparseable, negative, or too large to represent as a
   * `FiniteDuration`.
   */
  private[llm4s] def retryAfter(headers: Map[String, Seq[String]], clock: Clock): Option[FiniteDuration] =
    first(headers, "Retry-After").map(_.trim).flatMap { value =>
      val millis =
        if value.nonEmpty && value.forall(_.isDigit) then
          value.toLongOption.filter(_ <= MaxRetryAfterMillis / 1000L).map(_ * 1000L)
        else
          Try(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toOption
            .flatMap(date => Try(Duration.between(clock.instant(), date.toInstant).toMillis).toOption)
            .map(math.max(0L, _))
      millis.filter(_ <= MaxRetryAfterMillis).map(FiniteDuration(_, MILLISECONDS))
    }

  /** The longest delay a `FiniteDuration` (nanosecond-backed) holds, in whole milliseconds. */
  private val MaxRetryAfterMillis = Long.MaxValue / 1000000L
}

/**
 * HTTP response wrapper exposing status code, body, and headers.
 *
 * @param statusCode HTTP status code (e.g. 200, 404)
 * @param body       Response body as a string
 * @param headers    Response headers as a multi-valued map (lowercase keys)
 *
 * Build one with [[HttpResponse.apply]] and change it with the `with*` setters; the constructor and
 * `copy` are private, so a new field never breaks callers.
 */
@Stable
final case class HttpResponse private (
  statusCode: Int,
  body: String,
  headers: Map[String, Seq[String]]
) {

  /** Sets the HTTP status code. */
  def withStatusCode(statusCode: Int): HttpResponse = copy(statusCode = statusCode)

  /** Sets the response body. */
  def withBody(body: String): HttpResponse = copy(body = body)

  /** Sets the response headers (a multi-valued map with lowercase keys). */
  def withHeaders(headers: Map[String, Seq[String]]): HttpResponse = copy(headers = headers)

  /** First value of header `name`, matched case-insensitively. */
  def header(name: String): Option[String] = HttpHeaders.first(headers, name)

  /**
   * This response as [[Llm4sHttpClient.send]] returns one, its body the UTF-8 bytes of `body`: what a test
   * double's `send` answers with.
   */
  def toStreaming: StreamingHttpResponse =
    StreamingHttpResponse(statusCode, new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), headers)
}

/**
 * An HTTP response whose body has been parsed as JSON (see `HttpResponse.toJson`).
 *
 * @param statusCode HTTP status code
 * @param body       Response body parsed as JSON
 * @param headers    Response headers as a multi-valued map (lowercase keys)
 *
 * Build one with [[JsonHttpResponse.apply]] and change it with the `with*` setters; the constructor and
 * `copy` are private, so a new field never breaks callers.
 */
@Stable
final case class JsonHttpResponse private (
  statusCode: Int,
  body: ujson.Value,
  headers: Map[String, Seq[String]]
) {

  /** Sets the HTTP status code. */
  def withStatusCode(statusCode: Int): JsonHttpResponse = copy(statusCode = statusCode)

  /** Sets the response body, parsed as JSON. */
  def withBody(body: ujson.Value): JsonHttpResponse = copy(body = body)

  /** Sets the response headers (a multi-valued map with lowercase keys). */
  def withHeaders(headers: Map[String, Seq[String]]): JsonHttpResponse = copy(headers = headers)

  /** First value of header `name`, matched case-insensitively. */
  def header(name: String): Option[String] = HttpHeaders.first(headers, name)
}

object JsonHttpResponse {

  /**
   * Creates a [[JsonHttpResponse]].
   *
   * @param statusCode HTTP status code
   * @param body       response body, parsed as JSON
   * @param headers    response headers (a multi-valued map with lowercase keys)
   */
  def apply(statusCode: Int, body: ujson.Value, headers: Map[String, Seq[String]] = Map.empty): JsonHttpResponse =
    new JsonHttpResponse(statusCode, body, headers)
}

object HttpResponse:

  /**
   * Creates an [[HttpResponse]].
   *
   * @param statusCode HTTP status code
   * @param body       response body
   * @param headers    response headers (a multi-valued map with lowercase keys)
   */
  def apply(statusCode: Int, body: String, headers: Map[String, Seq[String]] = Map.empty): HttpResponse =
    new HttpResponse(statusCode, body, headers)

  /**
   * A `ServiceError` for a non-2xx `response`, carrying its status, the request id from the first of
   * [[HttpHeaders.RequestIdHeaders]] it sent, and the delay its `Retry-After` asks for. `details` is the
   * already-redacted body, or whatever the caller wants in the message.
   */
  private[llm4s] def serviceError(
    response: HttpResponse,
    provider: String,
    details: String,
    clock: Clock = Clock.systemUTC()
  ): ServiceError =
    val error = HttpHeaders.requestId(response.headers) match
      case Some(id) => ServiceError(response.statusCode, provider, details, id)
      case None     => ServiceError(response.statusCode, provider, details)
    HttpHeaders.retryAfter(response.headers, clock).fold(error)(error.withRetryAfter)

  extension (response: HttpResponse)
    /**
     * The response when its status is 2xx, else a `ServiceError` carrying the status and the body. The body in the
     * error is redacted and capped (`Redaction.safeBody`, 2048 characters), since a provider's error body can echo
     * the request's credentials. The error also carries the response's request id (`x-request-id`, `request-id`,
     * `x-amzn-requestid` or `apim-request-id`) and its `Retry-After` delay (delta-seconds or an HTTP-date), when
     * it sent them.
     */
    def ensureSuccess(provider: String): Result[HttpResponse] =
      if response.statusCode >= 200 && response.statusCode < 300 then Right(response)
      else Left(HttpResponse.serviceError(response, provider, Redaction.safeBody(response.body)))

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
          ServiceError.rewrapped(service, provider, s"$message: ${service.message}")
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
 *
 * Build one with [[HttpRawResponse.apply]] and change it with the `with*` setters; the constructor and
 * `copy` are private, so a new field never breaks callers.
 */
@Stable
final case class HttpRawResponse private (
  statusCode: Int,
  body: Array[Byte],
  headers: Map[String, Seq[String]]
) {

  /** Sets the HTTP status code. */
  def withStatusCode(statusCode: Int): HttpRawResponse = copy(statusCode = statusCode)

  /** Sets the raw response bytes. */
  def withBody(body: Array[Byte]): HttpRawResponse = copy(body = body)

  /** Sets the response headers (a multi-valued map with lowercase keys). */
  def withHeaders(headers: Map[String, Seq[String]]): HttpRawResponse = copy(headers = headers)

  /** First value of header `name`, matched case-insensitively. */
  def header(name: String): Option[String] = HttpHeaders.first(headers, name)

  /** This response as [[Llm4sHttpClient.send]] returns one: what a test double's `send` answers with. */
  def toStreaming: StreamingHttpResponse =
    StreamingHttpResponse(statusCode, new java.io.ByteArrayInputStream(body), headers)
}

object HttpRawResponse {

  /**
   * Creates an [[HttpRawResponse]].
   *
   * @param statusCode HTTP status code
   * @param body       raw response bytes
   * @param headers    response headers (a multi-valued map with lowercase keys)
   */
  def apply(statusCode: Int, body: Array[Byte], headers: Map[String, Seq[String]] = Map.empty): HttpRawResponse =
    new HttpRawResponse(statusCode, body, headers)
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
 *
 * Build one with [[StreamingHttpResponse.apply]] and change it with the `with*` setters; the constructor and
 * `copy` are private, so a new field never breaks callers.
 */
@Stable
final case class StreamingHttpResponse private (
  statusCode: Int,
  body: java.io.InputStream,
  headers: Map[String, Seq[String]]
) {

  /** Sets the HTTP status code. */
  def withStatusCode(statusCode: Int): StreamingHttpResponse = copy(statusCode = statusCode)

  /** Sets the response body stream; the caller closes it. */
  def withBody(body: java.io.InputStream): StreamingHttpResponse = copy(body = body)

  /** Sets the response headers (a multi-valued map with lowercase keys). */
  def withHeaders(headers: Map[String, Seq[String]]): StreamingHttpResponse = copy(headers = headers)

  /** First value of header `name`, matched case-insensitively. */
  def header(name: String): Option[String] = HttpHeaders.first(headers, name)

  /**
   * This response with a body whose reads give up: a read that has waited `idle` for more bytes fails with a
   * `java.net.SocketTimeoutException`, and the body is closed. Time the caller spends between reads is not
   * counted. An idle time of zero or less fails the first read that has to wait.
   *
   * For an [[Llm4sHttpClient]] whose transport has no read timeout of its own. It relies on closing the body
   * from another thread unblocking a read of it, as it does for a socket and for the JDK HTTP client.
   */
  def withIdleTimeout(idle: FiniteDuration): StreamingHttpResponse =
    copy(body = new IdleTimeoutInputStream(body, idle))
}

object StreamingHttpResponse {

  /**
   * Creates an [[StreamingHttpResponse]].
   *
   * @param statusCode HTTP status code
   * @param body       response body stream; the caller closes it
   * @param headers    response headers (a multi-valued map with lowercase keys)
   */
  def apply(
    statusCode: Int,
    body: java.io.InputStream,
    headers: Map[String, Seq[String]] = Map.empty
  ): StreamingHttpResponse =
    new StreamingHttpResponse(statusCode, body, headers)
}

/**
 * Represents a single part in a multipart/form-data request.
 *
 * Build one with [[MultipartPart.TextField]]`(name, value)`, [[MultipartPart.FilePart]]`(name, path, filename)`
 * or [[MultipartPart.FilePart.ofBytes]], and change it with the `with*` setters; the constructors and `copy`
 * are private, so a new field never breaks callers.
 */
@Stable
sealed trait MultipartPart {
  def name: String
}

object MultipartPart {

  /** A text field in a multipart request. */
  final case class TextField private (name: String, value: String) extends MultipartPart {

    /** Sets the field name. */
    def withName(name: String): TextField = copy(name = name)

    /** Sets the field value. */
    def withValue(value: String): TextField = copy(value = value)
  }

  object TextField {

    /** A text field `name` holding `value`. */
    def apply(name: String, value: String): TextField = new TextField(name, value)
  }

  /** Where a [[FilePart]]'s bytes come from. */
  sealed trait Content

  object Content {

    /** The bytes of the file at `path`, read when the request is sent. */
    final case class File private (path: Path) extends Content

    object File {
      def apply(path: Path): File = new File(path)
    }

    /** Bytes already in memory. */
    final case class Bytes private (bytes: Array[Byte]) extends Content

    object Bytes {
      def apply(bytes: Array[Byte]): Bytes = new Bytes(bytes)
    }
  }

  /**
   * A file field in a multipart request.
   *
   * @param name        the form field name
   * @param content     the file on disk, or bytes in memory
   * @param filename    the file name the server is told
   * @param contentType the part's `Content-Type`
   */
  final case class FilePart private (name: String, content: Content, filename: String, contentType: String)
      extends MultipartPart {

    /** Sets the field name. */
    def withName(name: String): FilePart = copy(name = name)

    /** Sets where the bytes come from. */
    def withContent(content: Content): FilePart = copy(content = content)

    /** Sends the file at `path`, read when the request is sent. */
    def withPath(path: Path): FilePart = copy(content = Content.File(path))

    /** Sends `bytes`. */
    def withBytes(bytes: Array[Byte]): FilePart = copy(content = Content.Bytes(bytes))

    /** Sets the file name the server is told. */
    def withFilename(filename: String): FilePart = copy(filename = filename)

    /** Sets the part's `Content-Type`. */
    def withContentType(contentType: String): FilePart = copy(contentType = contentType)
  }

  object FilePart {

    /** The `Content-Type` of a part that names none. */
    val DefaultContentType: String = "application/octet-stream"

    /** A file field sending the file at `path`, read when the request is sent. */
    def apply(name: String, path: Path, filename: String, contentType: String = DefaultContentType): FilePart =
      new FilePart(name, Content.File(path), filename, contentType)

    /** A file field sending `bytes` already in memory. */
    def ofBytes(
      name: String,
      bytes: Array[Byte],
      filename: String,
      contentType: String = DefaultContentType
    ): FilePart =
      new FilePart(name, Content.Bytes(bytes), filename, contentType)
  }
}

/** Encodes multipart/form-data, for [[HttpRequest.withMultipartEncoded]]. */
private[llm4s] object MultipartEncoding {

  /**
   * One part ready to send: its names escaped, its bytes read.
   *
   * @param name        the field name, escaped
   * @param file        for a file part, its file name (escaped) and its content type
   * @param bytes       the part's body; a text field's value as UTF-8
   */
  final case class Resolved(name: String, file: Option[(String, String)], bytes: Array[Byte])

  /**
   * A field name or file name as it goes inside the quotes of a `Content-Disposition` parameter: `"`, CR and
   * LF percent-encoded, as browsers do (the WHATWG form-data encoding), so neither can end the parameter or
   * start a header of its own.
   */
  def quoted(value: String): String =
    value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

  /**
   * Each part with its names escaped and its file read. An unreadable file, or a content type that holds a
   * line break (it would start a header of its own), is a `Left(ValidationError)`.
   */
  def resolve(parts: Seq[MultipartPart]): Result[Seq[Resolved]] =
    parts.foldLeft[Result[Vector[Resolved]]](Right(Vector.empty)) { (acc, part) =>
      acc.flatMap { done =>
        val next: Result[Resolved] = part match {
          case field: MultipartPart.TextField =>
            Right(Resolved(quoted(field.name), None, field.value.getBytes(StandardCharsets.UTF_8)))
          case file: MultipartPart.FilePart =>
            for {
              _ <- Either.cond(
                !file.contentType.exists(c => c == '\r' || c == '\n'),
                (),
                ValidationError("parts", s"Content type of multipart part '${quoted(file.name)}' holds a line break")
              )
              bytes <- file.content match {
                case MultipartPart.Content.Bytes(bytes) => Right(bytes)
                case MultipartPart.Content.File(path) =>
                  Try(java.nio.file.Files.readAllBytes(path)).toEither.left
                    .map(e => ValidationError("parts", s"Failed to read multipart body: ${e.getMessage}"))
              }
            } yield Resolved(quoted(file.name), Some((quoted(file.filename), file.contentType)), bytes)
        }
        next.map(done :+ _)
      }
    }

  /** The parts as one body delimited by `boundary`; see [[resolve]] for what makes it a `Left`. */
  def encode(parts: Seq[MultipartPart], boundary: String): Result[Array[Byte]] =
    resolve(parts).map { resolved =>
      val crlf                      = "\r\n"
      val out                       = new java.io.ByteArrayOutputStream()
      def write(text: String): Unit = out.write(text.getBytes(StandardCharsets.UTF_8))
      resolved.foreach { part =>
        write(s"--$boundary$crlf")
        part.file match {
          case None =>
            write(s"Content-Disposition: form-data; name=\"${part.name}\"$crlf$crlf")
          case Some((filename, contentType)) =>
            write(s"Content-Disposition: form-data; name=\"${part.name}\"; filename=\"$filename\"$crlf")
            write(s"Content-Type: $contentType$crlf$crlf")
        }
        out.write(part.bytes)
        write(crlf)
      }
      write(s"--$boundary--$crlf")
      out.toByteArray
    }
}

/** The method of an [[HttpRequest]]. */
@Stable
enum HttpMethod(val name: String) {
  case Get     extends HttpMethod("GET")
  case Head    extends HttpMethod("HEAD")
  case Post    extends HttpMethod("POST")
  case Put     extends HttpMethod("PUT")
  case Patch   extends HttpMethod("PATCH")
  case Delete  extends HttpMethod("DELETE")
  case Options extends HttpMethod("OPTIONS")
}

/** The body of an [[HttpRequest]]. */
@Stable
sealed trait HttpBody

object HttpBody {

  /** No body. */
  case object Empty extends HttpBody

  /** Text, sent as UTF-8. */
  final case class Text private (value: String) extends HttpBody

  object Text {
    def apply(value: String): Text = new Text(value)
  }

  /** Bytes, sent as they are. */
  final case class Bytes private (value: Array[Byte]) extends HttpBody

  object Bytes {
    def apply(value: Array[Byte]): Bytes = new Bytes(value)
  }

  /**
   * `multipart/form-data`. A client with no multipart support of its own calls
   * [[HttpRequest.withMultipartEncoded]] and sends the bytes it gives.
   */
  final case class Multipart private (parts: Seq[MultipartPart]) extends HttpBody

  object Multipart {
    def apply(parts: Seq[MultipartPart]): Multipart = new Multipart(parts)
  }
}

/**
 * One HTTP request, as [[Llm4sHttpClient.send]] takes it.
 *
 * The two timeouts bound different waits, and neither is a deadline for the whole exchange:
 *
 *  - `responseTimeout` is the longest wait, from sending the request, for the response's status line and
 *    headers. It covers connecting too, unless the client was created with a shorter connect timeout.
 *  - `idleTimeout` is the longest a read of the response body waits for more bytes. It is not measured while
 *    the caller is busy between reads, so a slow consumer never trips it, and a stream that keeps delivering
 *    may run for any length of time.
 *
 * Build one with [[HttpRequest.apply]] and change it with the `with*` setters; the constructor and `copy` are
 * private, so a new field never breaks callers. `toString` leaves the headers and query out, since either may
 * carry a credential.
 *
 * @param method          the HTTP method
 * @param url             absolute `http` or `https` URL; `queryParams` are appended to it
 * @param headers         request headers, single-valued
 * @param queryParams     query parameters, URL-encoded and appended to `url` by [[fullUrl]]
 * @param body            the request body
 * @param responseTimeout the longest wait for the response's status line and headers
 * @param idleTimeout     the longest a read of the response body waits for more bytes
 */
@Stable
final case class HttpRequest private (
  method: HttpMethod,
  url: String,
  headers: Map[String, String],
  queryParams: Map[String, String],
  body: HttpBody,
  responseTimeout: FiniteDuration,
  idleTimeout: FiniteDuration
) {

  /** Sets the HTTP method. */
  def withMethod(method: HttpMethod): HttpRequest = copy(method = method)

  /** Sets the URL, without the [[queryParams]]. */
  def withUrl(url: String): HttpRequest = copy(url = url)

  /** Replaces the request headers. */
  def withHeaders(headers: Map[String, String]): HttpRequest = copy(headers = headers)

  /** Adds or replaces one request header. */
  def withHeader(name: String, value: String): HttpRequest = copy(headers = headers + (name -> value))

  /** Replaces the query parameters appended to the URL. */
  def withQueryParams(queryParams: Map[String, String]): HttpRequest = copy(queryParams = queryParams)

  /** Sets the request body. */
  def withBody(body: HttpBody): HttpRequest = copy(body = body)

  /** Sets a text body, sent as UTF-8. */
  def withTextBody(text: String): HttpRequest = copy(body = HttpBody.Text(text))

  /** Sets a body of bytes. */
  def withBytesBody(bytes: Array[Byte]): HttpRequest = copy(body = HttpBody.Bytes(bytes))

  /** Sets a `multipart/form-data` body. */
  def withMultipartBody(parts: Seq[MultipartPart]): HttpRequest = copy(body = HttpBody.Multipart(parts))

  /** Sets the longest wait for the response's status line and headers. */
  def withResponseTimeout(responseTimeout: FiniteDuration): HttpRequest = copy(responseTimeout = responseTimeout)

  /** Sets the longest a read of the response body waits for more bytes. */
  def withIdleTimeout(idleTimeout: FiniteDuration): HttpRequest = copy(idleTimeout = idleTimeout)

  /** Sets both timeouts to `timeout`: the wait for the response head, and each wait for more of the body. */
  def withTimeout(timeout: FiniteDuration): HttpRequest = copy(responseTimeout = timeout, idleTimeout = timeout)

  /** `url` with [[queryParams]] form-encoded and appended, after any query it already has. */
  def fullUrl: String =
    if (queryParams.isEmpty) url
    else {
      val separator = if (url.contains("?")) "&" else "?"
      val encoded = queryParams.map { case (k, v) =>
        s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
      }
      s"$url$separator${encoded.mkString("&")}"
    }

  /**
   * This request with a [[HttpBody.Multipart]] body encoded: the body becomes [[HttpBody.Bytes]] and
   * `Content-Type` names the boundary. Field and file names are escaped (`"`, CR and LF percent-encoded) and
   * files are read here, so a file that cannot be read, or a part content type holding a line break, is a
   * `Left(ValidationError)`. A request with any other body is returned unchanged.
   *
   * For an [[Llm4sHttpClient]] whose transport has no multipart support of its own.
   */
  def withMultipartEncoded: Result[HttpRequest] =
    body match {
      case HttpBody.Multipart(parts) =>
        val boundary = UUID.randomUUID().toString
        MultipartEncoding.encode(parts, boundary).map { bytes =>
          copy(
            headers = headers.filterNot { case (name, _) =>
              name.equalsIgnoreCase("Content-Type")
            } +
              ("Content-Type" -> s"multipart/form-data; boundary=$boundary"),
            body = HttpBody.Bytes(bytes)
          )
        }
      case _ => Right(this)
    }

  override def toString: String = s"HttpRequest(${method.name} ${HttpFailures.safeEndpoint(url)})"
}

object HttpRequest {

  /** Both timeouts of a request that sets neither. */
  val DefaultTimeout: FiniteDuration = 10.seconds

  /**
   * Creates an [[HttpRequest]].
   *
   * @param method          the HTTP method
   * @param url             absolute `http` or `https` URL
   * @param headers         request headers
   * @param queryParams     query parameters, URL-encoded and appended to `url`
   * @param body            the request body
   * @param responseTimeout the longest wait for the response's status line and headers
   * @param idleTimeout     the longest a read of the response body waits for more bytes
   */
  def apply(
    method: HttpMethod,
    url: String,
    headers: Map[String, String] = Map.empty,
    queryParams: Map[String, String] = Map.empty,
    body: HttpBody = HttpBody.Empty,
    responseTimeout: FiniteDuration = DefaultTimeout,
    idleTimeout: FiniteDuration = DefaultTimeout
  ): HttpRequest =
    new HttpRequest(method, url, headers, queryParams, body, responseTimeout, idleTimeout)
}

/**
 * The HTTP client provider modules and built-in tools use, abstracted so it can be
 * injected and replaced by a test double.
 *
 * [[send]] is the one method an implementation writes. Everything else is a final convenience built on it:
 * [[sendText]] and [[sendRaw]] read the whole body, and `get`, `post`, `postBytes`, `postMultipart`, `put`,
 * `delete`, `postRaw` and `postStream` build the [[HttpRequest]] for the common calls. A request they do not
 * cover - a PATCH, a HEAD, a GET of bytes or of a stream - is an [[HttpRequest]] passed to one of the three.
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
 * '''Timeouts.''' The `timeout` of a convenience method is both of [[HttpRequest]]'s: the longest wait for
 * the response's status line and headers, and separately the longest any read of the body waits for more
 * bytes. It is not a deadline for the whole call. Pass an [[HttpRequest]] to set the two apart.
 *
 * Headers are passed as single-valued maps. Response headers are multi-valued, keyed in
 * lower case; look one up with `header(name)`.
 *
 * The trait is deliberately not sealed, so tests can implement it: a test double implements [[send]] alone.
 */
@Stable
trait Llm4sHttpClient extends AutoCloseable {

  /**
   * Sends `request` and returns the response with its body still open.
   *
   * An implementation:
   *
   *  - returns any status, 2xx or not, as a `Right`, with response headers keyed in lower case;
   *  - returns a transport failure as the `Left` described on [[Llm4sHttpClient]], and never throws;
   *  - gives up after `request.responseTimeout` without the response's status line and headers;
   *  - makes a read of the body that waits longer than `request.idleTimeout` fail with a
   *    `java.net.SocketTimeoutException` ([[StreamingHttpResponse.withIdleTimeout]] does this for a transport
   *    with no read timeout of its own);
   *  - sends `request.fullUrl`, and a [[HttpBody.Multipart]] body as `multipart/form-data`
   *    ([[HttpRequest.withMultipartEncoded]] encodes one).
   *
   * The caller closes the body, on a non-2xx status too. A failure while reading it surfaces as an
   * `IOException` from the stream itself, not through the returned `Result`.
   */
  def send(request: HttpRequest): Result[StreamingHttpResponse]

  /** [[send]], then the whole body as bytes; a failure while reading it is a `Left`. The body is closed. */
  final def sendRaw(request: HttpRequest): Result[HttpRawResponse] =
    send(request).flatMap(HttpFailures.readBody(request, _))

  /**
   * [[send]], then the whole body as text in the charset its `Content-Type` names, UTF-8 when it names none;
   * a failure while reading it is a `Left`. The body is closed.
   */
  final def sendText(request: HttpRequest): Result[HttpResponse] =
    sendRaw(request).map { raw =>
      HttpResponse(raw.statusCode, new String(raw.body, HttpFailures.charsetOf(raw.headers)), raw.headers)
    }

  /** GET `url` with `params` appended as its query. */
  final def get(
    url: String,
    headers: Map[String, String] = Map.empty,
    params: Map[String, String] = Map.empty,
    timeout: FiniteDuration = HttpRequest.DefaultTimeout
  ): Result[HttpResponse] =
    sendText(HttpRequest(HttpMethod.Get, url, headers, params).withTimeout(timeout))

  /** POST a text body. */
  final def post(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: FiniteDuration = HttpRequest.DefaultTimeout
  ): Result[HttpResponse] =
    sendText(HttpRequest(HttpMethod.Post, url, headers).withTextBody(body).withTimeout(timeout))

  /** POST a body of bytes. */
  final def postBytes(
    url: String,
    headers: Map[String, String] = Map.empty,
    data: Array[Byte] = Array.empty,
    timeout: FiniteDuration = HttpRequest.DefaultTimeout
  ): Result[HttpResponse] =
    sendText(HttpRequest(HttpMethod.Post, url, headers).withBytesBody(data).withTimeout(timeout))

  /** POST `parts` as `multipart/form-data`. */
  final def postMultipart(
    url: String,
    headers: Map[String, String] = Map.empty,
    parts: Seq[MultipartPart] = Seq.empty,
    timeout: FiniteDuration = HttpRequest.DefaultTimeout
  ): Result[HttpResponse] =
    sendText(HttpRequest(HttpMethod.Post, url, headers).withMultipartBody(parts).withTimeout(timeout))

  /** PUT a text body. */
  final def put(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: FiniteDuration = HttpRequest.DefaultTimeout
  ): Result[HttpResponse] =
    sendText(HttpRequest(HttpMethod.Put, url, headers).withTextBody(body).withTimeout(timeout))

  /** DELETE `url`. */
  final def delete(
    url: String,
    headers: Map[String, String] = Map.empty,
    timeout: FiniteDuration = HttpRequest.DefaultTimeout
  ): Result[HttpResponse] =
    sendText(HttpRequest(HttpMethod.Delete, url, headers).withTimeout(timeout))

  /**
   * POST with a string body and return the response as raw bytes, bypassing charset decoding.
   *
   * Use this when the response body is binary (e.g. image data) where decoding to a String
   * and back would corrupt bytes that are not valid in the chosen charset.
   */
  final def postRaw(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: FiniteDuration = HttpRequest.DefaultTimeout
  ): Result[HttpRawResponse] =
    sendRaw(HttpRequest(HttpMethod.Post, url, headers).withTextBody(body).withTimeout(timeout))

  /**
   * POST with a string body and return the response as a streaming InputStream.
   *
   * Use this for server-sent events or JSON-lines endpoints where the body must be
   * consumed incrementally. The caller is responsible for closing the InputStream,
   * including on a non-2xx status. A failure while reading the stream surfaces as an
   * `IOException` from the stream itself, not through the returned `Result`.
   *
   * The default `timeout` is 10 minutes, because a model may pause that long between events: it bounds the
   * wait for the response head and each wait for the next bytes, never the length of the stream.
   */
  final def postStream(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: FiniteDuration = 10.minutes
  ): Result[StreamingHttpResponse] =
    send(HttpRequest(HttpMethod.Post, url, headers).withTextBody(body).withTimeout(timeout))

  /**
   * Releases the connections and threads this client holds. A no-op unless the implementation
   * holds any; a client that owns one of these closes it when it is itself closed.
   */
  override def close(): Unit = ()
}

/**
 * How [[Llm4sHttpClient.create]] builds the JDK-backed client.
 *
 * Build one with [[HttpClientOptions.apply]] and change it with the `with*` setters; the constructor and
 * `copy` are private, so a new field never breaks callers.
 *
 * @param network        the proxy and TLS settings; [[HttpNetworkSettings.default]] keeps the JVM's default
 *                       proxy selector and trust store
 * @param connectTimeout the limit on establishing each connection, separate from every request's own
 *                       timeouts; `None` leaves connecting bounded by the request's `responseTimeout` alone
 */
@Stable
final case class HttpClientOptions private (
  network: HttpNetworkSettings,
  connectTimeout: Option[FiniteDuration]
) {

  /** Sets the proxy and TLS settings. */
  def withNetwork(network: HttpNetworkSettings): HttpClientOptions = copy(network = network)

  /** Sets the limit on establishing each connection. */
  def withConnectTimeout(connectTimeout: FiniteDuration): HttpClientOptions =
    copy(connectTimeout = Some(connectTimeout))

  /** Sets or clears the limit on establishing each connection. */
  def withConnectTimeout(connectTimeout: Option[FiniteDuration]): HttpClientOptions =
    copy(connectTimeout = connectTimeout)
}

object HttpClientOptions {

  /** The JVM's default proxy selector and trust store, and no connect timeout. */
  val default: HttpClientOptions = new HttpClientOptions(HttpNetworkSettings.default, None)

  /**
   * Creates an [[HttpClientOptions]].
   *
   * @param network        the proxy and TLS settings
   * @param connectTimeout the limit on establishing each connection
   */
  def apply(
    network: HttpNetworkSettings = HttpNetworkSettings.default,
    connectTimeout: Option[FiniteDuration] = None
  ): HttpClientOptions =
    new HttpClientOptions(network, connectTimeout)
}

object Llm4sHttpClient {

  /**
   * Creates the JDK-backed HTTP client.
   *
   * With no argument it uses the JVM's default proxy selector and trust store, not `llm4s.http`: pass
   * `HttpClientOptions(network)` with the settings `Llm4sConfig.httpNetwork()` loads to honour them. The client
   * is then routed and trusting as `network` says: through its proxy (except to the hosts it bypasses, answering
   * only the proxy's own authentication challenge) and with its extra trust and client certificate.
   *
   * TLS material that cannot be read (`Llm4sConfig.httpNetwork()` checks it when it loads) makes every
   * request a `Left(ConfigurationError)` naming the store, never a thrown exception.
   *
   * The JDK sends Basic proxy credentials on a plain-`http` request, but on an `https` tunnel only when
   * the JVM is started with `-Djdk.http.auth.tunneling.disabledSchemes=""` (its default disables Basic
   * there).
   *
   * @param options the proxy and TLS settings and the connect timeout
   */
  def create(options: HttpClientOptions = HttpClientOptions.default): Llm4sHttpClient =
    new JdkHttpClient(options.connectTimeout, options.network)

  /**
   * The default of a provider client's `httpClient` constructor parameter, meaning "build one from the
   * config's proxy and TLS settings": a parameter's default cannot read the `config` beside it.
   * [[resolve]] turns it into that client.
   */
  private[llm4s] lazy val FromConfig: Llm4sHttpClient = create()

  /** `client`, or a client for `network` when `client` is [[FromConfig]]. */
  private[llm4s] def resolve(client: Llm4sHttpClient, network: HttpNetworkSettings): Llm4sHttpClient =
    if (client eq FromConfig) create(HttpClientOptions(network)) else client

  /** The client a provider passes its constructor: `supplied` (from `LlmClientOptions.httpClient`), else [[FromConfig]]. */
  private[llm4s] def suppliedOrFromConfig(supplied: Option[Llm4sHttpClient]): Llm4sHttpClient =
    supplied.getOrElse(FromConfig)

  /**
   * Closes `resolved` - what [[resolve]] returned for `client` - only when the provider client built it itself:
   * a client the caller supplied stays the caller's to close (#2341).
   */
  private[llm4s] def closeIfOwned(client: Llm4sHttpClient, resolved: Llm4sHttpClient): Unit =
    if (client eq FromConfig) resolved.close()
}

/**
 * A response body whose reads give up: a read that has waited `idle` for more bytes closes `underlying`,
 * which unblocks it, and fails with a `SocketTimeoutException`. Time between reads is not counted.
 *
 * One shared daemon thread watches every open body. It holds each only weakly, so a body dropped without
 * being closed stops being watched once it is collected.
 */
final private[llm4s] class IdleTimeoutInputStream(underlying: java.io.InputStream, idle: FiniteDuration)
    extends java.io.InputStream {
  import IdleTimeoutInputStream.*

  // System.nanoTime() when the read now in progress began; NotReading between reads.
  @volatile private var readStarted: Long = NotReading
  @volatile private var timedOut: Boolean = false
  // The watch on this body, started by its first read.
  @volatile private var watch: Option[ScheduledFuture[?]] = None
  private val idleNanos                                   = idle.toNanos

  private def stopWatching(): Unit = watch.foreach(_.cancel(false))

  private def checkIdle(): Unit = {
    val started = readStarted
    if (started != NotReading && System.nanoTime() - started >= idleNanos) {
      timedOut = true
      stopWatching()
      Try(underlying.close())
      ()
    }
  }

  private def timeout(cause: Option[Throwable]): java.net.SocketTimeoutException = {
    val e = new java.net.SocketTimeoutException(s"no response data for ${idle.toMillis} ms")
    cause.foreach(e.initCause)
    e
  }

  private def reading(read: => Int): Int = {
    if (timedOut) throw timeout(None)
    // nanoTime may legitimately be NotReading; one nanosecond out is nothing.
    val now = System.nanoTime()
    readStarted = if (now == NotReading) now + 1 else now
    if (watch.isEmpty) watch = Some(startWatching(this, idleNanos))
    try
      val n = read
      if (n < 0) stopWatching()
      n
    catch case e: IOException if timedOut => throw timeout(Some(e))
    finally readStarted = NotReading
  }

  override def read(): Int                                   = reading(underlying.read())
  override def read(b: Array[Byte], off: Int, len: Int): Int = reading(underlying.read(b, off, len))
  override def available(): Int                              = underlying.available()

  override def close(): Unit = {
    stopWatching()
    underlying.close()
  }
}

private[llm4s] object IdleTimeoutInputStream {
  private val NotReading     = 0L
  private val MinPeriodNanos = 10.millis.toNanos
  private val MaxPeriodNanos = 1.second.toNanos

  private lazy val Watchdog: ScheduledThreadPoolExecutor = {
    val executor = new ScheduledThreadPoolExecutor(
      1,
      (task: Runnable) => {
        val thread = new Thread(task, "llm4s-http-idle-timeout")
        thread.setDaemon(true)
        thread
      }
    )
    executor.setRemoveOnCancelPolicy(true)
    executor
  }

  /** Checks `stream` a few times per idle period for as long as it is reachable. */
  private def startWatching(stream: IdleTimeoutInputStream, idleNanos: Long): ScheduledFuture[?] = {
    val period = math.max(MinPeriodNanos, math.min(idleNanos / 4, MaxPeriodNanos))
    val weak   = new WeakReference(stream)
    val handle = new AtomicReference[ScheduledFuture[?]]()
    val check: Runnable = () =>
      Option(weak.get()) match {
        case Some(watched) => watched.checkIdle()
        case None          => Option(handle.get()).foreach(_.cancel(false))
      }
    val scheduled = Watchdog.scheduleWithFixedDelay(check, period, period, NANOSECONDS)
    handle.set(scheduled)
    scheduled
  }
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
    val detail   = Option(t.getMessage).filter(_.nonEmpty).map(Redaction.redact(_)).getOrElse(t.getClass.getSimpleName)
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
  def attempt[A](method: String, url: String, timeout: FiniteDuration)(thunk: => A): Result[A] =
    try Right(thunk)
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(toLLMError(e, method, url, timeout))
      case NonFatal(e) => Left(toLLMError(e, method, url, timeout))

  /**
   * Reads all of `response`'s body and closes it. A failure while reading is mapped as one while sending is,
   * with `request.idleTimeout` on a [[TimeoutError]].
   */
  def readBody(request: HttpRequest, response: StreamingHttpResponse): Result[HttpRawResponse] =
    attempt(request.method.name, request.url, request.idleTimeout)(Using.resource(response.body)(_.readAllBytes()))
      .map(HttpRawResponse(response.statusCode, _, response.headers))

  /** The charset a `Content-Type` response header names; UTF-8 when it names none, or one this JVM lacks. */
  def charsetOf(headers: Map[String, Seq[String]]): Charset =
    HttpHeaders
      .first(headers, "Content-Type")
      .flatMap { contentType =>
        contentType.split(';').iterator.drop(1).map(_.trim).collectFirst {
          case param if param.regionMatches(true, 0, "charset=", 0, 8) =>
            param.drop(8).trim.stripPrefix("\"").stripSuffix("\"")
        }
      }
      .flatMap(name => Try(Charset.forName(name)).toOption)
      .getOrElse(StandardCharsets.UTF_8)

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
 *
 * `responseTimeout` is the JDK request's `timeout`, which on the JDKs this was checked on (21) ends once the
 * response head has arrived: a body that then stalls would block its reader for good, so the body is wrapped
 * to enforce `idleTimeout` ([[IdleTimeoutInputStream]]).
 */
private[llm4s] class JdkHttpClient(
  connectTimeout: Option[FiniteDuration],
  network: HttpNetworkSettings = HttpNetworkSettings.default
) extends Llm4sHttpClient {

  // Unreadable TLS material fails every request with the same ConfigurationError rather than throwing here.
  private val built: Result[JHttpClient] =
    HttpNetwork.tlsMaterial(network.tls).map { tls =>
      val builder = JHttpClient.newBuilder()
      connectTimeout.foreach(t => builder.connectTimeout(java.time.Duration.ofNanos(t.toNanos)))
      // Without a proxy the builder keeps the JVM's default selector, as before the setting existed.
      network.proxy.foreach { proxy =>
        builder.proxy(HttpNetwork.proxySelector(proxy))
        HttpNetwork.authenticator(proxy).foreach(builder.authenticator)
      }
      tls.foreach(material => builder.sslContext(material.sslContext))
      builder.build()
    }

  /** Closes the JDK client where the running JDK supports it (21+): its pool and executor. */
  override def close(): Unit =
    built.foreach { client =>
      (client: Any) match {
        case c: AutoCloseable => c.close()
        case _                => ()
      }
    }

  override def send(request: HttpRequest): Result[StreamingHttpResponse] =
    for {
      encoded <- request.withMultipartEncoded
      client  <- built
      url = encoded.fullUrl
      // The request is built inside the guarded block, so an invalid URL, header or timeout is a `Left` too.
      response <- HttpFailures.attempt(encoded.method.name, url, encoded.responseTimeout) {
        val builder = JHttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofNanos(encoded.responseTimeout.toNanos))
        encoded.headers.foreach { case (key, value) => builder.header(key, value) }
        val publisher = encoded.body match {
          case HttpBody.Text(text)   => JHttpRequest.BodyPublishers.ofString(text)
          case HttpBody.Bytes(bytes) => JHttpRequest.BodyPublishers.ofByteArray(bytes)
          // withMultipartEncoded left no multipart body.
          case HttpBody.Empty | _: HttpBody.Multipart => JHttpRequest.BodyPublishers.noBody()
        }
        val response = client.send(
          builder.method(encoded.method.name, publisher).build(),
          JHttpResponse.BodyHandlers.ofInputStream()
        )
        StreamingHttpResponse(response.statusCode(), response.body(), HttpFailures.headerMap(response.headers()))
          .withIdleTimeout(encoded.idleTimeout)
      }
    } yield response
}
