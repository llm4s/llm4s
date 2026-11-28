package org.llm4s.mcp

import com.sun.net.httpserver.{ HttpExchange, HttpServer }

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ ConcurrentLinkedQueue, Executors, LinkedBlockingQueue, TimeUnit }
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * A scripted MCP 2024-11-05 HTTP+SSE server for the transport specs (#3073): `GET /sse` opens an event stream whose
 * first event is `endpoint`, and each `POST /messages` is answered by `onPost`, which returns the status to answer the
 * POST with and, for 202, the raw SSE text to write to the open stream, for any other status the response body. `POST /mcp` (a Streamable HTTP attempt) is answered
 * `streamableStatus`.
 *
 * @param endpoint what the `endpoint` event says, given the server's base URL (`http://127.0.0.1:<port>`)
 */
final class LegacySseServer(
  endpoint: String => String = _ => "/messages?sessionId=s1",
  onPost: ujson.Value => (Int, String) = LegacySseServer.echoResult,
  streamableStatus: Int = 405
) extends AutoCloseable {

  /** Each request's method, path and headers (names lower case), and each POST's body. */
  val requests = new ConcurrentLinkedQueue[(String, String, Map[String, String])]()
  val posted   = new ConcurrentLinkedQueue[ujson.Value]()

  /** How many event streams have been opened. */
  @volatile var streamsOpened = 0

  private val streams = new ConcurrentLinkedQueue[LinkedBlockingQueue[Option[String]]]()
  private val server  = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.setExecutor(Executors.newCachedThreadPool())
  server.createContext("/", (exchange: HttpExchange) => handle(exchange))
  server.start()

  def base: String = s"http://127.0.0.1:${server.getAddress.getPort}"
  def url: String  = s"$base/sse"

  /** Writes `sse` (raw event text) to every open stream. */
  def push(sse: String): Unit = streams.asScala.foreach(_.put(Some(sse)))

  /** Ends every open stream, as a server that restarts does. */
  def dropStreams(): Unit = streams.asScala.foreach(_.put(None))

  /** The headers of the requests of `method`. */
  def headersOf(method: String): Seq[Map[String, String]] =
    requests.asScala.toSeq.collect { case (m, _, h) if m == method => h }

  private def handle(exchange: HttpExchange): Unit = {
    val headers = exchange.getRequestHeaders.asScala.map { case (k, v) =>
      k.toLowerCase -> v.asScala.mkString(",")
    }.toMap
    val path = exchange.getRequestURI.getPath
    requests.add((exchange.getRequestMethod, path, headers))
    (exchange.getRequestMethod, path) match {
      case ("GET", "/sse") => stream(exchange)
      case ("POST", "/messages") =>
        val body = ujson.read(new String(exchange.getRequestBody.readAllBytes(), UTF_8))
        posted.add(body)
        val (status, events) = onPost(body)
        respond(exchange, status, if (status == 202) "Accepted" else events)
        if (status == 202 && events.nonEmpty) push(events)
      case ("POST", _) => respond(exchange, streamableStatus, "not here")
      case _           => respond(exchange, 405, "no")
    }
  }

  private def stream(exchange: HttpExchange): Unit = {
    val queue = new LinkedBlockingQueue[Option[String]]()
    streams.add(queue)
    streamsOpened += 1
    exchange.getResponseHeaders.set("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, 0)
    val out                          = exchange.getResponseBody
    def write(text: String): Boolean = Try { out.write(text.getBytes(UTF_8)); out.flush() }.isSuccess
    if (write(s"event: endpoint\ndata: ${endpoint(base)}\n\n")) {
      var open = true
      while (open)
        Option(queue.poll(100, TimeUnit.MILLISECONDS)) match {
          case Some(Some(text)) => open = write(text)
          case Some(None)       => open = false
          case None             => open = write(": keep-alive\n\n")
        }
    }
    streams.remove(queue)
    Try(out.close())
    exchange.close()
  }

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(UTF_8)
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  }

  override def close(): Unit = {
    dropStreams()
    server.stop(0)
  }
}

object LegacySseServer {

  /** An SSE `message` event carrying `json`. */
  def message(json: String): String = s"event: message\ndata: $json\n\n"

  /** Accepts every POST, and answers a request with `{"echo": <method>}` on the stream; a notification gets nothing. */
  val echoResult: ujson.Value => (Int, String) = body =>
    body.obj.get("id") match {
      case Some(id) if !body.obj.contains("result") && !body.obj.contains("error") =>
        val result =
          if (body("method").str == "initialize")
            ujson.Obj("protocolVersion" -> "2024-11-05", "capabilities" -> ujson.Obj("tools" -> ujson.Obj()))
          else ujson.Obj("echo"         -> body("method"))
        202 -> message(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "result" -> result).render())
      case _ => 202 -> ""
    }
}
