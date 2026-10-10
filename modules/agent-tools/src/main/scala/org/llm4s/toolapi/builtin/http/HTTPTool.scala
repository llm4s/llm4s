package org.llm4s.toolapi.builtin.http

import org.llm4s.core.safety.UsingOps.using
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.llm4s.util.{ DurationRounding, Redaction }
import upickle.default._

import java.io.InputStream
import java.net.{ HttpURLConnection, SocketTimeoutException, URI }
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ Executors, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit, TimeoutException }
import scala.annotation.tailrec
import scala.concurrent.duration.{ Deadline, Duration, DurationInt, DurationLong, FiniteDuration }
import scala.concurrent.{ Await, ExecutionContext, Future }
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
  @upickle.implicits.key("responseTimeMs") responseTime: FiniteDuration
)

object HTTPResult {
  import org.llm4s.util.DurationJson.millisRW
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
 * - Configurable timeout, bounding the whole call (connect, every redirect hop and the body read)
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
        s" Timeout: ${config.timeout.toMillis}ms.",
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

  /**
   * Read a response body without ever holding more than `maxBytes + 1` bytes.
   *
   * A body that is longer than the cap is cut at the cap and reported as truncated; reading stops once the byte
   * after the cap has been seen, instead of reading the whole body first. The cap counts bytes (so an endless
   * response never has to fit in memory); a cut that falls inside a multi-byte character decodes it as U+FFFD.
   *
   * With a `deadline`, the deadline is checked before every read, and a body still arriving when it passes fails
   * with a `SocketTimeoutException`, so a server that sends a byte at a time cannot stretch the read past it.
   */
  private[http] def readBounded(
    in: InputStream,
    maxBytes: Long,
    deadline: Option[Deadline] = None
  ): (String, Boolean) = {
    val limit  = math.min(math.max(maxBytes, 0L), (Int.MaxValue - 9).toLong).toInt
    val wanted = limit + 1
    val out    = new java.io.ByteArrayOutputStream(math.min(wanted, ReadChunk))
    val buffer = new Array[Byte](math.min(wanted, ReadChunk))

    @tailrec
    def loop(total: Int): Unit =
      if (total < wanted) {
        if (deadline.exists(_.isOverdue())) throw new SocketTimeoutException("deadline reached while reading the body")
        val n = in.read(buffer, 0, math.min(buffer.length, wanted - total))
        if (n > 0) {
          out.write(buffer, 0, n)
          loop(total + n)
        } else if (n == 0) loop(total)
      }

    loop(0)
    val bytes    = out.toByteArray
    val isLonger = bytes.length > limit
    val kept     = if (isLonger) java.util.Arrays.copyOf(bytes, limit) else bytes
    (new String(kept, StandardCharsets.UTF_8), isLonger)
  }

  private val ReadChunk = 8192

  /**
   * Headers never sent to another origin, even when `HttpConfig.redirectSafeHeaders` lists them. Core's redaction
   * catches most other credential names (`X-Api-Key`, `X-Auth-Token`, `X-Client-Secret`, ...); these are the ones it
   * may not.
   */
  private val NeverForwarded: Set[String] =
    Set("authorization", "proxy-authorization", "cookie", "cookie2")

  /**
   * Header names ending in these (compared with `-` and `_` dropped) are credentials too: `Private-Token`,
   * `X-Amz-Security-Token`, `X-CSRF-Token`, `Ocp-Apim-Subscription-Key`. Redaction does not match a bare `token`
   * suffix, because a log is full of `max_tokens` and `next_page_token`; a request header named so is a credential.
   */
  private val CredentialHeaderSuffixes: Seq[String] = Seq("token", "key")

  private def headerKey(name: String): String = name.trim.toLowerCase(Locale.ROOT)

  /** Whether header `key` (from `headerKey`) can carry a credential, so that no allowlist may forward it. */
  private def isCredentialHeader(key: String): Boolean = {
    val compact = key.filter(_.isLetterOrDigit)
    NeverForwarded.contains(key) || Redaction.isSensitiveKey(key) || CredentialHeaderSuffixes.exists(compact.endsWith)
  }

  /**
   * The origin of a URL as RFC 6454 defines it: scheme, host and port, with the scheme's default port filled in, so
   * `http://a.example` and `http://a.example:80` are the same origin and `https://a.example` is not.
   */
  final private[http] case class Origin(scheme: String, host: String, port: Int)

  private[http] object Origin {
    def of(url: java.net.URL): Origin = {
      val port = if (url.getPort == -1) url.getDefaultPort else url.getPort
      Origin(url.getProtocol.toLowerCase(Locale.ROOT), Option(url.getHost).getOrElse("").toLowerCase(Locale.ROOT), port)
    }
  }

  /**
   * The headers to send on a hop to `hop`, and whether caller-set headers are stripped from here on.
   *
   * On a hop that stays on `initial` (the same scheme, host and port) every header is sent. Once any hop has left it
   * - which includes a downgrade from `https` to `http`, and an upgrade - only the headers named in `safe` (in any
   * case) are sent, and never one that names a credential: `Authorization`, `Proxy-Authorization`, `Cookie`, any
   * name `Redaction` treats as sensitive, or one ending in `token` or `key`. `Content-Type` is sent only with a body
   * (`sendsBody`). Stripping is sticky: it holds for every later hop, including one that comes back to the original
   * origin.
   */
  private[http] def headersForHop(
    headers: Option[Map[String, String]],
    initial: Origin,
    hop: Origin,
    alreadyStripped: Boolean,
    safe: Seq[String] = HttpConfig.DefaultRedirectSafeHeaders,
    sendsBody: Boolean = false
  ): (Option[Map[String, String]], Boolean) = {
    val strip = alreadyStripped || hop != initial
    val sent =
      if (strip) {
        val allowed = safe.map(headerKey).toSet
        headers.map(_.filter { case (k, _) =>
          val key = headerKey(k)
          allowed.contains(key) && !isCredentialHeader(key) && (sendsBody || key != "content-type")
        })
      } else headers
    (sent, strip)
  }

  /** A daemon thread factory, so that the tool's threads never keep a JVM alive. */
  private def daemonThreads(name: String): ThreadFactory = {
    val count = new AtomicLong(0)
    (r: Runnable) => {
      val t = new Thread(r, s"$name-${count.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
  }

  /**
   * Runs each call, so that the caller can stop waiting at the deadline whatever the connection is doing. A call
   * abandoned at the deadline ends on its own soon after: the watchdog below and the deadline checks in the body
   * read stop it, and no single blocking read can last longer than the read timeout, itself no longer than the
   * whole call's timeout.
   */
  private lazy val callThreads: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(daemonThreads("llm4s-http-tool")))

  /**
   * Disconnects a connection still open at the call's deadline. Up to the response headers this closes the socket,
   * so a server that drips its status line or headers a byte at a time cannot keep the abandoned call's thread busy.
   * (Once the body is being read, `HttpURLConnection.disconnect` waits for the read to return, so [[readBounded]]
   * checks the deadline itself, and the disconnect runs on a call thread rather than on the watchdog's.)
   */
  private lazy val watchdog: ScheduledThreadPoolExecutor = {
    val executor = new ScheduledThreadPoolExecutor(1, daemonThreads("llm4s-http-tool-deadline"))
    executor.setRemoveOnCancelPolicy(true)
    executor
  }

  private def timeoutMessage(config: HttpConfig): String =
    s"TIMEOUT: HTTP request did not complete within ${DurationRounding.ceilMillis(config.timeout)} ms " +
      "(connect, redirects and body read)"

  /**
   * The longest timeout honoured. `Deadline.now + d` throws once the sum passes `Long.MaxValue` nanoseconds, so a
   * larger timeout (up to `FiniteDuration`'s own limit of about 292 years) is treated as this one, which is still
   * effectively "never".
   */
  private val MaxTimeout: FiniteDuration = 36500.days

  /** `timeout` from now, capped so that building the deadline can never overflow. */
  private[http] def deadlineAfter(timeout: FiniteDuration): Deadline = {
    val now  = Deadline.now
    val room = (Long.MaxValue - math.max(now.time.toNanos, 0L)).nanos
    now + timeout.min(MaxTimeout).min(room)
  }

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
      if (config.timeout <= Duration.Zero)
        Left(s"TIMEOUT: HttpConfig.timeout must be positive (got ${config.timeout}), so no request was sent")
      else {
        // One deadline for the whole call: name resolution, every connect, every redirect hop and every body read.
        val deadline = deadlineAfter(config.timeout)
        val call = Future(followRedirects(urlStr, method, headers, body, contentType, config, deadline))(callThreads)
        Try(Await.result(call, deadline.timeLeft)).toEither.left.map {
          case _: TimeoutException => timeoutMessage(config)
          case e                   => s"HTTP request failed: ${e.getMessage}"
        }.flatten
      }
    }

  /** The request and every redirect hop it is allowed to follow, within `deadline`. */
  private def followRedirects(
    urlStr: String,
    method: String,
    headers: Option[Map[String, String]],
    body: Option[String],
    contentType: Option[String],
    config: HttpConfig,
    deadline: Deadline
  ): Either[String, HTTPResult] = {

    /**
     * Recursively follow redirects with per-hop SSRF validation.
     *
     * For every hop we:
     *  1. Parse and validate the URL
     *  2. Check the destination domain/IP against the SSRF filter
     *  3. Execute the request with auto-redirects disabled, within what is left of the call's deadline
     *  4. If the response is 3xx and we still have hops left, extract
     *     the `Location` header, resolve it to an absolute URL, and loop.
     *
     * Security measures applied on each redirect:
     *  - From the first hop that leaves the original origin (scheme, host and port) and on every hop after it,
     *    only the caller-set headers on `HttpConfig.redirectSafeHeaders` are sent, never a credential header
     *  - 301/302 convert POST→GET and drop the request body (per HTTP spec), and with it any `Content-Type`
     *  - 307/308 preserve the original method and body
     */
    def go(
      currentUrlStr: String,
      currentMethod: String,
      currentBody: Option[String],
      initialOrigin: Option[Origin],
      stripped: Boolean,
      hopsLeft: Int
    ): Either[String, HTTPResult] =
      Try(URI.create(currentUrlStr).toURL).toEither.left
        .map(e => s"Invalid URL: ${e.getMessage}")
        .flatMap { url =>
          val scheme = url.getProtocol.toLowerCase(Locale.ROOT)
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
            else if (deadline.isOverdue())
              Left(timeoutMessage(config))
            else {
              val hopOrigin = Origin.of(url)
              val origin    = initialOrigin.getOrElse(hopOrigin)
              val (hopHeaders, nowOff) =
                headersForHop(headers, origin, hopOrigin, stripped, config.redirectSafeHeaders, currentBody.isDefined)
              // A Content-Type describes the body: a hop whose body a 301/302 dropped sends none, whatever its
              // origin, neither a caller-set header nor the tool's own content_type. A request that never had a
              // body keeps its Content-Type on a same-origin hop; a cross-origin hop sends one only with a body.
              val bodyDropped = body.isDefined && currentBody.isEmpty
              val safeHeaders =
                if (bodyDropped) hopHeaders.map(_.filter { case (k, _) => headerKey(k) != "content-type" })
                else hopHeaders
              val hopContentType = if (bodyDropped || (nowOff && currentBody.isEmpty)) None else contentType

              executeRequest(
                url,
                currentUrlStr,
                currentMethod,
                safeHeaders,
                currentBody,
                hopContentType,
                config,
                deadline
              )
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
                            ) && currentMethod.toUpperCase(Locale.ROOT) != "GET" &&
                            currentMethod.toUpperCase(Locale.ROOT) != "HEAD"
                          )
                            ("GET", None)
                          else
                            (currentMethod, currentBody)

                        go(absoluteLocation, nextMethod, nextBody, Some(origin), nowOff, hopsLeft - 1)
                    }
                  } else {
                    Right(result)
                  }
                }
            }
          }
        }

    go(urlStr, method, body, initialOrigin = None, stripped = false, config.maxRedirects)
  }

  private def executeRequest(
    url: java.net.URL,
    urlStr: String,
    method: String,
    headers: Option[Map[String, String]],
    body: Option[String],
    contentType: Option[String],
    config: HttpConfig,
    deadline: Deadline
  ): Either[String, HTTPResult] = {
    val startTime  = System.currentTimeMillis()
    val connection = Try(url.openConnection().asInstanceOf[HttpURLConnection])
    // No single blocking read or connect may outlast what is left of the call; the watchdog closes a connection still
    // waiting for its response at the deadline.
    val remaining = deadline.timeLeft
    val guard = connection.toOption.map { c =>
      // The disconnect runs on a call thread: it can wait for a body read, which must not stall the watchdog.
      val disconnect: Runnable = () => callThreads.execute(() => c.disconnect())
      watchdog.schedule(disconnect, math.max(remaining.toNanos, 0L), TimeUnit.NANOSECONDS)
    }

    val outcome = connection.flatMap { connection =>
      Try {
        // Configure connection
        connection.setRequestMethod(method.toUpperCase(Locale.ROOT))
        // HttpURLConnection reads 0 as "no timeout": never pass it one.
        val timeoutMillis = math.max(DurationRounding.ceilMillisInt(remaining), 1)
        connection.setConnectTimeout(timeoutMillis)
        connection.setReadTimeout(timeoutMillis)
        // Auto-redirects are always disabled; the makeRequest loop handles
        // redirect following with per-hop SSRF validation (Issue #788).
        connection.setInstanceFollowRedirects(false)
        connection.setRequestProperty("User-Agent", config.userAgent)

        // Set headers
        headers.foreach(h => h.foreach { case (k, v) => connection.setRequestProperty(k, v) })

        // Set content type for requests with body
        val effectiveContentType = contentType.orElse(
          if (Seq("POST", "PUT", "PATCH").contains(method.toUpperCase(Locale.ROOT))) Some("application/json")
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

        // Read at most maxResponseSize bytes (plus one, to detect a longer body) so that an oversized or endless
        // response never has to fit in memory, then decode. A cut that falls inside a multi-byte character decodes it
        // as U+FFFD. A body over the cap also drops the connection, so the server is not left sending the rest.
        val (responseBody, truncated) = using(inputStream) { is =>
          val bounded = readBounded(is, config.maxResponseSize, Some(deadline))
          if (bounded._2) connection.disconnect()
          bounded
        }

        connection.disconnect()
        // A response finished past the deadline is reported as a timeout, never as a possibly cut-short body.
        if (deadline.isOverdue()) throw new SocketTimeoutException("deadline reached")
        val endTime = System.currentTimeMillis()

        HTTPResult(
          url = urlStr,
          method = method.toUpperCase(Locale.ROOT),
          statusCode = statusCode,
          statusMessage = statusMessage,
          headers = responseHeaders,
          body = responseBody,
          contentType = responseContentType,
          contentLength = responseContentLength,
          truncated = truncated,
          responseTime = (endTime - startTime).millis
        )
      }
    }
    guard.foreach(_.cancel(false))

    outcome.toEither.left.map { e =>
      if (deadline.isOverdue()) timeoutMessage(config) else s"HTTP request failed: ${e.getMessage}"
    }
  }
}
