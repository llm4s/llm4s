package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
class PayloadLogSpec extends AnyFlatSpec with Matchers {

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

    val logged = capturingDebug(classOf[StdioTransportImpl]) {
      try transport.sendRequest(request).isRight shouldBe true
      finally transport.close()
    }

    val written = logged.filter(_.contains("writing to stdin"))
    written should have size 1
    written.head.length should be < PayloadLog.MaxChars + 200
    (written.head should not).include("hunter2-SENTINEL")
    all(logged.map(_.length)) should be < PayloadLog.MaxChars + 200
  }

  private def capturingDebug(source: Class[?])(body: => Unit): Seq[String] = {
    val logger   = LoggerFactory.getLogger(source).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    try body
    finally {
      logger.detachAppender(appender)
      logger.setLevel(previous)
    }
    appender.list.asScala.toSeq.map(_.getFormattedMessage)
  }
}
