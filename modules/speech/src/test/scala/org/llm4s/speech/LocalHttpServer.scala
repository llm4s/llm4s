package org.llm4s.speech

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.http._
import org.llm4s.types.Result

import java.net.{ InetAddress, InetSocketAddress }
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

/** One request as it arrived on the wire at [[LocalHttpServer]]. */
final case class WireRequest(method: String, target: String, headers: Map[String, String], body: Array[Byte]) {
  def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }
  def text: String                         = new String(body, "UTF-8")
}

/** A canned reply for [[LocalHttpServer]]. */
final case class WireReply(status: Int, body: Array[Byte], headers: Map[String, String] = Map.empty)

/**
 * A real HTTP server on a loopback port, so the cloud clients are exercised through the real
 * `Llm4sHttpClient` and the bytes that cross the socket can be inspected. No external network.
 */
final class LocalHttpServer(reply: WireRequest => WireReply) extends AutoCloseable {
  private val seen   = new CopyOnWriteArrayList[WireRequest]()
  private val server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)

  server.createContext(
    "/",
    (ex: HttpExchange) => {
      val headers = ex.getRequestHeaders.entrySet.asScala.map(e => e.getKey -> e.getValue.get(0)).toMap
      val req = WireRequest(ex.getRequestMethod, ex.getRequestURI.toString, headers, ex.getRequestBody.readAllBytes())
      seen.add(req)
      val r = reply(req)
      r.headers.foreach { case (k, v) => ex.getResponseHeaders.add(k, v) }
      ex.sendResponseHeaders(r.status, if (r.body.isEmpty) -1L else r.body.length.toLong)
      if (r.body.nonEmpty) ex.getResponseBody.write(r.body)
      ex.close()
    }
  )
  server.start()

  val baseUrl: String            = s"http://127.0.0.1:${server.getAddress.getPort}"
  def requests: Seq[WireRequest] = seen.asScala.toSeq
  def only: WireRequest = {
    require(seen.size == 1, s"expected 1 request, saw ${seen.size}")
    seen.get(0)
  }
  override def close(): Unit = server.stop(0)
}

object LocalHttpServer {
  def ok(body: Array[Byte]): WireRequest => WireReply = _ => WireReply(200, body)
  def json(status: Int, body: String): WireRequest => WireReply =
    _ => WireReply(status, body.getBytes("UTF-8"), Map("Content-Type" -> "application/json"))

  /** Runs `f` against a started server and always stops it. */
  def using[A](reply: WireRequest => WireReply)(f: LocalHttpServer => A): A = {
    val s = new LocalHttpServer(reply)
    try f(s)
    finally s.close()
  }
}

/** Wraps the real client and remembers the files it was asked to upload. */
final class RecordingHttpClient(delegate: Llm4sHttpClient = Llm4sHttpClient.create()) extends Llm4sHttpClient {
  @volatile var uploads: Seq[Path] = Seq.empty

  override def get(u: String, h: Map[String, String], p: Map[String, String], t: FiniteDuration): Result[HttpResponse] =
    delegate.get(u, h, p, t)
  override def post(u: String, h: Map[String, String], b: String, t: FiniteDuration): Result[HttpResponse] =
    delegate.post(u, h, b, t)
  override def postBytes(u: String, h: Map[String, String], d: Array[Byte], t: FiniteDuration): Result[HttpResponse] =
    delegate.postBytes(u, h, d, t)
  override def postMultipart(
    u: String,
    h: Map[String, String],
    parts: Seq[MultipartPart],
    t: FiniteDuration
  ): Result[HttpResponse] = {
    uploads = uploads ++ parts.collect { case MultipartPart.FilePart(_, path, _) => path }
    delegate.postMultipart(u, h, parts, t)
  }
  override def put(u: String, h: Map[String, String], b: String, t: FiniteDuration): Result[HttpResponse] =
    delegate.put(u, h, b, t)
  override def delete(u: String, h: Map[String, String], t: FiniteDuration): Result[HttpResponse] =
    delegate.delete(u, h, t)
  override def postRaw(u: String, h: Map[String, String], b: String, t: FiniteDuration): Result[HttpRawResponse] =
    delegate.postRaw(u, h, b, t)
  override def postStream(
    u: String,
    h: Map[String, String],
    b: String,
    t: FiniteDuration
  ): Result[StreamingHttpResponse] = delegate.postStream(u, h, b, t)
}
