package org.llm4s.http

import java.net.URI
import java.net.URLEncoder
import java.net.http.{ HttpClient => JHttpClient, HttpRequest, HttpResponse => JHttpResponse }
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters._

/**
 * HTTP response wrapper exposing status code, body, and headers.
 *
 * @param statusCode HTTP status code (e.g. 200, 404)
 * @param body       Response body as a string
 * @param headers    Response headers as a multi-valued map (lowercase keys)
 */
case class HttpResponse(
  statusCode: Int,
  body: String,
  headers: Map[String, Seq[String]] = Map.empty
)

/**
 * HTTP response wrapper for binary (non-text) response bodies.
 *
 * Use this instead of [[HttpResponse]] when the response body is binary data
 * (e.g. image bytes) to avoid lossy charset decoding.
 *
 * @param statusCode HTTP status code
 * @param body       Raw response bytes — exact wire representation, no charset conversion
 */
case class HttpRawResponse(statusCode: Int, body: Array[Byte])

/**
 * Represents a single part in a multipart/form-data request.
 */
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
 * Abstraction for HTTP client to enable dependency injection and testing.
 *
 * All methods accept timeout in milliseconds. Headers are passed as
 * single-valued maps. The implementation never throws on non-2xx status
 * codes — callers inspect `HttpResponse.statusCode` themselves.
 */
trait Llm4sHttpClient {

  def get(
    url: String,
    headers: Map[String, String] = Map.empty,
    params: Map[String, String] = Map.empty,
    timeout: Int = 10000
  ): HttpResponse

  def post(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: Int = 10000
  ): HttpResponse

  def postBytes(
    url: String,
    headers: Map[String, String] = Map.empty,
    data: Array[Byte] = Array.empty,
    timeout: Int = 10000
  ): HttpResponse

  def postMultipart(
    url: String,
    headers: Map[String, String] = Map.empty,
    parts: Seq[MultipartPart] = Seq.empty,
    timeout: Int = 10000
  ): HttpResponse

  def put(
    url: String,
    headers: Map[String, String] = Map.empty,
    body: String = "",
    timeout: Int = 10000
  ): HttpResponse

  def delete(
    url: String,
    headers: Map[String, String] = Map.empty,
    timeout: Int = 10000
  ): HttpResponse

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
    timeout: Int = 10000
  ): HttpRawResponse
}

object Llm4sHttpClient {

  /** Creates the default JDK-backed HTTP client. */
  def create(): Llm4sHttpClient = new JdkHttpClient()
}

/**
 * JDK 11+ `java.net.http.HttpClient` implementation of [[Llm4sHttpClient]].
 *
 * Uses a single shared `HttpClient` instance for connection pooling.
 * Never throws on non-2xx responses — the caller is responsible for
 * checking `HttpResponse.statusCode`.
 */
private[llm4s] class JdkHttpClient extends Llm4sHttpClient {
  private val client = JHttpClient.newHttpClient()

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: Int
  ): HttpResponse = {
    val fullUrl = appendQueryParams(url, params)
    val request = buildRequest(fullUrl, headers, timeout)
      .GET()
      .build()
    execute(request)
  }

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpResponse = {
    val request = buildRequest(url, headers, timeout)
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    execute(request)
  }

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: Int
  ): HttpResponse = {
    val request = buildRequest(url, headers, timeout)
      .POST(HttpRequest.BodyPublishers.ofByteArray(data))
      .build()
    execute(request)
  }

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: Int
  ): HttpResponse = {
    val boundary = UUID.randomUUID().toString
    val body     = buildMultipartBody(parts, boundary)

    val allHeaders = headers + ("Content-Type" -> s"multipart/form-data; boundary=$boundary")

    val request = buildRequest(url, allHeaders, timeout)
      .POST(HttpRequest.BodyPublishers.ofByteArray(body))
      .build()
    execute(request)
  }

  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpResponse = {
    val request = buildRequest(url, headers, timeout)
      .PUT(HttpRequest.BodyPublishers.ofString(body))
      .build()
    execute(request)
  }

  override def delete(
    url: String,
    headers: Map[String, String],
    timeout: Int
  ): HttpResponse = {
    val request = buildRequest(url, headers, timeout)
      .DELETE()
      .build()
    execute(request)
  }

  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpRawResponse = {
    val request = buildRequest(url, headers, timeout)
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val response = client.send(request, JHttpResponse.BodyHandlers.ofByteArray())
    HttpRawResponse(statusCode = response.statusCode(), body = response.body())
  }

  // ============================================================
  // Internal helpers
  // ============================================================

  private def buildRequest(
    url: String,
    headers: Map[String, String],
    timeout: Int
  ): HttpRequest.Builder = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMillis(timeout.toLong))

    headers.foreach { case (key, value) =>
      builder.header(key, value)
    }

    builder
  }

  private def execute(request: HttpRequest): HttpResponse = {
    val response = client.send(request, JHttpResponse.BodyHandlers.ofString())

    val headers = response
      .headers()
      .map()
      .asScala
      .map { case (key, values) => key.toLowerCase(java.util.Locale.ROOT) -> values.asScala.toSeq }
      .toMap

    HttpResponse(
      statusCode = response.statusCode(),
      body = response.body(),
      headers = headers
    )
  }

  private def appendQueryParams(url: String, params: Map[String, String]): String =
    if (params.isEmpty) url
    else {
      val separator = if (url.contains("?")) "&" else "?"
      val queryParts = params.map { case (k, v) =>
        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
      }
      url + separator + queryParts.mkString("&")
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
