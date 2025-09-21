package org.llm4s.mcp

import org.llm4s.toolapi.ToolFunction
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class MCPClientImplSpec extends AnyFlatSpec with Matchers with MockFactory with EitherValues {

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
              "parameters" -> ujson.Obj(
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
    val result: Either[String, Seq[ToolFunction[?, ?]]] = client.getTools()

    // Assert
    result.isRight shouldBe true
    result.value should be(Seq.empty[ToolFunction[?, ?]])
  }

  it should "return empty sequence when initialization fails" in {
    // Arrange
    val client        = new MCPClientImpl(config)
    val mockTransport = mock[MCPTransportImpl]

    (mockTransport.sendRequest _)
      .expects(*)
      .returning(Left("Initialization failed"))

    client.transport = Some(mockTransport)

    // Act
    val result = client.getTools()

    // Assert
    result.isRight shouldBe true
    result.value shouldBe empty
  }

  it should "return empty sequence when no transport is available" in {
    // Arrange
    val client = new MCPClientImpl(config)
    client.transport = None

    // Act
    val result = client.getTools()

    // Assert
    result.isRight shouldBe true
    result.value shouldBe empty
  }

  it should "handle invalid tool responses" in {
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
    result.isRight shouldBe true
    result.value shouldBe empty
  }
}
