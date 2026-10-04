package org.llm4s.mcp

import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * Round trips through a real, in-process [[MCPServer]]: client -> Streamable HTTP -> server ->
 * tool handler and back, for both `MCPClientImpl` and `MCPToolRegistry`.
 *
 * Needs no external service (the server binds an ephemeral loopback port), so it is an ordinary
 * unit suite rather than an integration one.
 */
class MCPEmbeddedServerRoundTripSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var server: MCPServer = _
  private var port: Int         = -1

  private def tool(name: String, description: String, schema: ObjectSchema[Map[String, Any]])(
    handler: SafeParameterExtractor => Either[String, String]
  ): ToolFunction[_, _] =
    ToolBuilder[Map[String, Any], String](name, description, schema)
      .withHandler(handler)
      .buildSafe()
      .fold(e => fail(s"could not build tool $name: ${e.formatted}"), identity)

  override def beforeAll(): Unit = {
    val addSchema = Schema
      .`object`[Map[String, Any]]("Add parameters")
      .withProperty(Schema.property("a", Schema.integer("First operand")))
      .withProperty(Schema.property("b", Schema.integer("Second operand")))
    val reverseSchema = Schema
      .`object`[Map[String, Any]]("Reverse parameters")
      .withProperty(Schema.property("text", Schema.string("Text to reverse")))

    val tools = Seq(
      tool("add", "Returns the sum of two integers", addSchema)(p =>
        for {
          a <- p.getInt("a")
          b <- p.getInt("b")
        } yield (a + b).toString
      ),
      tool("reverse", "Returns the reversed string", reverseSchema)(p => p.getString("text").map(_.reverse))
    )

    server = new MCPServer(MCPServerOptions(0, "/mcp", "RoundTripServer", "1.0.0"), tools)
    server.start().fold(e => throw e, _ => ())
    port = server.boundPort
  }

  override def afterAll(): Unit =
    if (server != null) server.stop()

  private def withClient[A](f: MCPClientImpl => A): A = {
    val transport = StreamableHTTPTransport(s"http://127.0.0.1:$port/mcp", "round-trip-client")
    val client    = new MCPClientImpl(MCPServerConfig("round-trip", transport, 10.seconds))
    try {
      client.initialize() shouldBe Right(())
      f(client)
    } finally client.close()
  }

  private def toolNamed(client: MCPClientImpl, name: String): ToolFunction[_, _] =
    client.getTools().getOrElse(Seq.empty).find(_.name == name).getOrElse(fail(s"tool '$name' not advertised"))

  "MCPClientImpl against an embedded server" should "advertise exactly the registered tools with their descriptions" in
    withClient { client =>
      val tools = client.getTools().getOrElse(fail("getTools failed"))
      tools.map(_.name).toSet shouldBe Set("add", "reverse")
      tools.find(_.name == "add").map(_.description) shouldBe Some("Returns the sum of two integers")
    }

  it should "advertise the parameter names of each tool" in withClient { client =>
    val json = toolNamed(client, "add").toOpenAITool().toString
    json should include("\"a\"")
    json should include("\"b\"")
  }

  it should "execute add and return the computed sum" in withClient { client =>
    toolNamed(client, "add").execute(ujson.Obj("a" -> 3, "b" -> 4)) shouldBe Right(ujson.Num(7))
  }

  it should "execute reverse and return the reversed text" in withClient { client =>
    toolNamed(client, "reverse").execute(ujson.Obj("text" -> "hello")).map(_.str) shouldBe Right("olleh")
  }

  it should "surface a failure when a required argument is missing" in withClient { client =>
    toolNamed(client, "add").execute(ujson.Obj("a" -> 3)).isLeft shouldBe true
  }

  "MCPToolRegistry against an embedded server" should "discover the server's tools and execute them" in {
    val config = MCPServerConfig.streamableHTTP("registry-round-trip", s"http://127.0.0.1:$port/mcp", 10.seconds)
    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq.empty,
      cacheTTL = 5.minutes,
      initializeOnStartup = false
    )
    try {
      registry.getAllTools.map(_.name).toSet shouldBe Set("add", "reverse")
      registry.execute(ToolCallRequest("add", ujson.Obj("a" -> 20, "b" -> 22))) shouldBe Right(ujson.Num(42))
      registry.execute(ToolCallRequest("nope", ujson.Obj())).isLeft shouldBe true
    } finally registry.close()
  }
}
