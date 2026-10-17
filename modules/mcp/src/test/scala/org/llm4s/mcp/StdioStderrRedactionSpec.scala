package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * The server's stderr is redacted as whole lines, whatever the read boundaries: a key and its value split across two
 * reads must never leave the value unredacted in the log or in an error (#1957).
 */
class StdioStderrRedactionSpec extends AnyWordSpec with Matchers {

  private val Secret  = "hunter2-" + "SENTINEL"
  private val Reply   = """{"jsonrpc":"2.0","id":"$id","result":{}}"""
  private val request = JsonRpcRequest("2.0", "1", "tools/list", None)

  private def lines(cap: Int, chunks: Seq[String]): Seq[String] = {
    val out = Seq.newBuilder[String]
    val l   = new StdioTransportImpl.StderrLines(cap, out += _)
    chunks.foreach(l.feed)
    l.finish()
    out.result()
  }

  "StderrLines" should {

    "never separate a key from its value, wherever the line is split into chunks" in {
      val line = "x" * 3000 + s"""{"api_key":"$Secret","y":"${"y" * 3000}"}"""
      for (at <- 0 to line.length by 61) {
        val out = lines(1 << 14, Seq(line.take(at), line.drop(at), "\n"))
        out should have size 1
        (out.head should not).include(Secret)
        out.head.length should be < PayloadLog.MaxChars + 200
      }
    }

    "join lines split across chunks and strip the carriage return" in {
      lines(100, Seq("ab", "c\r", "\nd", "e\nf")) shouldBe Seq("abc", "de", "f")
    }

    "redact a line longer than the cap from its capped prefix and mark what was dropped" in {
      val line = s"""{"api_key":"$Secret"}""" + "z" * 5000 + "tail-" + Secret
      val out  = lines(1000, line.grouped(97).toSeq :+ "\n")
      out should have size 1
      (out.head should not).include(Secret)
      out.head should include("omitted]")
    }

    "keep only the cap of a line that never ends" in {
      val out = Seq.newBuilder[String]
      val l2  = new StdioTransportImpl.StderrLines(1000, out += _)
      Iterator.fill(2000)("z" * 1000).foreach(l2.feed)
      out.result() shouldBe empty // nothing is emitted until the line ends or the stream does
      l2.finish()
      val result = out.result()
      result should have size 1
      result.head.length should be < PayloadLog.MaxChars + 200
    }
  }

  "StdioTransportImpl" should {

    "redact a key and value split by a read, in the log and in the error" in {
      val (result, logged) = captured(StdioTestServers.echo(Reply, "--stderr-secret-line"), "split-secret")
      val message          = result.left.toOption.map(_.message).getOrElse("")
      message should include("Server stderr:")
      (message should not).include(Secret)
      logged.filter(_.contains("stderr:")) should not be empty
      logged.foreach(m => (m should not).include(Secret))
    }

    "log and report a line that never ends once, redacted, without keeping the rest" in {
      val (result, logged) = captured(StdioTestServers.echo(Reply, "--stderr-endless"), "endless")
      val message          = result.left.toOption.map(_.message).getOrElse("")
      (message should not).include(Secret)
      (message should not).include("tail-SENTINEL")
      message.length should be < PayloadLog.MaxChars + 400
      val mine = logged.filter(_.contains("stderr:"))
      mine should not be empty
      mine.foreach { m =>
        (m should not).include(Secret)
        (m should not).include("tail-SENTINEL")
        m.length should be < PayloadLog.MaxChars + 400
      }
    }
  }

  private def captured(command: Seq[String], name: String): (Either[org.llm4s.error.LLMError, ?], Seq[String]) = {
    val logger   = LoggerFactory.getLogger(classOf[StdioTransportImpl]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    val transport = new StdioTransportImpl(command, name, 500.millis)
    val result    = transport.sendRequest(request)
    transport.close()
    logger.detachAppender(appender)
    logger.setLevel(previous)
    (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
  }
}
