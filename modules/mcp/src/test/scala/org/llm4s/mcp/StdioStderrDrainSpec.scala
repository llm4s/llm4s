package org.llm4s.mcp

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * A stdio server's stderr is read while a request is in flight (#1952): a server that logs more than the pipe holds
 * would otherwise block in its write, never answer, and fail the request on the 30 s response timeout.
 */
class StdioStderrDrainSpec extends AnyWordSpec with Matchers {

  private val Reply   = """{"jsonrpc":"2.0","id":"$id","result":{}}"""
  private val request = JsonRpcRequest("2.0", "1", "tools/list", None)

  private def drainThreads: Seq[Thread] =
    Thread.getAllStackTraces.keySet.asScala.toSeq.filter(_.getName.endsWith("-stderr"))

  "StdioTransportImpl" should {

    "answer a server that writes 256 KB to stderr before replying, well inside the response timeout" in {
      val transport = new StdioTransportImpl(
        StdioTestServers.echo(Reply, "--stderr-kb=256"),
        startupTimeout = 500.millis,
        name = "drain-ok"
      )
      val started = System.nanoTime()
      val result  = transport.sendRequest(request)
      val elapsed = (System.nanoTime() - started).nanos
      transport.close()

      withClue(s"result: $result; ")(result.isRight shouldBe true)
      // A blocked server would end on the 30 s timeout; this is the reply, not that path.
      elapsed should be < 15.seconds
      result.left.foreach(e => (e.message should not).include("Timeout waiting"))
    }

    "keep the recent stderr in the error when the server dies after writing it" in {
      val transport = new StdioTransportImpl(
        StdioTestServers.echo(Reply, "--stderr-kb=256", "--die-after-stderr"),
        startupTimeout = 500.millis,
        name = "drain-fail"
      )
      val result = transport.sendRequest(request)
      transport.close()

      result.isLeft shouldBe true
      val message = result.left.toOption.map(_.message).getOrElse("")
      message should include("Server stderr:")
      message should include("last-words-marker")
      message.length should be < PayloadLog.MaxChars + 300
      (message should not).include("Timeout waiting")
    }

    "end its stderr drain thread on close" in {
      val name      = "drain-close"
      val transport = new StdioTransportImpl(StdioTestServers.echo(Reply), startupTimeout = 500.millis, name = name)
      transport.sendRequest(request).isRight shouldBe true
      val mine = drainThreads.filter(_.getName.contains(name))
      mine should have size 1
      mine.head.isDaemon shouldBe true

      transport.close()

      mine.head.isAlive shouldBe false
    }
  }
}
