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

  /** Headers that must be stripped when a redirect crosses to a different host. */
  private val SensitiveHeaders: Set[String] =
    Set("authorization", "cookie", "proxy-authorization")

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

      /**
       * Recursively follow redirects with per-hop SSRF validation.
       *
       * For every hop we:
       *  1. Parse and validate the URL
       *  2. Check the destination domain/IP against the SSRF filter
       *  3. Execute the request with auto-redirects disabled
       *  4. If the response is 3xx and we still have hops left, extract
       *     the `Location` header, resolve it to an absolute URL, and loop.
       *
       * Security measures applied on each redirect:
       *  - Sensitive headers (Authorization, Cookie) are stripped on cross-origin hops
       *  - 301/302 convert POST→GET and drop the request body (per HTTP spec)
       *  - 307/308 preserve the original method and body
       */
      def go(
        currentUrlStr: String,
        currentMethod: String,
        currentHeaders: Option[Map[String, String]],
        currentBody: Option[String],
        previousHost: Option[String],
        hopsLeft: Int
      ): Either[String, HTTPResult] =
        Try(URI.create(currentUrlStr).toURL).toEither.left
          .map(e => s"Invalid URL: ${e.getMessage}")
          .flatMap { url =>
            val scheme = url.getProtocol.toLowerCase
            if (scheme != "http" && scheme != "https")
              Left(
                s"UNSUPPORTED_PROTOCOL: Only http and https are allowed (got: '$scheme')"
              )
            else {
              val domain = Option(url.getHost).getOrElse("")
              if (domain.isEmpty)
                Left("URL has no host")
              else if (!config.validateDomainWithSSRF(domain))
                Left(s"SSRF_BLOCKED: domain '$domain' is not allowed")
              else {
                // Strip sensitive headers when the redirect crosses to a different host.
                val safeHeaders = previousHost match {
                  case Some(prevHost) if !prevHost.equalsIgnoreCase(domain) =>
                    currentHeaders.map(_.filterNot { case (k, _) =>
                      SensitiveHeaders.contains(k.toLowerCase)
                    })
                  case _ => currentHeaders
                }

                executeRequest(url, currentUrlStr, currentMethod, safeHeaders, currentBody, contentType, config)
                  .flatMap { result =>
                    val isRedirect =
                      Set(301, 302, 307, 308).contains(result.statusCode)
                    if (config.followRedirects && isRedirect) {
                      val locationOpt =
                        result.headers
                          .find { case (k, _) => k.equalsIgnoreCase("Location") }
                          .map(_._2)
                      locationOpt match {
                        case None =>
                          Right(result)
                        case Some(_) if hopsLeft <= 0 =>
                          Left(
                            s"TOO_MANY_REDIRECTS: Too many redirects (max ${config.maxRedirects})"
                          )
                        case Some(location) =>
                          val absoluteLocation =
                            Try(url.toURI.resolve(location).toString).getOrElse(location)

                          // Per HTTP spec: 301/302 convert POST→GET and drop the body.
                          // 307/308 preserve the original method and body.
                          val (nextMethod, nextBody) =
                            if (
                              Set(301, 302).contains(
                                result.statusCode
                              ) && currentMethod.toUpperCase != "GET" && currentMethod.toUpperCase != "HEAD"
                            )
                              ("GET", None)
                            else
                              (currentMethod, currentBody)

                          go(absoluteLocation, nextMethod, currentHeaders, nextBody, Some(domain), hopsLeft - 1)
                      }
                    } else {
                      Right(result)
                    }
                  }
              }
            }
          }

      go(urlStr, method, headers, body, previousHost = None, config.maxRedirects)
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
      // Auto-redirects are always disabled; the makeRequest loop handles
      // redirect following with per-hop SSRF validation (Issue #788).
      connection.setInstanceFollowRedirects(false)
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
