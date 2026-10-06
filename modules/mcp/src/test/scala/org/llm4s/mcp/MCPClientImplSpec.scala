package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.toolapi.ToolFunction
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Outcome
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

class MCPClientImplSpec extends AnyFlatSpec with Matchers with MockFactory with EitherValues {

  override def withFixture(test: NoArgTest): Outcome = {
    val clientLogger = LoggerFactory.getLogger("org.llm4s.mcp.MCPClientImpl").asInstanceOf[LBLogger]
    val stdioLogger  = LoggerFactory.getLogger("org.llm4s.mcp.StdioTransportImpl").asInstanceOf[LBLogger]

    val previousClientLevel = clientLogger.getLevel
    val previousStdioLevel  = stdioLogger.getLevel

    clientLogger.setLevel(Level.OFF)
    stdioLogger.setLevel(Level.OFF)

    try super.withFixture(test)
    finally {
      clientLogger.setLevel(previousClientLevel)
      stdioLogger.setLevel(previousStdioLevel)
    }
  }

  // Test fixtures
  val config = MCPServerConfig.stdio(
    name = "test-server",
    command = Seq("test-command"),
    timeout = 30.seconds
  )

  "MCPClientImpl.getTools" should "return tools when initialization succeeds" in {
    // Arrange
    val client        = new MCPClientImpl(config)
    val mockTransport = mock[MCPTransportImpl]

    // Initialize response
    val initResponse = JsonRpcResponse(
      jsonrpc = "2.0",
      id = "1",
      result = Some(ujson.Obj("protocolVersion" -> "2025-06-18"))
    )

    // Tools response
    val toolsResponse = JsonRpcResponse(
      jsonrpc = "2.0",
      id = "2",
      result = Some(
        ujson.Obj(
          "tools" -> ujson.Arr(
            ujson.Obj(
              "name"        -> "test-tool",
              "description" -> "A test tool",
              "inputSchema" -> ujson.Obj(
                "type"       -> "object",
                "properties" -> ujson.Obj()
              )
            )
          )
        )
      )
    )

    // Setup expectations
    (mockTransport.sendRequest _)
      .expects(where((req: JsonRpcRequest) => req.method == "initialize"))
      .returning(Right(initResponse))

    (mockTransport.sendNotification _)
      .expects(where((notif: JsonRpcNotification) => notif.method == "notifications/initialized"))
      .returning(Right(()))

    val request = JsonRpcRequest("2.0", "2", "tools/list", None)
    (mockTransport.sendRequest _)
      .expects(request)
      .returning(Right(toolsResponse))

    client.transport = Some(mockTransport)
    // Act
    val result: org.llm4s.types.Result[Seq[ToolFunction[?, ?]]] = client.getTools()

    // Assert
    result.map(_.map(_.name)) shouldBe Right(Seq("test-tool"))
  }

  it should "fail when initialization fails" in {
    // Arrange
    val client        = new MCPClientImpl(config)
    val mockTransport = mock[MCPTransportImpl]

    (mockTransport.sendRequest _)
      .expects(*)
      .returning(Left(org.llm4s.error.SimpleError("Initialization failed")))

    client.transport = Some(mockTransport)

    // Act
    val result = client.getTools()

    // Assert
    result.isLeft shouldBe true
  }

  it should "fail when no transport is available" in {
    // Arrange
    val client = new MCPClientImpl(config)
    client.transport = None

    // Act
    val result = client.getTools()

    // Assert
    result.isLeft shouldBe true
  }

  it should "fail on an invalid tool listing" in {
    // Arrange
    val client        = new MCPClientImpl(config)
    val mockTransport = mock[MCPTransportImpl]

    val p = JsonRpcRequest(
      "2.0",
      "1",
      "initialize",
      Some(ujson.Value("""
          |{"protocolVersion":"2025-06-18","capabilities":{"tools":{},"roots":{"listChanged":false},"sampling":{}},"clientInfo":{"name":"llm4s-mcp","version":"1.0.0"}}
          |""".stripMargin))
    )

    // Mock successful initialization but invalid tools response
    (mockTransport.sendRequest _)
      .expects(p)
      .returning(
        Right(
          JsonRpcResponse(
            "2.0",
            "1",
            Some(ujson.Obj("protocolVersion" -> "2025-06-18"))
          )
        )
      )

    client.transport = Some(mockTransport)
    // Act
    val result = client.getTools()

    // Assert
    result.isLeft shouldBe true
  }
}
