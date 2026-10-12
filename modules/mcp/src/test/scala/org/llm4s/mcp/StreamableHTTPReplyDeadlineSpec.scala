package org.llm4s.mcp

import com.sun.net.httpserver.HttpExchange
import org.llm4s.testkit.{ LocalProviderTestServer, ProviderModuleChecks }
import org.llm4s.toolapi.ToolCallError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.write

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Try

/**
 * [[MCPServerConfig.timeout]] bounds the whole Streamable HTTP exchange, reply body included (#1785).
 *
 * The HTTP request timeout stops counting once response headers arrive, and a Streamable HTTP server that
 * answers with an SSE stream - as servers built on the MCP SDKs do - sends them at once. These servers do
 * exactly that and then stall, or keep the stream alive without ever replying: the call must still end at
 * the configured timeout, as a timed-out failure, and close the connection.
 *
 * Every server here is local (`LocalProviderTestServer`), and the stalling ones are held by a latch that
 * only the end of the test releases, so no outcome depends on how long a sleep lasts. Each call runs on
 * its own thread and the test gives up on it after [[GiveUpAfter]], so a call that ignores its timeout
 * fails the test instead of hanging it.
 */
class StreamableHTTPReplyDeadlineSpec extends AnyFlatSpec with Matchers {

  private val Timeout     = 300.millis
  private val GiveUpAfter = 10.seconds

  private val request = JsonRpcRequest(jsonrpc = "2.0", id = "1", method = "tools/list", params = None)

  private val replyJson = """{"jsonrpc":"2.0","id":"1","result":{"tools":[]}}"""

  private val progressEvent =
    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}\n\n"

  private given ExecutionContext = ExecutionContext.global

  /** Runs `call` on another thread; fails the test if it has not returned within [[GiveUpAfter]]. */
  private def timed[A](call: => A): (A, FiniteDuration) = {
    val start  = System.nanoTime()
    val result = Await.result(Future(call), GiveUpAfter)
    result -> (System.nanoTime() - start).nanos
  }

  private def transport(url: String, timeout: FiniteDuration = Timeout) =
    new StreamableHTTPTransportImpl(s"$url/mcp", "deadline", timeout)

  private def startStream(exchange: HttpExchange, contentType: String = "text/event-stream"): Unit = {
    exchange.getResponseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(200, 0) // 0: chunked, length unknown
  }

  /**
   * Sends a progress event every 20 ms until a write fails because the client has gone, then counts down
   * `clientLeft`; counts down `sent` after each event. Stops, without counting down, when the server shuts down.
   */
  private def streamUntilClientLeaves(clientLeft: CountDownLatch, sent: CountDownLatch = new CountDownLatch(0))(
    exchange: HttpExchange
  ): Unit = {
    startStream(exchange)
    val out   = exchange.getResponseBody
    val bytes = progressEvent.getBytes(UTF_8)
    Try {
      while (!Thread.currentThread().isInterrupted) {
        out.write(bytes)
        out.flush()
        sent.countDown()
        Thread.sleep(20)
      }
    }.failed.foreach {
      case _: IOException => clientLeft.countDown()
      case _              => ()
    }
    Try(out.close()): Unit
  }

  private def assertTimedOut(result: Result[JsonRpcResponse], elapsed: FiniteDuration, timeout: FiniteDuration) = {
    result match {
      case Left(error)  => error.message should include("timed out")
      case Right(reply) => fail(s"expected a timeout, got $reply")
    }
    elapsed should be >= (timeout - 50.millis)
  }

  "StreamableHTTPTransportImpl" should "end a call at its timeout when an SSE reply sends headers and then nothing" in
    LocalProviderTestServer.withServer("/mcp")(LocalProviderTestServer.streamThenHold(_, ": stream opened\n\n")) {
      url =>
        val (result, elapsed) = timed(transport(url).sendRequest(request))
        assertTimedOut(result, elapsed, Timeout)
        elapsed should be < 5.seconds
    }

  it should "end a call at its timeout when an SSE stream keeps sending events but never the reply, and close the connection" in {
    val clientLeft = new CountDownLatch(1)
    LocalProviderTestServer.withServer("/mcp")(streamUntilClientLeaves(clientLeft)) { url =>
      val (result, elapsed) = timed(transport(url).sendRequest(request))
      assertTimedOut(result, elapsed, Timeout)
      withClue("the server never saw the client close the connection: ") {
        clientLeft.await(5, TimeUnit.SECONDS) shouldBe true
      }
    }
  }

  it should "end a call at its timeout when a plain JSON reply's body stalls after the headers" in
    LocalProviderTestServer.withServer("/mcp") { exchange =>
      startStream(exchange, "application/json")
      val out = exchange.getResponseBody
      out.write("""{"jsonrpc":"2.0",""".getBytes(UTF_8))
      out.flush()
      LocalProviderTestServer.holdOpen(exchange)
    } { url =>
      val (result, elapsed) = timed(transport(url).sendRequest(request))
      assertTimedOut(result, elapsed, Timeout)
    }

  it should "count the time to the response headers against the same timeout" in
    LocalProviderTestServer.withServer("/mcp")(LocalProviderTestServer.holdOpen) { url =>
      val (result, elapsed) = timed(transport(url).sendRequest(request))
      assertTimedOut(result, elapsed, Timeout)
    }

  it should "return the reply as soon as it arrives on an SSE stream the server keeps open" in
    LocalProviderTestServer.withServer("/mcp")(
      LocalProviderTestServer.streamThenHold(_, s"event: message\ndata: $replyJson\n\n")
    ) { url =>
      // The server never ends the stream; the reply must not wait for the 30-second timeout.
      val (result, elapsed) = timed(transport(url, 30.seconds).sendRequest(request))
      result.map(_.id) shouldBe Right("1")
      elapsed should be < 5.seconds
    }

  it should "return a reply that follows progress events on a slow stream, within the timeout" in
    LocalProviderTestServer.withServer("/mcp") { exchange =>
      startStream(exchange)
      val out = exchange.getResponseBody
      (1 to 3).foreach { _ =>
        out.write(progressEvent.getBytes(UTF_8))
        out.flush()
        Thread.sleep(20)
      }
      out.write(s"event: message\ndata: $replyJson\n\n".getBytes(UTF_8))
      out.close()
    } { url =>
      val (result, _) = timed(transport(url, 5.seconds).sendRequest(request))
      result.map(_.id) shouldBe Right("1")
    }

  it should "return CancelledError, and close the connection, when interrupted while reading an SSE reply" in {
    val clientLeft = new CountDownLatch(1)
    val sent       = new CountDownLatch(3)
    LocalProviderTestServer.withServer("/mcp")(streamUntilClientLeaves(clientLeft, sent)) { url =>
      ProviderModuleChecks.assertCallCancelsOnceReady("streamable-http sendRequest reading an SSE reply")(
        transport(url, 30.seconds).sendRequest(request)
      ) {
        // Three events in: the headers have gone, so the call is reading the body, not waiting for them
        sent.await(5, TimeUnit.SECONDS) shouldBe true
      }
      withClue("the server never saw the client close the connection: ") {
        clientLeft.await(5, TimeUnit.SECONDS) shouldBe true
      }
    }
  }

  // ---- end to end, through the client

  /** A minimal Streamable HTTP MCP server with one tool, `slow`, whose call is answered by `onCall`. */
  private def mcpServer(onCall: HttpExchange => Unit)(exchange: HttpExchange): Unit = {
    val message = ujson.read(new String(exchange.getRequestBody.readAllBytes(), UTF_8))
    def reply(result: ujson.Value): Unit = LocalProviderTestServer.sendJsonResponse(
      exchange,
      200,
      ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> message("id"), "result" -> result))
    )
    message("method").str match {
      case "initialize" =>
        reply(
          ujson.read(
            write(InitializeResponse("2025-06-18", MCPCapabilities(), ServerInfo("deadline-server", "1.0.0")))
          )
        )
      case "tools/list" =>
        reply(
          ujson.Obj(
            "tools" -> ujson.Arr(
              ujson.Obj(
                "name"        -> "slow",
                "description" -> "Never answers",
                "inputSchema" -> ujson.Obj("type" -> "object")
              )
            )
          )
        )
      case "tools/call" => onCall(exchange)
      case _ => // a notification
        exchange.sendResponseHeaders(202, -1)
        exchange.close()
    }
  }

  "MCPClientImpl" should "fail a tool call whose SSE reply never comes with a timeout, and stay usable" in
    LocalProviderTestServer.withServer("/mcp")(
      mcpServer(LocalProviderTestServer.streamThenHold(_, ": working\n\n"))
    ) { url =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("deadline-client", s"$url/mcp", 1.second))
      try {
        client.initialize() shouldBe Right(())
        val slow = client.getTools().fold(e => fail(e.formatted), identity).find(_.name == "slow").get

        val (result, elapsed) = timed(slow.execute(ujson.Obj()))
        result match {
          case Left(error: ToolCallError.HandlerError) => error.getMessage should include("timed out")
          case other                                   => fail(s"expected a timed-out tool failure, got $other")
        }
        elapsed should be < 5.seconds

        val transport = client.transport.getOrElse(fail("no transport after initialize"))
        transport.sendRequest(JsonRpcRequest("2.0", "2", "tools/list", None)).map(_.id) shouldBe Right("2")
      } finally client.close()
    }
}
