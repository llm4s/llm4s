package org.llm4s.mcp

import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration._

/**
 * A server that does not answer within the configured [[MCPServerConfig.timeout]], end to end
 * through a real in-process [[MCPServer]]: the call fails with a timeout instead of waiting for the
 * server, the failure is a tool failure (not a cancellation, not "no such tool"), and the client
 * and registry stay usable for the next call.
 *
 * `stall` blocks on a latch that only `afterAll` releases, so nothing here depends on how long
 * a sleep lasts: the server cannot answer in time, whatever the machine's speed.
 */
class MCPEmbeddedServerTimeoutSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val ClientTimeout = 1.second
  // Far longer than ClientTimeout, far shorter than the latch's own 60-second bound.
  private val GiveUpWithin = 30.seconds

  private val release           = new CountDownLatch(1)
  private val stallEntered      = new CountDownLatch(1)
  private var server: MCPServer = _
  private var url: String       = _

  override def beforeAll(): Unit = {
    val noArgs = Schema.`object`[Map[String, Any]]("No parameters")
    def tool(name: String)(handler: SafeParameterExtractor => Either[String, String]): ToolFunction[_, _] =
      ToolBuilder[Map[String, Any], String](name, name, noArgs)
        .withHandler(handler)
        .buildSafe()
        .fold(e => fail(e.formatted), identity)

    val stall = tool("stall") { _ =>
      stallEntered.countDown()
      release.await(60, TimeUnit.SECONDS): Unit
      Right("too late")
    }
    val ping = tool("ping")(_ => Right("pong"))

    server = new MCPServer(MCPServerOptions(0, "/mcp", "TimeoutServer", "1.0.0"), Seq(stall, ping))
    server.start().fold(e => throw e, _ => ())
    url = s"http://127.0.0.1:${server.boundPort}/mcp"
  }

  override def afterAll(): Unit = {
    release.countDown()
    if (server != null) server.stop()
  }

  private def timed[A](f: => A): (A, FiniteDuration) = {
    val start  = System.nanoTime()
    val result = f
    result -> (System.nanoTime() - start).nanos
  }

  "MCPClientImpl" should "fail a tool call the server does not answer within the timeout, and stay usable" in {
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("timeout-client", url, ClientTimeout))
    try {
      client.initialize() shouldBe Right(())
      val tools = client.getTools().fold(e => fail(e.formatted), identity)
      val stall = tools.find(_.name == "stall").getOrElse(fail("stall not advertised"))
      val ping  = tools.find(_.name == "ping").getOrElse(fail("ping not advertised"))

      val (result, elapsed) = timed(stall.execute(ujson.Obj()))
      stallEntered.await(10, TimeUnit.SECONDS) shouldBe true // the request reached the tool
      result match {
        case Left(error) =>
          error shouldBe a[ToolCallError.HandlerError]
          error.getMessage should include("timed out")
        case Right(value) => fail(s"expected a timeout, got $value")
      }
      elapsed should be < GiveUpWithin

      ping.execute(ujson.Obj()) shouldBe Right(ujson.Str("pong"))
    } finally client.close()
  }

  "MCPToolRegistry" should "report a timed-out MCP call as a failed tool, not a cancelled or unknown one" in {
    val registry = new MCPToolRegistry(
      Seq(MCPServerConfig.streamableHTTP("timeout-registry", url, ClientTimeout)),
      Seq.empty,
      5.minutes,
      initializeOnStartup = false
    )
    try {
      val (result, elapsed) = timed(registry.execute(ToolCallRequest("stall", ujson.Obj())))
      result match {
        case Left(_: ToolCallError.Cancelled)       => fail("a timeout is not a cancellation")
        case Left(_: ToolCallError.UnknownFunction) => fail("a timeout is not an unknown tool")
        case Left(error)                            => error.getMessage should include("timed out")
        case Right(value)                           => fail(s"expected a timeout, got $value")
      }
      elapsed should be < GiveUpWithin
      Thread.currentThread().isInterrupted shouldBe false

      registry.execute(ToolCallRequest("ping", ujson.Obj())) shouldBe Right(ujson.Str("pong"))
    } finally registry.close()
  }
}
