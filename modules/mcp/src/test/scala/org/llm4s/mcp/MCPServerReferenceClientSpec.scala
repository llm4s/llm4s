package org.llm4s.mcp

import io.modelcontextprotocol.client.{ McpClient, McpSyncClient }
import io.modelcontextprotocol.client.transport.{ HttpClientSseClientTransport, HttpClientStreamableHttpTransport }
import io.modelcontextprotocol.spec.{ McpError, McpSchema }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * `MCPServer` driven by the official MCP Java SDK's client (#3200), over Streamable HTTP and the legacy HTTP+SSE
 * transport: the handshake, `ping`, `tools/list` with annotations, a tool call, and a protocol error. The SDK reads
 * our replies as the specification defines them, so a reply without `jsonrpc`, an id of the wrong type, or a
 * capability it cannot read fails here, as it would for any real client.
 */
class MCPServerReferenceClientSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val echo = ToolBuilder[ujson.Value](
    "echo",
    "Echoes its text",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("text", Schema.string("Text to echo"))
  ).withHandler(params => params.getString("text").map(text => ujson.Obj("echoed" -> text): ujson.Value))
    .build()
    .fold(e => fail(e.formatted), identity)

  private val server = new MCPServer(
    MCPServerOptions(0, "/mcp", "ReferenceClientServer", "1.2.3"),
    Seq(echo),
    Map("echo" -> MCPToolAnnotations(readOnlyHint = Some(true)))
  )

  override def beforeAll(): Unit = server.start().fold(e => throw e, _ => ())
  override def afterAll(): Unit  = server.stop()

  private def base = s"http://127.0.0.1:${server.boundPort}"

  private def streamable(): McpSyncClient =
    McpClient
      .sync(HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp").build())
      .requestTimeout(Duration.ofSeconds(10))
      .build()

  private def legacySse(): McpSyncClient =
    McpClient
      .sync(HttpClientSseClientTransport.builder(base).sseEndpoint("/mcp/sse").build())
      .requestTimeout(Duration.ofSeconds(10))
      .build()

  private def conversation(client: McpSyncClient): Unit = {
    val init = client.initialize()
    init.serverInfo().name() shouldBe "ReferenceClientServer"
    init.serverInfo().version() shouldBe "1.2.3"
    Option(init.capabilities().tools()) should not be empty

    client.ping()

    val tools = client.listTools().tools().asScala
    tools.map(_.name()) shouldBe Seq("echo")
    tools.head.annotations().readOnlyHint() shouldBe true

    val call = client.callTool(
      McpSchema.CallToolRequest.builder("echo").arguments(Map[String, Object]("text" -> "hi").asJava).build()
    )
    call.isError() shouldBe false
    call.content().asScala.collect { case t: McpSchema.TextContent => t.text() }.mkString should include("hi")
    Option(call.structuredContent()).map(_.toString) shouldBe Some("{echoed=hi}")

    val unknown = Try(
      client.callTool(McpSchema.CallToolRequest.builder("nope").arguments(Map.empty[String, Object].asJava).build())
    )
    val error = unknown.failed.toOption.flatMap(e => Option(McpError.findRootCause(e))).orElse(unknown.failed.toOption)
    error.collect { case e: McpError => e.getJsonRpcError.code() } shouldBe Some(MCPErrorCodes.INVALID_PARAMS)
  }

  "MCPServer" should "serve the official MCP Java SDK client over Streamable HTTP" in {
    Using.resource(streamable())(conversation)
  }

  it should "serve the official MCP Java SDK client over the legacy HTTP+SSE transport" in {
    Using.resource(legacySse())(conversation)
  }
}
