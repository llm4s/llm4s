package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.testkit.{ LocalProviderTestServer, ProviderModuleChecks }
import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration._

/**
 * MCP honours interruption (design section 4.4): called on a virtual thread, then interrupted, each layer
 * returns promptly with the thread's interrupt flag still set - `Left(CancelledError)` from a transport or
 * the client, `ToolCallError.Cancelled` from the registry - and never a transport failure, an empty tool
 * list or "no such tool". Stopping the thread also stops what it started: a stdio server it launched.
 */
class MCPCancellationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val isWindows = System.getProperty("os.name").toLowerCase.contains("win")

  private val request = JsonRpcRequest(jsonrpc = "2.0", id = "1", method = "tools/list", params = None)

  // A real MCP server with one tool that never returns, for the end-to-end case.
  private val hangStarted       = new CountDownLatch(1)
  private val release           = new CountDownLatch(1)
  private var server: MCPServer = _
  private var port: Int         = -1

  override def beforeAll(): Unit = {
    val noArgs = Schema.`object`[Map[String, Any]]("No parameters")
    val hang = ToolBuilder[Map[String, Any], String]("hang", "Never returns until released", noArgs)
      .withHandler { _ =>
        hangStarted.countDown()
        release.await(60, TimeUnit.SECONDS): Unit
        Right("late")
      }
      .buildSafe()
      .fold(e => fail(e.formatted), identity)
    server = new MCPServer(MCPServerOptions(0, "/mcp", "CancellationServer", "1.0.0"), Seq(hang))
    server.start().fold(e => throw e, _ => ())
    port = server.boundPort
  }

  override def afterAll(): Unit = {
    release.countDown()
    if (server != null) server.stop()
  }

  private def withHeldServer(test: String => Any): Unit =
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen)(test)

  /** Returns once `file` is not empty, and fails if it is still empty after 10 seconds. */
  private def awaitContent(file: Path, what: String): Unit = {
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while (new String(Files.readAllBytes(file)).trim.isEmpty && System.nanoTime() < deadline) Thread.sleep(20)
    assert(new String(Files.readAllBytes(file)).trim.nonEmpty, s"the server never $what")
  }

  /** The process whose id `pidFile` holds has ended, waiting up to 5 seconds for it to. */
  private def programStopped(pidFile: Path): Boolean = {
    val pid      = new String(Files.readAllBytes(pidFile)).trim.toLong
    val deadline = System.nanoTime() + 5.seconds.toNanos
    def alive    = ProcessHandle.of(pid).map(_.isAlive).orElse(false)
    while (alive && System.nanoTime() < deadline) Thread.sleep(50)
    !alive
  }

  // ---- the HTTP transports

  "StreamableHTTPTransportImpl" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val transport = new StreamableHTTPTransportImpl(url, "held", 30.seconds)
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("streamable-http sendRequest")(
      transport.sendRequest(request)
    )
  }

  "SSETransportImpl" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val transport = new SSETransportImpl(url, "held", 30.seconds)
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("sse sendRequest")(transport.sendRequest(request))
  }

  // ---- the client

  private def clientFor(url: String) =
    new MCPClientImpl(MCPServerConfig("held", StreamableHTTPTransport(url, "held"), 30.seconds))

  "MCPClientImpl" should "return CancelledError from initialize when its thread is interrupted" in withHeldServer {
    url => ProviderModuleChecks.assertCallCancelsWhenInterrupted("initialize")(clientFor(url).initialize())
  }

  it should "return CancelledError from getTools, not an empty tool list, when its thread is interrupted" in
    withHeldServer { url =>
      ProviderModuleChecks.assertCallCancelsWhenInterrupted("getTools")(clientFor(url).getTools())
    }

  // ---- the stdio transport

  "StdioTransportImpl" should "return CancelledError and stop the server it started when interrupted during startup" in {
    assume(!isWindows, "needs a POSIX shell")
    val pidFile = Files.createTempFile("llm4s-mcp-", ".pid")
    // A server that starts and says nothing: the transport is still waiting for it to be ready.
    val transport =
      new StdioTransportImpl(Seq("sh", "-c", "echo $$ > '" + pidFile + "'; exec sleep 30", "sh"), "silent")
    ProviderModuleChecks.assertCallCancelsOnceReady("stdio sendRequest during startup")(
      transport.sendRequest(request)
    )(awaitContent(pidFile, "recorded its process id"))
    val stopped = programStopped(pidFile)
    transport.close()
    Files.deleteIfExists(pidFile): Unit
    assert(stopped, "the server was still running after the call was cancelled during startup")
  }

  it should "return CancelledError and keep no request pending when interrupted while awaiting a response" in {
    assume(!isWindows, "needs a POSIX shell")
    val pidFile = Files.createTempFile("llm4s-mcp-", ".pid")
    val gotFile = Files.createTempFile("llm4s-mcp-", ".got")
    // A server that is ready (it prints a line) and reads a request, but never answers it: once it has
    // read the request, the transport is known to be waiting for the response.
    val script =
      "echo $$ > '" + pidFile + "'; echo ready; read line; echo got > '" + gotFile + "'; exec sleep 30"
    val transport = new StdioTransportImpl(Seq("sh", "-c", script, "sh"), "mute")
    ProviderModuleChecks.assertCallCancelsOnceReady("stdio sendRequest awaiting a response")(
      transport.sendRequest(request)
    )(awaitContent(gotFile, "read the request"))
    Files.deleteIfExists(gotFile): Unit
    transport.close()
    val stopped = programStopped(pidFile)
    Files.deleteIfExists(pidFile): Unit
    assert(stopped, "the server was still running after the transport was closed")
  }

  // ---- the registry, end to end

  "MCPToolRegistry" should "report a tool call interrupted mid-flight as cancelled, not as a failure or an unknown tool" in {
    val registry = new MCPToolRegistry(
      Seq(MCPServerConfig("e2e", StreamableHTTPTransport(s"http://127.0.0.1:$port/mcp", "e2e"), 30.seconds)),
      initializeOnStartup = false
    )
    try {
      @volatile var outcome: Option[(Either[ToolCallError, ujson.Value], Boolean)] = None
      val worker = Thread.ofVirtual().start { () =>
        val result = registry.execute(ToolCallRequest("hang", ujson.Obj()))
        outcome = Some(result -> Thread.currentThread().isInterrupted)
      }
      hangStarted.await(10, TimeUnit.SECONDS) shouldBe true // the server is inside the tool
      worker.interrupt()
      worker.join(10_000)

      worker.isAlive shouldBe false
      outcome shouldBe Some(Left(ToolCallError.Cancelled("hang")) -> true)
    } finally registry.close()
  }

  it should "not cache an empty tool list when discovery is interrupted" in withHeldServer { url =>
    val registry = new MCPToolRegistry(
      Seq(MCPServerConfig("held", StreamableHTTPTransport(url, "held"), 30.seconds)),
      initializeOnStartup = false
    )
    try {
      @volatile var outcome: Option[(Either[ToolCallError, ujson.Value], Boolean)] = None
      val worker = Thread.ofVirtual().start { () =>
        val result = registry.execute(ToolCallRequest("anything", ujson.Obj()))
        outcome = Some(result -> Thread.currentThread().isInterrupted)
      }
      Thread.sleep(200) // the registry is asking the server which tools it has
      worker.interrupt()
      worker.join(10_000)

      worker.isAlive shouldBe false
      outcome shouldBe Some(Left(ToolCallError.Cancelled("anything")) -> true)
    } finally registry.close()
  }

  "the cancellation the MCP layers return" should "be a CancelledError, which no retry layer retries" in {
    LLMError.isRecoverable(CancelledError("mcp.http")) shouldBe false
  }
}
