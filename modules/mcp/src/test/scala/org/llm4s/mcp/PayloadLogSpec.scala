package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
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

  private val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  "PayloadLog.preview" should "pass a short payload through unchanged" in {
    PayloadLog.preview("""{"jsonrpc":"2.0","id":"1"}""") shouldBe """{"jsonrpc":"2.0","id":"1"}"""
  }

  it should "cut a long payload to MaxChars and say how much it omitted" in {
    val preview = PayloadLog.preview("x" * (1024 * 1024))
    preview should startWith("x" * PayloadLog.MaxChars)
    preview.length should be < PayloadLog.MaxChars + 100
    preview should include(s"${1024 * 1024 - PayloadLog.MaxChars} chars omitted")
  }

  it should "redact a secret in the payload" in {
    (PayloadLog.preview("""{"api_key":"hunter2-SENTINEL"}""") should not).include("hunter2-SENTINEL")
  }

  "StdioTransportImpl" should "log a one-megabyte request as a bounded, redacted preview" in {
    assume(!isWindows, "Bash not available on Windows")
    val echoServer = Seq(
      "bash",
      "-c",
      """while IFS= read -r line; do
           id=$(echo "$line" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
           [ -n "$id" ] && echo "{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{}}"
         done"""
    )
    val transport = new StdioTransportImpl(echoServer, startupTimeout = 500.millis, name = "payload-log")
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
    assume(!isWindows, "Bash not available on Windows")
    // Reads the request, then writes ~10 KB to stderr and exits: the pending request fails, and the transport
    // reports the server's stderr in its ERROR log and in the returned error. The script assembles the secret at
    // run time, because the transport logs its command line.
    val failingServer = Seq(
      "bash",
      "-c",
      """read -r line; k=hunter2-SENT; printf '{"api_key":"%sINEL","x":"%s"}\n' "$k" "$(printf 'x%.0s' $(seq 1 10000))" >&2; exit 1"""
    )
    val transport = new StdioTransportImpl(failingServer, startupTimeout = 500.millis, name = "stderr-log")

    val (result, logged) = capturingDebug(classOf[StdioTransportImpl]) {
      try transport.sendRequest(JsonRpcRequest("2.0", "1", "tools/list", None))
      finally transport.close()
    }

    val error = result.left.map(_.message)
    error.isLeft shouldBe true
    error.left.foreach { message =>
      message should include("Server stderr:")
      message.length should be < PayloadLog.MaxChars + 300
      (message should not).include("hunter2-SENTINEL")
    }
    logged.exists(_.contains("Server stderr:")) shouldBe true
    all(logged.map(_.length)) should be < PayloadLog.MaxChars + 300
    logged.foreach(line => (line should not).include("hunter2-SENTINEL"))
  }

  Seq[(String, Llm4sHttpClient => MCPTransportImpl, Class[?])](
    (
      "StreamableHTTPTransportImpl",
      http => new StreamableHTTPTransportImpl("http://localhost/mcp", "sse-log", 5.seconds, http),
      classOf[StreamableHTTPTransportImpl]
    ),
    (
      "SSETransportImpl",
      http => new SSETransportImpl("http://localhost/mcp", "sse-log", 5.seconds, http),
      classOf[SSETransportImpl]
    )
  ).foreach { case (transportName, newTransport, source) =>
    transportName should "log a large non-JSON-RPC SSE event as a bounded, redacted preview" in {
      val nonJsonRpc = s"""{"api_key":"hunter2-SENTINEL","x":"${"x" * (1024 * 1024)}"}"""
      val response   = """{"jsonrpc":"2.0","id":"1","result":{}}"""
      val sseBody    = s"data: $nonJsonRpc\n\ndata: $response\n\n"
      val http       = stub[Llm4sHttpClient]
      (http.post _)
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
