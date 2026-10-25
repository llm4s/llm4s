package org.llm4s.testutil

import com.sun.net.httpserver.{ HttpsConfigurator, HttpsParameters, HttpsServer }

import java.io.{ BufferedInputStream, ByteArrayOutputStream, InputStream }
import java.net.{ InetAddress, InetSocketAddress, ServerSocket, Socket }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, StandardCopyOption }
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * One request a [[RecordingProxy]] received.
 *
 * @param method  the HTTP method
 * @param target  the request target as sent: an absolute URL when the client sent it to a proxy, a path when it
 *                called the stub as a server
 * @param headers the headers, names in lower case
 * @param body    the body, as text
 */
final case class RecordedRequest(method: String, target: String, headers: Map[String, String], body: String):
  def header(name: String): Option[String] = headers.get(name.toLowerCase)

/**
 * A local HTTP proxy for specs (#2273): it records every request it receives and answers each itself with a canned
 * reply rather than forwarding it, so a spec can prove a client's call reached the proxy without any network. It also
 * serves as a plain HTTP stub: a client that bypasses a proxy calls it directly, and the recorded target is then a
 * path rather than an absolute URL.
 *
 * With `credentials` set it acts as an authenticating proxy: a request without the matching
 * `Proxy-Authorization: Basic` header gets `407` with a `Proxy-Authenticate: Basic` challenge, which is recorded too.
 * Every reply closes its connection.
 */
final class RecordingProxy private (
  server: ServerSocket,
  credentials: Option[(String, String)],
  status: Int,
  body: String,
  contentType: String
) extends AutoCloseable:

  private val received = new ConcurrentLinkedQueue[RecordedRequest]()
  private val closed   = new AtomicBoolean(false)

  /** The port it listens on, on the loopback address. */
  val port: Int = server.getLocalPort

  /** `http://127.0.0.1:<port>`: the proxy URL to configure, or the base URL of the stub. */
  val url: String = s"http://127.0.0.1:$port"

  /** Every request received so far, in order. */
  def requests: Vector[RecordedRequest] = received.asScala.toVector

  /** The requests that carried the right credentials, or every request when none are required. */
  def authorized: Vector[RecordedRequest] = requests.filter(isAuthorized)

  private val acceptor = Thread.ofVirtual().name("recording-proxy").start(() => acceptLoop())

  private def acceptLoop(): Unit =
    while !closed.get() do
      Try(server.accept()).toOption.foreach(socket => Thread.ofVirtual().start(() => serve(socket)): Unit)

  private def serve(socket: Socket): Unit =
    Using.resource(socket) { s =>
      val in = new BufferedInputStream(s.getInputStream)
      readRequest(in).foreach { request =>
        received.add(request)
        val reply =
          if isAuthorized(request) then
            val bytes = body.getBytes(StandardCharsets.UTF_8)
            head(s"$status ${reason(status)}", Seq("Content-Type" -> contentType), bytes.length) ++ bytes
          else
            head(
              "407 Proxy Authentication Required",
              Seq("Proxy-Authenticate" -> "Basic realm=\"llm4s-test\""),
              0
            )
        s.getOutputStream.write(reply)
        s.getOutputStream.flush()
      }
    }

  private def isAuthorized(request: RecordedRequest): Boolean =
    credentials.forall { case (user, password) =>
      val expected =
        "Basic " + Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8))
      request.header("proxy-authorization").contains(expected)
    }

  private def head(statusLine: String, extra: Seq[(String, String)], length: Int): Array[Byte] =
    val lines = s"HTTP/1.1 $statusLine" +: (extra.map { case (k, v) => s"$k: $v" } ++ Seq(
      s"Content-Length: $length",
      "Connection: close"
    ))
    (lines.mkString("\r\n") + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)

  private def reason(code: Int): String = if code < 300 then "OK" else "Error"

  private def readLine(in: InputStream): Option[String] =
    val buffer = new ByteArrayOutputStream()
    var next   = in.read()
    while next != -1 && next != '\n' do
      if next != '\r' then buffer.write(next)
      next = in.read()
    if next == -1 && buffer.size() == 0 then None else Some(buffer.toString(StandardCharsets.UTF_8))

  private def readRequest(in: InputStream): Option[RecordedRequest] =
    readLine(in).filter(_.nonEmpty).map { requestLine =>
      val parts   = requestLine.split(" ")
      val headers = Iterator.continually(readLine(in)).takeWhile(_.exists(_.nonEmpty)).flatten.toVector
      val headerMap = headers.flatMap { line =>
        line.split(":", 2) match
          case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
          case _           => None
      }.toMap
      val content =
        if headerMap.get("transfer-encoding").exists(_.equalsIgnoreCase("chunked")) then readChunked(in)
        else in.readNBytes(headerMap.get("content-length").flatMap(_.toIntOption).getOrElse(0))
      RecordedRequest(parts(0), parts.lift(1).getOrElse(""), headerMap, new String(content, StandardCharsets.UTF_8))
    }

  private def readChunked(in: InputStream): Array[Byte] =
    val out = new ByteArrayOutputStream()
    Iterator
      .continually(readLine(in).map(_.takeWhile(_ != ';').trim).flatMap(h => Try(Integer.parseInt(h, 16)).toOption))
      .takeWhile(_.exists(_ > 0))
      .flatten
      .foreach { size =>
        out.write(in.readNBytes(size))
        readLine(in): Unit
      }
    readLine(in): Unit
    out.toByteArray

  override def close(): Unit =
    closed.set(true)
    Try(server.close()): Unit
    acceptor.join(2000): Unit

object RecordingProxy:

  /**
   * Starts a recording proxy on an ephemeral loopback port.
   *
   * @param credentials the Basic credentials it requires, or `None` to accept every request
   * @param status      the status of its reply to an accepted request
   * @param body        the body of that reply
   * @param contentType the reply's content type
   */
  def start(
    credentials: Option[(String, String)] = None,
    status: Int = 200,
    body: String = "{}",
    contentType: String = "application/json"
  ): RecordingProxy =
    new RecordingProxy(
      new ServerSocket(0, 50, InetAddress.getLoopbackAddress),
      credentials,
      status,
      body,
      contentType
    )

/**
 * A test certificate authority and the stores it signed (#2273), in `core`'s test resources: `trust.p12` holds the CA
 * certificate, `server.p12` a `localhost` / `127.0.0.1` server key and certificate, `client.p12` a client key and
 * certificate for mutual TLS. Every store's password is [[Password]]; the certificates are valid for a century.
 */
object TestTls:

  val Password: String = "changeit"

  /** A copy of the store `name` in a temporary file, which is removed when the JVM exits. */
  def store(name: String): Path =
    val target = Files.createTempFile(s"llm4s-$name-", ".p12")
    target.toFile.deleteOnExit()
    Using.resource(getClass.getResourceAsStream(s"/tls/$name.p12"))(in =>
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)
    ): Unit
    target

  /** The trust store holding the test CA. */
  def trustStore: Path = store("trust")

  /** The client key store, for mutual TLS. */
  def clientStore: Path = store("client")

  /**
   * An HTTPS stub on an ephemeral loopback port, its certificate signed by the test CA, answering every request with
   * `200` and `body`. With `requireClientCertificate` it also demands a client certificate signed by that CA.
   */
  def httpsServer(body: String = "{}", requireClientCertificate: Boolean = false): HttpsServer =
    val keyStore = KeyStore.getInstance(store("server").toFile, Password.toCharArray)
    val keys     = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keys.init(keyStore, Password.toCharArray)
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(KeyStore.getInstance(trustStore.toFile, Password.toCharArray))
    val context = SSLContext.getInstance("TLS")
    context.init(keys.getKeyManagers, trust.getTrustManagers, null)
    val server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.setHttpsConfigurator(new HttpsConfigurator(context) {
      override def configure(params: HttpsParameters): Unit =
        val ssl = context.getDefaultSSLParameters
        ssl.setNeedClientAuth(requireClientCertificate)
        params.setSSLParameters(ssl)
    })
    server.createContext(
      "/",
      exchange => {
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        Using.resource(exchange.getResponseBody)(_.write(bytes))
        exchange.close()
      }
    )
    server.start()
    server
