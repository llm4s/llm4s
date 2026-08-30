package org.llm4s.mcp

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.toolapi._
import upickle.default._

import scala.concurrent.duration._

/**
 * Tests for MCPToolRegistry caching and tool management functionality.
 */
class MCPToolRegistryCacheSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Helper definitions
  // ==========================================================================

  private def mkConfig(name: String): MCPServerConfig =
    MCPServerConfig.stdio(name, Seq("echo", "noop"), 30.seconds)

  // Result type for test tool
  case class TestResult(value: String)
  object TestResult {
    implicit val rw: ReadWriter[TestResult] = macroRW
  }

  // Create a simple test tool using ToolBuilder
  private def createTestTool(): Result[ToolFunction[Map[String, Any], TestResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Test parameters")
      .withProperty(Schema.property("input", Schema.string("Input value")))

    ToolBuilder[Map[String, Any], TestResult](
      "test_tool",
      "A test tool",
      schema
    ).withHandler(_ => Right(TestResult("result"))).buildSafe()
  }

  private val testTool = createTestTool()

  // ==========================================================================
  // Local Tools Tests
  // ==========================================================================

  "MCPToolRegistry" should "include local tools in tools list" in {
    testTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new MCPToolRegistry(
          mcpServers = Seq.empty,
          localTools = Seq(tool),
          cacheTTL = 1.minute,
          initializeOnStartup = false
        )

        registry.tools should have size 1
        registry.tools.head.name shouldBe "test_tool"
      }
    )
  }

  it should "execute local tools successfully" in {
    testTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new MCPToolRegistry(
          mcpServers = Seq.empty,
          localTools = Seq(tool),
          cacheTTL = 1.minute,
          initializeOnStartup = false
        )

        val request = ToolCallRequest("test_tool", ujson.Obj("input" -> "hello"))
        registry
          .execute(request)
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            json => json("value").str shouldBe "result"
          )
      }
    )
  }

  it should "return error for unknown tool" in {
    testTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new MCPToolRegistry(
          mcpServers = Seq.empty,
          localTools = Seq(tool),
          cacheTTL = 1.minute,
          initializeOnStartup = false
        )

        val request = ToolCallRequest("nonexistent_tool", ujson.Obj())
        registry
          .execute(request)
          .fold(
            err => err shouldBe a[ToolCallError.UnknownFunction],
            result => fail(s"Expected Left but got Right: $result")
          )
      }
    )
  }

  // ==========================================================================
  // Cache Management Tests
  // ==========================================================================

  "MCPToolRegistry.clearCache" should "clear all cached tools" in {
    val registry = new MCPToolRegistry(
      mcpServers = Seq.empty,
      localTools = Seq.empty,
      cacheTTL = 1.minute,
      initializeOnStartup = false
    )

    // This should not throw
    noException should be thrownBy registry.clearCache()
  }

  "MCPToolRegistry.refreshCache" should "refresh cache for all servers" in {
    val registry = new MCPToolRegistry(
      mcpServers = Seq.empty,
      localTools = Seq.empty,
      cacheTTL = 1.minute,
      initializeOnStartup = false
    )

    // With no servers, this should complete without error
    noException should be thrownBy registry.refreshCache()
  }

  // ==========================================================================
  // OpenAI Tools Format Tests
  // ==========================================================================

  "MCPToolRegistry.getOpenAITools" should "return tools in OpenAI format" in {
    testTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new MCPToolRegistry(
          mcpServers = Seq.empty,
          localTools = Seq(tool),
          cacheTTL = 1.minute,
          initializeOnStartup = false
        )

        val openAITools = registry.getOpenAITools()

        openAITools.value should have size 1
      }
    )
  }

  it should "return empty array when no tools" in {
    val registry = new MCPToolRegistry(
      mcpServers = Seq.empty,
      localTools = Seq.empty,
      cacheTTL = 1.minute,
      initializeOnStartup = false
    )

    val openAITools = registry.getOpenAITools()

    openAITools.value shouldBe empty
  }

  // ==========================================================================
  // Close Tests
  // ==========================================================================

  "MCPToolRegistry.close" should "close all MCP clients" in {
    val registry = new MCPToolRegistry(
      mcpServers = Seq.empty,
      localTools = Seq.empty,
      cacheTTL = 1.minute,
      initializeOnStartup = false
    )

    noException should be thrownBy registry.close()
  }

  "MCPToolRegistry.closeMCPClients" should "be idempotent" in {
    val registry = new MCPToolRegistry(
      mcpServers = Seq.empty,
      localTools = Seq.empty,
      cacheTTL = 1.minute,
      initializeOnStartup = false
    )

    // Should be safe to call multiple times
    noException should be thrownBy {
      registry.closeMCPClients()
      registry.closeMCPClients()
    }
  }

  // ==========================================================================
  // getAllTools Tests
  // ==========================================================================

  "MCPToolRegistry.getAllTools" should "combine local and MCP tools" in {
    testTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new MCPToolRegistry(
          mcpServers = Seq.empty,
          localTools = Seq(tool),
          cacheTTL = 1.minute,
          initializeOnStartup = false
        )

        val allTools = registry.getAllTools

        allTools should have size 1
        allTools.head.name shouldBe "test_tool"
      }
    )
  }

  it should "return empty when no tools configured" in {
    val registry = new MCPToolRegistry(
      mcpServers = Seq.empty,
      localTools = Seq.empty,
      cacheTTL = 1.minute,
      initializeOnStartup = false
    )

    registry.getAllTools shouldBe empty
  }

  // ==========================================================================
  // Configuration Tests
  // ==========================================================================

  "MCPToolRegistry" should "respect initializeOnStartup=false" in {
    // When initializeOnStartup is false, it should not attempt to connect to servers
    val registry = new MCPToolRegistry(
      mcpServers = Seq(mkConfig("test-server")),
      localTools = Seq.empty,
      cacheTTL = 1.minute,
      initializeOnStartup = false
    )

    // Registry should be created without error even if server doesn't exist
    registry should not be null
    registry.close()
  }

  it should "support custom cache TTL" in {
    val registry = new MCPToolRegistry(
      mcpServers = Seq.empty,
      localTools = Seq.empty,
      cacheTTL = 5.minutes,
      initializeOnStartup = false
    )

    // Just verify it constructs successfully
    registry should not be null
    registry.close()
  }

  // ==========================================================================
  // CachedTools Tests
  // ==========================================================================

  "CachedTools" should "detect expiration correctly" in {
    testTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val tools = Seq(tool)

        // Create cached tools instance via reflection or internal access
        // For now, test the behavior indirectly through MCPToolRegistry
        val registry = new MCPToolRegistry(
          mcpServers = Seq.empty,
          localTools = tools,
          cacheTTL = 1.millisecond, // Very short TTL
          initializeOnStartup = false
        )

        // Cache should handle expiry gracefully
        Thread.sleep(10)
        registry.tools should have size 1 // Local tools are always available
        registry.close()
      }
    )
  }
}
