package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, SimpleError }
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * What `MCPToolRegistry` does with a server whose client fails: a failed listing drops the client and closes it, so
 * the next call connects afresh; a cancelled one drops nothing; a client that cannot even be created is a server
 * with no tools, not an exception; and `healthCheck` reports each server's handshake. The clients are stubs.
 */
class MCPToolRegistryFailurePathsSpec extends AnyFlatSpec with Matchers {

  private class StubClient(init: Result[Unit], tools: Result[Seq[ToolFunction[?, ?]]]) extends MCPClient {
    val closes                                               = new AtomicInteger(0)
    override def initialize(): Result[Unit]                  = init
    override def getTools(): Result[Seq[ToolFunction[?, ?]]] = tools
    override def close(): Unit                               = closes.incrementAndGet(): Unit
  }

  private val config = MCPServerConfig.streamableHTTP("stub", "http://127.0.0.1:1/mcp", 5.seconds)

  private def registryOver(make: MCPServerConfig => MCPClient): MCPToolRegistry =
    new MCPToolRegistry(Seq(config), initializeOnStartup = false) {
      override private[mcp] def createMCPClient(server: MCPServerConfig): MCPClient = make(server)
    }

  "MCPToolRegistry" should "drop and close a client whose tool listing failed, and report no tools" in {
    val stub     = new StubClient(Right(()), Left(SimpleError("server error")))
    val registry = registryOver(_ => stub)

    registry.getAllTools shouldBe empty
    stub.closes.get shouldBe 1 // evicted, so the next call connects afresh
  }

  it should "keep the client when the tool listing was only cancelled" in {
    val stub     = new StubClient(Right(()), Left(CancelledError("tools/list")))
    val registry = registryOver(_ => stub)

    registry.getAllTools shouldBe empty
    stub.closes.get shouldBe 0
  }

  it should "treat a client that cannot be created as a server with no tools, not an exception" in {
    val registry = registryOver(_ => throw new IllegalStateException("cannot create"))

    registry.getAllTools shouldBe empty
  }

  "healthCheck" should "report true for a server whose handshake succeeds and false for one whose fails" in {
    val healthy   = registryOver(_ => new StubClient(Right(()), Right(Seq.empty)))
    val unhealthy = registryOver(_ => new StubClient(Left(SimpleError("refused")), Right(Seq.empty)))

    healthy.healthCheck() shouldBe Map("stub" -> true)
    unhealthy.healthCheck() shouldBe Map("stub" -> false)
  }
}
