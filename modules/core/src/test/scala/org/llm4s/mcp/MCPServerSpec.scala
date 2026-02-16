package org.llm4s.mcp

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.llm4s.toolapi.{ Schema, SafeParameterExtractor, ToolBuilder }
import scala.concurrent.duration._

class MCPServerSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  var server: MCPServer = _
  var port: Int         = _

  override def beforeAll(): Unit = {
    // 1. Define a simple tool
    val pingSchema = Schema
      .`object`[Map[String, Any]]("Ping parameters")
      .withProperty(Schema.property("message", Schema.string("Message to echo back")))

    def pingHandler(params: SafeParameterExtractor): Either[String, String] =
      params.getString("message").map(msg => s"Echo: $msg")

    val pingTool = ToolBuilder[Map[String, Any], String](
      "ping",
      "Echoes a message",
      pingSchema
    ).withHandler(pingHandler).build()

    // 2. Setup Server
    val options = MCPServerOptions(0, "/mcp", "TestServer", "1.0.0")
    server = new MCPServer(options, Seq(pingTool))
    server.start().fold(e => throw e, _ => ())
    // Give it a moment to bind (though bind is synchronous, 0 port assignment happens then)
    port = server.boundPort
  }

  override def afterAll(): Unit =
    if (server != null) server.stop()

  describe("MCPServer Integration") {

    it("should be discoverable by MCPClient") {
      // 3. Setup Client
      // Use StreamableHTTPTransport to connect to our server
      // Note: MCPServer uses Streamable HTTP protocol (single endpoint)
      val transport = StreamableHTTPTransport(s"http://127.0.0.1:$port/mcp", "test-client")
      val config    = MCPServerConfig("test-server", transport, 5.seconds)
      val client    = new MCPClientImpl(config)

      try {
        // Initialize handshake
        val initResult = client.initialize()
        initResult should be(Right(()))

        // Get Tools
        val toolsResult = client.getTools()
        toolsResult.isRight should be(true)
        val tools = toolsResult.getOrElse(Seq.empty)

        tools should have size 1
        tools.head.name should be("ping")

        // Execute Tool
        val args       = ujson.Obj("message" -> "Hello World")
        val execResult = tools.head.execute(args)

        execResult.isRight should be(true)
        val successMsg = execResult.toOption.get.str
        successMsg should include("Echo: Hello World")

      } finally
        client.close()
    }
  }
}
