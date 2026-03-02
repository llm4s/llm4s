package org.llm4s.toolapi.builtin.http

import org.llm4s.core.safety.UsingOps.using
import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.net.{ HttpURLConnection, URI }
import java.nio.charset.StandardCharsets
import scala.io.Source
import scala.util.Try

/**
 * HTTP response result.
 */
case class HTTPResult(
  url: String,
  method: String,
  statusCode: Int,
  statusMessage: String,
  headers: Map[String, String],
  body: String,
  contentType: Option[String],
  contentLength: Long,
  truncated: Boolean,
  responseTimeMs: Long
)

object HTTPResult {
  implicit val httpResultRW: ReadWriter[HTTPResult] = macroRW[HTTPResult]
}

/**
 * Tool for making HTTP requests.
 *
 * Features:
 * - Support for GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
 * - Request headers and body
 * - Domain allowlist/blocklist for security
 * - Response size limits
 * - Configurable timeout
 *
 * @example
 * {{{{
 * import org.llm4s.toolapi.builtin.http._
 *
 * val httpTool = HTTPTool.create(HttpConfig(
 *   allowedDomains = Some(Seq("api.example.com")),
 *   allowedMethods = Seq("GET", "POST")
 * ))
 *
 * val tools = new ToolRegistry(Seq(httpTool))
 * agent.run("Fetch data from https://api.example.com/data", tools)
 * }}}}
 */
object HTTPTool {

  private def createSchema = Schema
    .`object`[Map[String, Any]]("HTTP request parameters")
    .withProperty(
      Schema.property(
        "url",
        Schema.string("The URL to request (must include protocol, e.g., https://)")
      )
    )
    .withProperty(
      Schema.property(
        "method",
        Schema
          .string("HTTP method (default: GET)")
          .withEnum(Seq("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"))
      )
    )
    .withProperty(
      Schema.property(
        "headers",
        Schema.`object`[Map[String, String]]("Request headers as key-value pairs")
      )
    )
    .withProperty(
      Schema.property(
        "body",
        Schema.string("Request body (for POST, PUT, PATCH)")
      )
    )
    .withProperty(
      Schema.property(
        "content_type",
        Schema
          .string("Content-Type header (default: application/json for POST/PUT/PATCH)")
          .withEnum(Seq("application/json", "application/x-www-form-urlencoded", "text/plain", "application/xml"))
      )
    )

  /**
   * Create an HTTP tool with the given configuration, returning a Result for safe error handling.
   */
  def createSafe(config: HttpConfig = HttpConfig()): Result[ToolFunction[Map[String, Any], HTTPResult]] =
    ToolBuilder[Map[String, Any], HTTPResult](
      name = "http_request",
      description = s"Make HTTP requests to fetch or send data. " +
        s"Allowed methods: ${config.allowedMethods.mkString(", ")}. " +
        s"Blocked domains: ${config.blockedDomains.mkString(", ")}. " +
        config.allowedDomains
          .map(d => s"Allowed domains: ${d.mkString(", ")}")
          .getOrElse("All domains allowed (except blocked).") +
        s" Timeout: ${config.timeoutMs}ms.",
      schema = createSchema
    ).withHandler { extractor =>
      for {
        urlStr <- extractor.getString("url")
        method      = extractor.getString("method").fold(_ => "GET", identity)
        headersOpt  = extractHeaders(extractor)
        bodyOpt     = extractor.getString("body").toOption
        contentType = extractor.getString("content_type").toOption
        result <- makeRequest(urlStr, method, headersOpt, bodyOpt, contentType, config)
      } yield result
    }.buildSafe()

  private def extractHeaders(extractor: SafeParameterExtractor): Option[Map[String, String]] =
    extractor.getObject("headers").fold(_ => None, obj => Some(obj.value.collect { case (k, v) => k -> v.str }.toMap))

  /**
   * Default HTTP tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], HTTPResult]] = createSafe()

  private def makeRequest(
    urlStr: String,
    method: String,
    headers: Option[Map[String, String]],
    body: Option[String],
    contentType: Option[String],
    config: HttpConfig
  ): Either[String, HTTPResult] =
    // Validate method
    if (!config.isMethodAllowed(method)) {
      Left(s"HTTP method '$method' is not allowed. Allowed: ${config.allowedMethods.mkString(", ")}")
    } else {
      // Parse and validate URL
      val urlResult = Try(URI.create(urlStr).toURL).toEither.left.map(e => s"Invalid URL: ${e.getMessage}")

      urlResult.flatMap { url =>
        // Extract domain from URL
        val domain = Option(url.getHost).getOrElse("")

        if (domain.isEmpty) {
          Left("URL has no host")
        } else if (!config.validateDomainWithSSRF(domain)) {
          Left(s"Domain '$domain' is not allowed")
        } else {
          executeRequest(url, urlStr, method, headers, body, contentType, config)
        }
      }
    }

  private def executeRequest(
    url: java.net.URL,
    urlStr: String,
    method: String,
    headers: Option[Map[String, String]],
    body: Option[String],
    contentType: Option[String],
    config: HttpConfig
  ): Either[String, HTTPResult] = {
    val startTime = System.currentTimeMillis()

    Try {
      val connection = url.openConnection().asInstanceOf[HttpURLConnection]

      // Configure connection
      connection.setRequestMethod(method.toUpperCase)
      connection.setConnectTimeout(config.timeoutMs)
      connection.setReadTimeout(config.timeoutMs)
      connection.setInstanceFollowRedirects(config.followRedirects)
      connection.setRequestProperty("User-Agent", config.userAgent)

      // Set headers
      headers.foreach(h => h.foreach { case (k, v) => connection.setRequestProperty(k, v) })

      // Set content type for requests with body
      val effectiveContentType = contentType.orElse(
        if (Seq("POST", "PUT", "PATCH").contains(method.toUpperCase)) Some("application/json")
        else None
      )
      effectiveContentType.foreach(ct => connection.setRequestProperty("Content-Type", ct))

      // Send body if present
      body.foreach { b =>
        connection.setDoOutput(true)
        using(connection.getOutputStream) { outputStream =>
          outputStream.write(b.getBytes(StandardCharsets.UTF_8))
          outputStream.flush()
        }
      }

      // Get response
      val statusCode    = connection.getResponseCode
      val statusMessage = Option(connection.getResponseMessage).getOrElse("")

      // Get response headers
      val responseHeaders = (0 until 100).flatMap { i =>
        val key   = Option(connection.getHeaderFieldKey(i))
        val value = Option(connection.getHeaderField(i))
        for {
          k <- key
          v <- value
        } yield k -> v
      }.toMap

      val responseContentType   = Option(connection.getContentType)
      val responseContentLength = connection.getContentLengthLong

      // Read response body
      val inputStream = if (statusCode >= 400) {
        Option(connection.getErrorStream).getOrElse(connection.getInputStream)
      } else {
        connection.getInputStream
      }

      val (responseBody, truncated) = using(inputStream) { is =>
        using(Source.fromInputStream(is, "UTF-8")) { source =>
          val fullBody = source.mkString
          if (fullBody.length > config.maxResponseSize) {
            (fullBody.take(config.maxResponseSize.toInt), true)
          } else {
            (fullBody, false)
          }
        }
      }

      connection.disconnect()
      val endTime = System.currentTimeMillis()

      HTTPResult(
        url = urlStr,
        method = method.toUpperCase,
        statusCode = statusCode,
        statusMessage = statusMessage,
        headers = responseHeaders,
        body = responseBody,
        contentType = responseContentType,
        contentLength = responseContentLength,
        truncated = truncated,
        responseTimeMs = endTime - startTime
      )
    }.toEither.left.map(e => s"HTTP request failed: ${e.getMessage}")
  }
}
