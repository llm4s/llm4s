package org.llm4s.rag.loader

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.NetworkError

import java.net.{ InetAddress, InetSocketAddress }
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/**
 * An HTTP server on a loopback port for the loaders' fetch tests. No external network.
 *
 * Each path answers with its [[LoopbackHttpServer.Reply]] (404 otherwise) and every request is recorded.
 */
final class LoopbackHttpServer(routes: Map[String, LoopbackHttpServer.Reply]) extends AutoCloseable {
  import LoopbackHttpServer.*

  private val seen   = new CopyOnWriteArrayList[Seen]()
  private val server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)

  server.createContext(
    "/",
    (ex: HttpExchange) => {
      val headers = ex.getRequestHeaders
      seen.add(
        Seen(
          ex.getRequestURI.toString,
          Option(headers.getFirst("Host")),
          Option(headers.getFirst("User-Agent")),
          headers.asScala.map { case (k, v) => k.toLowerCase -> v.asScala.mkString(",") }.toMap
        )
      )
      val reply = routes.getOrElse(ex.getRequestURI.getPath, Reply(404, "not found"))
      reply.headers.foreach { case (k, v) => ex.getResponseHeaders.add(k, v) }
      val bytes = reply.body.getBytes("UTF-8")
      // 0 makes HttpServer send a chunked body; -1 sends none
      val length = if (reply.chunked) 0L else if (bytes.isEmpty) -1L else bytes.length.toLong
      ex.sendResponseHeaders(reply.status, length)
      if (bytes.nonEmpty) ex.getResponseBody.write(bytes)
      ex.close()
    }
  )
  server.start()

  val port: Int                 = server.getAddress.getPort
  val baseUrl: String           = s"http://127.0.0.1:$port"
  def requests: Seq[Seen]       = seen.asScala.toSeq
  def paths: Seq[String]        = requests.map(_.uri)
  override def close(): Unit    = server.stop(0)
  def url(host: String): String = s"http://$host:$port"
}

object LoopbackHttpServer {

  final case class Reply(
    status: Int,
    body: String,
    headers: Map[String, String] = Map.empty,
    chunked: Boolean = false
  )

  final case class Seen(uri: String, host: Option[String], userAgent: Option[String], headers: Map[String, String])

  def html(body: String): Reply = Reply(200, body, Map("Content-Type" -> "text/html; charset=utf-8"))

  def redirect(location: String, status: Int = 302): Reply = Reply(status, "", Map("Location" -> location))

  def withServer[A](routes: (String, Reply)*)(f: LoopbackHttpServer => A): A = {
    val server = new LoopbackHttpServer(routes.toMap)
    try f(server)
    finally server.close()
  }

  /** A policy that allows loopback addresses (the test servers) but is otherwise the default. */
  val loopbackPolicy: UrlPolicy.Standard = UrlPolicy.default.withAllowPrivateNetworks(true)

  /**
   * Resolves every name in `names` to the loopback address and nothing else, as a stand-in DNS: a request to
   * `http://name:port/` can only arrive if the loader connects to the address the policy returned.
   */
  def fakeDns(names: String*): String => Either[NetworkError, Seq[InetAddress]] = host =>
    if (names.contains(host)) Right(Seq(InetAddress.getLoopbackAddress))
    else Left(NetworkError(s"DNS resolution failed for '$host'", None, "ssrf-protection"))
}
