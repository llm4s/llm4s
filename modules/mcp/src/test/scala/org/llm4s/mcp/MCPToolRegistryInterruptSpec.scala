package org.llm4s.mcp

import org.llm4s.toolapi.{ ObjectSchema, SafeParameterExtractor, ToolCallError, ToolCallRequest, ToolFunction }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * The rule of design section 4.4 for an MCP tool call: a call that ends cancelled leaves the caller's thread
 * interrupted, whichever way the cancellation reached the registry. A tool that wraps an `InterruptedException`
 * in an exception of its own has already cleared the flag by the time the registry sees it, so the registry
 * must set it again: otherwise the caller gets `Cancelled` and can no longer tell that its thread was
 * interrupted.
 */
class MCPToolRegistryInterruptSpec extends AnyFlatSpec with Matchers {

  private def tool(name: String)(body: => Nothing): ToolFunction[Map[String, Any], String] =
    new ToolFunction[Map[String, Any], String](
      name,
      "a tool that fails",
      ObjectSchema[Map[String, Any]]("no parameters", Seq.empty),
      (_: SafeParameterExtractor) => body
    )

  /** A registry whose one MCP server hands out `tool`, without a network. */
  private def registryWith(tool: ToolFunction[?, ?]): MCPToolRegistry = {
    val client = new MCPClient {
      override def initialize(): Result[Unit]                  = Right(())
      override def getTools(): Result[Seq[ToolFunction[?, ?]]] = Right(Seq(tool))
      override def close(): Unit                               = ()
    }
    new MCPToolRegistry(
      Seq(MCPServerConfig.streamableHTTP("stub", "http://127.0.0.1:1/mcp", 5.seconds)),
      initializeOnStartup = false
    ) {
      override private[mcp] def createMCPClient(server: MCPServerConfig): MCPClient = client
    }
  }

  /** Runs `call` on a thread whose interrupt flag starts clear, and reports the result and the flag after it. */
  private def callWithClearFlag(
    registry: MCPToolRegistry,
    name: String
  ): (Either[ToolCallError, ujson.Value], Boolean) = {
    Thread.interrupted(): Unit // start clear, whatever an earlier test left
    val result = registry.execute(ToolCallRequest(name, ujson.Obj()))
    val flag   = Thread.interrupted() // reads and clears, so it cannot leak into the next test
    (result, flag)
  }

  "MCPToolRegistry.execute" should "set the interrupt flag again when a tool wrapped the interruption in its own exception" in {
    val registry =
      registryWith(tool("wrapped")(throw new RuntimeException("tool failed", new InterruptedException("cancelled"))))
    try {
      val (result, flagAfter) = callWithClearFlag(registry, "wrapped")

      result shouldBe Left(ToolCallError.Cancelled("wrapped"))
      flagAfter shouldBe true // the caller can still see that its thread was interrupted
    } finally registry.close()
  }

  it should "find the interruption however deep in the exception's causes it sits" in {
    val deep =
      new RuntimeException("outer", new IllegalStateException("middle", new InterruptedException("cancelled")))
    val registry = registryWith(tool("deep")(throw deep))
    try {
      val (result, flagAfter) = callWithClearFlag(registry, "deep")

      result shouldBe Left(ToolCallError.Cancelled("deep"))
      flagAfter shouldBe true
    } finally registry.close()
  }

  it should "set the flag again when the tool threw the InterruptedException itself" in {
    val registry = registryWith(tool("direct")(throw new InterruptedException("cancelled")))
    try {
      val (result, flagAfter) = callWithClearFlag(registry, "direct")

      result shouldBe Left(ToolCallError.Cancelled("direct"))
      flagAfter shouldBe true
    } finally registry.close()
  }

  it should "report an ordinary failure as one, and leave the flag clear" in {
    val registry = registryWith(tool("broken")(throw new IllegalStateException("boom")))
    try {
      val (result, flagAfter) = callWithClearFlag(registry, "broken")

      result should matchPattern { case Left(ToolCallError.ExecutionError("broken", _)) => }
      flagAfter shouldBe false // nothing was cancelled, so nothing is interrupted
    } finally registry.close()
  }
}
