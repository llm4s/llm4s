package org.llm4s.mcp

import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

/**
 * Round trips through a real, in-process [[MCPServer]]: client -> Streamable HTTP -> server ->
 * tool handler and back, for both `MCPClientImpl` and `MCPToolRegistry`.
 *
 * Needs no external service (the server binds an ephemeral loopback port), so it is an ordinary
 * unit suite rather than an integration one.
 */
class MCPEmbeddedServerRoundTripSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val AllTools = Set("ping", "fail", "echo", "add", "reverse")

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

    val echoSchema = Schema
      .`object`[Map[String, Any]]("Echo parameters")
      .withProperty(Schema.property("text", Schema.string("Text to echo")))
    val noArgSchema = Schema.`object`[Map[String, Any]]("No parameters")

    val tools = Seq(
      tool("ping", "Returns pong without arguments", noArgSchema)(_ => Right("pong")),
      tool("fail", "Always fails", noArgSchema)(_ => Left("kaboom-from-handler")),
      tool("echo", "Echoes the text back", echoSchema)(p => p.getString("text")),
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
      tools.map(_.name).toSet shouldBe AllTools
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

  it should "mark every declared parameter of add as required in the advertised schema" in withClient { client =>
    val params = toolNamed(client, "add").toOpenAITool(strict = false)("function")("parameters")
    params("required").arr.map(_.str).toSet shouldBe Set("a", "b")
    params("properties")("a")("type").str shouldBe "integer"
  }

  it should "execute a tool that takes no arguments" in withClient { client =>
    toolNamed(client, "ping").execute(ujson.Obj()).map(_.str) shouldBe Right("pong")
  }

  it should "propagate a tool handler failure to the caller as Left carrying the handler message" in withClient {
    client =>
      val result = toolNamed(client, "fail").execute(ujson.Obj())
      result.isLeft shouldBe true
      result.left.toOption.map(_.toString).getOrElse("") should include("kaboom-from-handler")
  }

  it should "round-trip unicode text unchanged" in withClient { client =>
    val text = "héllo 日本語 😀 \"quoted\" \\ back\nnewline"
    toolNamed(client, "echo").execute(ujson.Obj("text" -> text)).map(_.str) shouldBe Right(text)
  }

  it should "round-trip a payload of about one megabyte" in withClient { client =>
    val text = "x" * (1024 * 1024)
    toolNamed(client, "echo").execute(ujson.Obj("text" -> text)).map(_.str.length) shouldBe Right(text.length)
  }

  it should "return each caller its own result under concurrent calls on one client" in withClient { client =>
    implicit val ec: ExecutionContext = ExecutionContext.global
    val add                           = toolNamed(client, "add")
    val calls   = (1 to 24).map(i => Future(i -> add.execute(ujson.Obj("a" -> i, "b" -> (i * 100)))))
    val results = Await.result(Future.sequence(calls), 60.seconds)
    results.foreach { case (i, r) => r shouldBe Right(ujson.Num(i * 101)) }
  }

  it should "fail a call made after the client was closed instead of hanging" in {
    val transport = StreamableHTTPTransport(s"http://127.0.0.1:$port/mcp", "closed-client")
    val client    = new MCPClientImpl(MCPServerConfig("closed", transport, 10.seconds))
    client.initialize() shouldBe Right(())
    val ping = toolNamed(client, "ping")
    client.close()
    ping.execute(ujson.Obj()).isLeft shouldBe true
  }

  it should "fail a call once the server has gone away" in {
    val pingTool = tool("ping", "ping", Schema.`object`[Map[String, Any]]("none"))(_ => Right("pong"))
    val live     = new MCPServer(MCPServerOptions(0, "/mcp", "Live", "1.0.0"), Seq(pingTool))
    live.start().fold(e => throw e, _ => ())
    val client = new MCPClientImpl(
      MCPServerConfig("gone", StreamableHTTPTransport(s"http://127.0.0.1:${live.boundPort}/mcp", "gone"), 5.seconds)
    )
    try {
      client.initialize() shouldBe Right(())
      val ping = toolNamed(client, "ping")
      ping.execute(ujson.Obj()).map(_.str) shouldBe Right("pong")
      live.stop()
      ping.execute(ujson.Obj()).isLeft shouldBe true
    } finally client.close()
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
      registry.getAllTools.map(_.name).toSet shouldBe AllTools
      registry.execute(ToolCallRequest("add", ujson.Obj("a" -> 20, "b" -> 22))) shouldBe Right(ujson.Num(42))
      registry.execute(ToolCallRequest("nope", ujson.Obj())).isLeft shouldBe true
      registry.execute(ToolCallRequest("fail", ujson.Obj())).isLeft shouldBe true
      registry.execute(ToolCallRequest("ping", ujson.Obj())) shouldBe Right(ujson.Str("pong"))
    } finally registry.close()
  }
}
