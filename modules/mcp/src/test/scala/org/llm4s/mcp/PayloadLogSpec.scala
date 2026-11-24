package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.llm4s.http.{ HttpResponse, StubbedHttpCalls }
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The transports log JSON-RPC payloads at DEBUG. A payload can be megabytes - a tool argument, a file's contents -
 * and logging one whole put a single 1 MB line in CI's output, which stalled the runner's log processing for half
 * an hour. These pin that a logged payload is cut to `PayloadLog.MaxChars` and has its secrets redacted.
 */
class PayloadLogSpec extends AnyFlatSpec with Matchers with MockFactory {
  import PayloadLogSpec.exchangeTimeout

  "PayloadLog.preview" should "pass a short payload through unchanged" in {
    PayloadLog.preview("""{"jsonrpc":"2.0","id":"1"}""") shouldBe """{"jsonrpc":"2.0","id":"1"}"""
  }

  it should "cut a long payload to MaxChars and say how much it omitted" in {
    val preview = PayloadLog.preview("x" * PayloadLog.RedactWholeLimit)
    preview should startWith("x" * PayloadLog.MaxChars)
    preview.length should be < PayloadLog.MaxChars + 100
    preview should include(s"${PayloadLog.RedactWholeLimit - PayloadLog.MaxChars} chars omitted")
  }

  // Redacting a payload whole costs about a second per megabyte, on the thread whose exchange is running against
  // its deadline. Past RedactWholeLimit only a prefix is redacted, which the suffix says: no timing is asserted.
  it should "preview a payload over RedactWholeLimit from a redacted prefix of it, not from the whole" in {
    val line = """the quick \"brown\" fox's jumps, over (lazy) dogs\n"""
    val head =
      """{"jsonrpc":"2.0","id":"9","result":{"api_key":"hunter2-SENTINEL","content":[{"type":"text","text":""""
    val payload = head + line * (1024 * 1024 / line.length) + """"}],"token":"late-SENTINEL"}}"""
    val preview = PayloadLog.preview(payload)

    preview should startWith("""{"jsonrpc":"2.0","id":"9","result":{"api_key":"[REDACTED]","content":""")
    preview should include(s"original length: ${payload.length}, redacted from its first ")
    preview.length should be < PayloadLog.MaxChars + 200
    (preview should not).include("SENTINEL")
  }

  it should "show nothing of a payload over RedactWholeLimit that has no prefix it can redact safely" in {
    val preview = PayloadLog.preview("x" * (1024 * 1024))
    (preview should not).include("xxxx")
    preview should include(s"original length: ${1024 * 1024}")
    preview.length should be < 200
  }

  it should "redact a secret in the payload" in {
    (PayloadLog.preview("""{"api_key":"hunter2-SENTINEL"}""") should not).include("hunter2-SENTINEL")
  }

  "StdioTransportImpl" should "log a one-megabyte request as a bounded, redacted preview" in {
    val echoServer = StdioTestServers.echo("""{"jsonrpc":"2.0","id":"$id","result":{}}""", "--ready")
    val transport  = new StdioTransportImpl(echoServer, startupTimeout = 500.millis, name = "payload-log")
    val request = JsonRpcRequest(
      "2.0",
      "big",
      "tools/call",
      Some(ujson.Obj("api_key" -> "hunter2-SENTINEL", "text" -> "x" * (1024 * 1024)))
    )

    val (result, logged) = capturingDebug(classOf[StdioTransportImpl]) {
      try transport.sendRequest(request)
      finally transport.close()
    }

    result.isRight shouldBe true

    val written = logged.filter(_.contains("writing to stdin"))
    written should have size 1
    written.head.length should be < PayloadLog.MaxChars + 200
    (written.head should not).include("hunter2-SENTINEL")
    all(logged.map(_.length)) should be < PayloadLog.MaxChars + 200
  }

  it should "bound and redact the server's stderr in the error it returns and logs" in {
    // Reads the request, then writes ~3 KB to stderr (over MaxChars, under a pipe buffer) and exits: the pending request fails, and the transport
    // reports the server's stderr in its ERROR log and in the returned error. The server assembles the secret at
    // run time, because the transport logs its command line.
    val failingServer = StdioTestServers.command("--stderr-flood")
    val transport     = new StdioTransportImpl(failingServer, startupTimeout = 500.millis, name = "stderr-log")

    val started = System.nanoTime()
    val (result, logged) = capturingDebug(classOf[StdioTransportImpl]) {
      try transport.sendRequest(JsonRpcRequest("2.0", "1", "tools/list", None))
      finally transport.close()
    }
    val elapsed = (System.nanoTime() - started).nanos

    val error = result.left.map(_.message)
    error.isLeft shouldBe true
    // The process-exit path, not the 30 s response timeout: a blocked server would end on the timeout.
    elapsed should be < 15.seconds
    error.left.foreach(message => (message should not).include("Timeout waiting"))
    error.left.foreach { message =>
      message should include("Server stderr:")
      message.length should be < PayloadLog.MaxChars + 300
      (message should not).include("hunter2-SENTINEL")
    }
    logged.exists(_.contains("Server stderr:")) shouldBe true
    all(logged.map(_.length)) should be < PayloadLog.MaxChars + 300
    logged.foreach(line => (line should not).include("hunter2-SENTINEL"))
  }

  Seq[(String, StubbedHttpCalls => MCPTransportImpl, Class[?])](
    (
      "StreamableHTTPTransportImpl",
      http =>
        new StreamableHTTPTransportImpl("http://localhost/mcp", "sse-log", exchangeTimeout, WholeBodyReplies(http)),
      classOf[StreamableHTTPTransportImpl]
    ),
    (
      "SSETransportImpl",
      http => new SSETransportImpl("http://localhost/mcp", "sse-log", exchangeTimeout, http),
      classOf[SSETransportImpl]
    )
  ).foreach { case (transportName, newTransport, source) =>
    transportName should "log a large non-JSON-RPC SSE event as a bounded, redacted preview" in {
      val nonJsonRpc = s"""{"api_key":"hunter2-SENTINEL","x":"${"x" * (1024 * 1024)}"}"""
      val response   = """{"jsonrpc":"2.0","id":"1","result":{}}"""
      val sseBody    = s"data: $nonJsonRpc\n\ndata: $response\n\n"
      val http       = stub[StubbedHttpCalls]
      (http.onPost _)
        .when(*, *, *, *)
        .returns(Right(HttpResponse(200, sseBody, Map("content-type" -> Seq("text/event-stream")))))

      val (result, logged) = capturingDebug(source) {
        newTransport(http).sendRequest(JsonRpcRequest("2.0", "1", "tools/list", None))
      }

      result.isRight shouldBe true

      logged.exists(_.contains("skipping non-JSON-RPC SSE data")) shouldBe true
      all(logged.map(_.length)) should be < PayloadLog.MaxChars + 300
      logged.foreach(line => (line should not).include("hunter2-SENTINEL"))
    }
  }

  /** Runs `body` with `source`'s logger at DEBUG, returning its result and the messages logged meanwhile. */
  private def capturingDebug[A](source: Class[?])(body: => A): (A, Seq[String]) = {
    val logger   = LoggerFactory.getLogger(source).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    val result =
      try body
      finally {
        logger.detachAppender(appender)
        logger.setLevel(previous)
      }
    (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
  }
}

object PayloadLogSpec {

  /**
   * The HTTP transports' timeout. The reply is a stub's, so nothing waits on a server and the timeout is never what
   * is tested. It is the deadline of the whole exchange all the same, and logging the skipped event counts against
   * it: at 5 seconds, a Windows runner that took over 5 seconds to redact the event whole timed the request out.
   * Generous, so that no runner's speed decides the result.
   */
  private val exchangeTimeout: FiniteDuration = 10.minutes
}
