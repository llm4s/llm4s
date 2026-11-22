package org.llm4s.rag.loader.internal

import org.llm4s.error.NetworkError
import org.llm4s.rag.loader.UrlPolicy
import org.llm4s.util.DurationRounding

import java.io.{ BufferedInputStream, ByteArrayOutputStream, InputStream }
import java.net.{ InetAddress, InetSocketAddress, Socket, URI }
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.{ SNIHostName, SSLContext, SSLSocket }
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success, Try, Using }

/**
 * A minimal HTTP/1.1 `GET` client that connects only to the addresses a [[UrlPolicy]] approved.
 *
 * `HttpURLConnection` and `java.net.http.HttpClient` resolve the host again when they connect, so a host whose DNS
 * answer changes after the check (DNS rebinding) could reach an address the policy refused, and neither lets a
 * caller choose the address per request. This client asks the policy, opens the socket to an approved address
 * itself, and sends the original `Host` header; for `https` it runs TLS over that socket with the original host as
 * the SNI server name and the certificate check (`HTTPS` endpoint identification), as a browser would. Redirects are
 * followed here, each hop asking the policy again. It speaks `Connection: close` and `identity` encoding, decodes a
 * chunked or `Content-Length` body, and reads the body as UTF-8. It uses no proxy.
 */
private[loader] object PinnedHttpClient {

  /** A response: the status, the reason phrase, headers by lower-case name (first value wins) and the body. */
  final case class Response(status: Int, reason: String, headers: Map[String, String], body: String, url: String) {
    def header(name: String): Option[String] = headers.get(name.toLowerCase(Locale.ROOT))
  }

  val DefaultMaxRedirects: Int = 5

  /**
   * `GET url`, following up to `maxRedirects` redirects, each checked by `policy` before it is requested.
   *
   * @return the first response that is not a redirect, or why there is none
   */
  def get(
    url: String,
    headers: Seq[(String, String)],
    timeout: FiniteDuration,
    policy: UrlPolicy,
    maxRedirects: Int = DefaultMaxRedirects
  ): Either[NetworkError, Response] = {

    @tailrec
    def loop(current: URI, redirectsLeft: Int, first: Boolean): Either[NetworkError, Response] =
      policy.resolve(current) match {
        case Left(err) if first => Left(err)
        case Left(err) =>
          Left(NetworkError(s"Redirect blocked by SSRF protection: ${err.message}", None, "ssrf-protection"))
        case Right(addresses) =>
          request(current, addresses, headers, timeout) match {
            case Left(err) => Left(err)
            case Right(resp) if resp.status >= 300 && resp.status < 400 =>
              resp.header("Location") match {
                case None => Left(http(s"HTTP ${resp.status} redirect without Location header"))
                case Some(_) if redirectsLeft <= 0 => Left(http("Too many redirects"))
                case Some(location) =>
                  Try(current.resolve(location.trim)) match {
                    case Failure(e)    => Left(http(s"Invalid redirect location '$location': ${e.getMessage}"))
                    case Success(next) => loop(next, redirectsLeft - 1, first = false)
                  }
              }
            case Right(resp) => Right(resp)
          }
      }

    Try(new URI(url)) match {
      case Failure(e)   => Left(NetworkError(s"Invalid URL: ${e.getMessage}", Some(e), "ssrf-protection"))
      case Success(uri) => loop(uri, maxRedirects, first = true)
    }
  }

  private def http(message: String, cause: Option[Throwable] = None): NetworkError =
    NetworkError(message, cause, "http")

  /** One request to `uri`, tried at each address in turn until one accepts the connection. */
  private def request(
    uri: URI,
    addresses: Seq[InetAddress],
    headers: Seq[(String, String)],
    timeout: FiniteDuration
  ): Either[NetworkError, Response] = {
    val scheme = uri.getScheme.toLowerCase(Locale.ROOT)
    val host   = uri.getHost.stripPrefix("[").stripSuffix("]")
    val port   = if (uri.getPort == -1) if (scheme == "https") 443 else 80 else uri.getPort
    val millis = DurationRounding.ceilMillisInt(timeout)

    for {
      _      <- validateHeaders(headers)
      socket <- connect(addresses, port, millis)
      resp <- Using(socket) { plain =>
        val s = if (scheme == "https") startTls(plain, host, port) else plain
        exchange(s, requestBytes(uri, host, port, scheme, headers), uri.toString)
      }.flatten.toEither.left.map(e => http(Option(e.getMessage).getOrElse(e.toString), Some(e)))
    } yield resp
  }

  private def validateHeaders(headers: Seq[(String, String)]): Either[NetworkError, Unit] =
    headers.collectFirst {
      case (k, v)
          if k.isEmpty || k.exists(c => c <= ' ' || c == ':' || c >= 127) || v.exists(c => c == '\r' || c == '\n') =>
        k
    } match {
      case Some(name) => Left(http(s"Invalid HTTP header '$name'"))
      case None       => Right(())
    }

  private def connect(addresses: Seq[InetAddress], port: Int, millis: Int): Either[NetworkError, Socket] = {
    @tailrec
    def attempt(rest: List[InetAddress], last: Option[Throwable]): Either[NetworkError, Socket] =
      rest match {
        case Nil =>
          Left(http(s"Connection failed: ${last.flatMap(e => Option(e.getMessage)).getOrElse("no address")}", last))
        case address :: tail =>
          val socket = new Socket()
          Try {
            socket.connect(new InetSocketAddress(address, port), millis)
            socket.setSoTimeout(millis)
          } match {
            case Success(_) => Right(socket)
            case Failure(e) =>
              socket.close()
              attempt(tail, Some(e))
          }
      }
    attempt(addresses.toList, None)
  }

  /** TLS over the connected socket, verifying the certificate against `host` (not the address) and sending its SNI. */
  private def startTls(plain: Socket, host: String, port: Int): Socket = {
    val tls    = SSLContext.getDefault.getSocketFactory.createSocket(plain, host, port, true).asInstanceOf[SSLSocket]
    val params = tls.getSSLParameters
    params.setEndpointIdentificationAlgorithm("HTTPS")
    if (!isIpLiteral(host)) params.setServerNames(java.util.List.of(new SNIHostName(host)))
    tls.setSSLParameters(params)
    tls.startHandshake()
    tls
  }

  private def isIpLiteral(host: String): Boolean =
    host.contains(':') || host.forall(c => c.isDigit || c == '.')

  private def requestBytes(
    uri: URI,
    host: String,
    port: Int,
    scheme: String,
    headers: Seq[(String, String)]
  ): Array[Byte] = {
    val path      = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    val target    = Option(uri.getRawQuery).fold(path)(q => s"$path?$q")
    val hostName  = if (host.contains(':')) s"[$host]" else host
    val default   = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
    val hostValue = if (default) hostName else s"$hostName:$port"
    val fixed     = Set("host", "connection", "accept-encoding", "content-length", "transfer-encoding")
    val caller    = headers.filterNot { case (k, _) => fixed.contains(k.toLowerCase(Locale.ROOT)) }
    val lines =
      Seq(s"GET $target HTTP/1.1", s"Host: $hostValue") ++
        caller.map { case (k, v) => s"$k: $v" } ++
        Seq("Accept-Encoding: identity", "Connection: close")
    lines.mkString("", "\r\n", "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)
  }

  private def exchange(socket: Socket, request: Array[Byte], url: String): Try[Response] = Try {
    val out = socket.getOutputStream
    out.write(request)
    out.flush()
    readResponse(new BufferedInputStream(socket.getInputStream), url)
  }

  private def readResponse(in: InputStream, url: String): Response = {
    val (status, reason, headers) = readHead(in)
    val bodyBytes =
      if (headers.get("transfer-encoding").exists(_.toLowerCase(Locale.ROOT).contains("chunked"))) readChunked(in)
      else
        headers.get("content-length").flatMap(_.trim.toLongOption) match {
          case Some(n) => readExactly(in, n)
          case None    => in.readAllBytes()
        }
    Response(status, reason, headers, new String(bodyBytes, StandardCharsets.UTF_8), url)
  }

  /** The status line and headers, skipping any interim `1xx` response. */
  @tailrec
  private def readHead(in: InputStream): (Int, String, Map[String, String]) = {
    val statusLine = readLine(in)
    val parts      = statusLine.split(" ", 3)
    val status = parts.lift(1).flatMap(_.toIntOption).filter(_ => parts(0).startsWith("HTTP/")) match {
      case Some(code) => code
      case None       => throw new java.io.IOException(s"Malformed HTTP status line: '$statusLine'")
    }
    val headers = Iterator
      .continually(readLine(in))
      .takeWhile(_.nonEmpty)
      .flatMap { line =>
        line.indexOf(':') match {
          case -1 => None
          case i  => Some(line.substring(0, i).trim.toLowerCase(Locale.ROOT) -> line.substring(i + 1).trim)
        }
      }
      .foldLeft(Map.empty[String, String]) { case (acc, (k, v)) => if (acc.contains(k)) acc else acc + (k -> v) }
    if (status >= 100 && status < 200) readHead(in) else (status, parts.lift(2).getOrElse(""), headers)
  }

  /** One CRLF- (or LF-) terminated line, as ISO-8859-1. */
  private def readLine(in: InputStream): String = {
    val buf = new ByteArrayOutputStream()
    @tailrec
    def loop(): Unit =
      in.read() match {
        case -1 if buf.size() == 0 => throw new java.io.EOFException("Connection closed before the response")
        case -1 | '\n'             => ()
        case b =>
          if (buf.size() > MaxLineLength) throw new java.io.IOException("HTTP header line too long")
          buf.write(b)
          loop()
      }
    loop()
    val line = buf.toString(StandardCharsets.ISO_8859_1)
    if (line.endsWith("\r")) line.dropRight(1) else line
  }

  private val MaxLineLength = 64 * 1024

  private def readExactly(in: InputStream, n: Long): Array[Byte] = {
    if (n > Int.MaxValue - 8) throw new java.io.IOException(s"Response body too large: $n bytes")
    val bytes = in.readNBytes(n.toInt)
    if (bytes.length < n) throw new java.io.EOFException(s"Response body ended after ${bytes.length} of $n bytes")
    bytes
  }

  private def readChunked(in: InputStream): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    @tailrec
    def loop(): Unit = {
      val sizeLine = readLine(in).takeWhile(_ != ';').trim
      val size = Try(java.lang.Long.parseLong(sizeLine, 16)).getOrElse(
        throw new java.io.IOException(s"Malformed chunk size: '$sizeLine'")
      )
      if (size == 0) {
        // Trailers, up to the blank line that ends the message (or the end of the stream)
        Iterator.continually(Try(readLine(in)).getOrElse("")).takeWhile(_.nonEmpty).foreach(_ => ())
      } else {
        out.write(readExactly(in, size))
        readLine(in) // the CRLF after the chunk
        loop()
      }
    }
    loop()
    out.toByteArray
  }
}
