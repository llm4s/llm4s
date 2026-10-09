package org.llm4s.mcp

import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ServerSocket
import scala.concurrent.duration.*

/**
 * The ways an MCP server can go wrong - answering with a JSON-RPC error, with garbage, or not at all, never
 * starting, or not being there - and what each layer reports. The servers are scripted `bash` loops that answer
 * each request by its method, so every message below is the product of a known reply.
 */
class MCPErrorPathsSpec extends AnyFlatSpec with Matchers {

  private val isWindows = System.getProperty("os.name").toLowerCase.contains("win")

  // ---- scripted servers

  private def result(json: String): String = s"""{"jsonrpc":"2.0","id":"$$id","result":$json}"""
  private def noResult: String             = """{"jsonrpc":"2.0","id":"$id"}"""
  private def rpcError(code: Int, message: String): String =
    s"""{"jsonrpc":"2.0","id":"$$id","error":{"code":$code,"message":"$message"}}"""

  private val initialized =
    result("""{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"scripted","version":"1"}}""")

  /** A server that answers each request by its method with the given reply (`$id` is the request's id). */
  private def server(replies: (String, String)*): Seq[String] = {
    val cases = replies
      .map { case (method, reply) => s"""      "$method") printf '%s\\n' "${reply.replace("\"", "\\\"")}" ;;""" }
      .mkString("\n")
    Seq(
      "bash",
      "-c",
      s"""echo ready >&2
         |while IFS= read -r line; do
         |  id=$$(echo "$$line" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
         |  method=$$(echo "$$line" | grep -o '"method":"[^"]*"' | head -1 | cut -d'"' -f4)
         |  [ -z "$$id" ] && continue
         |  case "$$method" in
         |$cases
         |  esac
         |done""".stripMargin
    )
  }

  private val toolsRequest = JsonRpcRequest(jsonrpc = "2.0", id = "1", method = "tools/list", params = None)

  private val notification = JsonRpcNotification("2.0", "notifications/initialized", None)

  private def transportFor(command: Seq[String]) = new StdioTransportImpl(command, "scripted", 2.seconds)

  private def failure[A](result: Result[A]): String = result.left.getOrElse(fail("expected a Left")).message

  /** A URL nothing listens on: the port was free a moment ago and is closed again. */
  private def deadUrl: String = {
    val socket = new ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    s"http://127.0.0.1:$port/mcp"
  }

  private def withClient[A](command: Seq[String])(test: MCPClientImpl => A): A = {
    val client = new MCPClientImpl(MCPServerConfig.stdio("scripted", command, 10.seconds))
    try test(client)
    finally client.close()
  }

  // ---- the stdio transport

  "StdioTransportImpl" should "report a JSON-RPC error response with its code and message" in {
    assume(!isWindows, "bash is not available on Windows")
    val transport = transportFor(server("tools/list" -> rpcError(-32601, "Method not found")))
    try failure(transport.sendRequest(toolsRequest)) shouldBe "JSON-RPC Error -32601: Method not found"
    finally transport.close()
  }

  it should "report a response that is JSON-RPC in name only as a parse failure" in {
    assume(!isWindows, "bash is not available on Windows")
    // It has the request's id, so it reaches the caller, and an `error` that is a string, not a code and a message.
    val transport = transportFor(server("tools/list" -> """{"jsonrpc":"2.0","id":"$id","error":"oops"}"""))
    try failure(transport.sendRequest(toolsRequest)) should startWith("Failed to parse response")
    finally transport.close()
  }

  it should "report a server that exits without answering, rather than waiting for it" in {
    assume(!isWindows, "bash is not available on Windows")
    val transport = transportFor(Seq("bash", "-c", "echo ready >&2; read line; exit 0"))
    try failure(transport.sendRequest(toolsRequest)) should include("Reader thread exited unexpectedly")
    finally transport.close()
  }

  it should "report a server process that dies while starting" in {
    assume(!isWindows, "bash is not available on Windows")
    // No output before exiting: the start-up wait reads any output as "ready", so a line written just before the
    // exit could let the process count as started and fail later, at the write, as a broken pipe.
    val transport = transportFor(Seq("bash", "-c", "exit 3"))
    val message   = failure(transport.sendRequest(toolsRequest))
    message should startWith("Failed to start MCP server process")
    message should include("died during startup")
  }

  it should "report a command that does not exist as a start failure" in {
    val transport = transportFor(Seq("/nonexistent/llm4s-mcp-test-server"))
    failure(transport.sendRequest(toolsRequest)) should startWith("Failed to start MCP server process")
  }

  // ---- the HTTP transports, notified at a server that is not there

  "StreamableHTTPTransportImpl" should "report a notification it cannot deliver" in {
    val transport = new StreamableHTTPTransportImpl(deadUrl, "dead", 2.seconds)
    failure(transport.sendNotification(notification)) should startWith("Notification error:")
  }

  "SSETransportImpl" should "report a notification it cannot deliver" in {
    val transport = new SSETransportImpl(deadUrl, "dead", 2.seconds)
    failure(transport.sendNotification(notification)) should startWith("Notification error:")
  }

  // ---- the client's handshake

  "MCPClientImpl.initialize" should "refuse a server that speaks a protocol version it does not know" in {
    assume(!isWindows, "bash is not available on Windows")
    val unknown = result("""{"protocolVersion":"1999-01-01","capabilities":{}}""")
    withClient(server("initialize" -> unknown)) { client =>
      failure(client.initialize()) shouldBe "Unsupported protocol version: 1999-01-01"
    }
  }

  it should "report an initialize reply that carries no result" in {
    assume(!isWindows, "bash is not available on Windows")
    withClient(server("initialize" -> noResult)) { client =>
      failure(client.initialize()) shouldBe "Initialize request failed: no result in response"
    }
  }

  it should "report an initialize result without a protocol version as an invalid format" in {
    assume(!isWindows, "bash is not available on Windows")
    withClient(server("initialize" -> result("{}"))) { client =>
      failure(client.initialize()) shouldBe "Invalid initialization response format"
    }
  }

  it should "say what the last transport reported when a server rejects both" in {
    // 405 on every request: the Streamable HTTP attempt is refused, and so is the HTTP+SSE fallback.
    LocalProviderTestServer.withServer("/")(ex => LocalProviderTestServer.sendJsonResponse(ex, 405, "{}")) { url =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("rejecting", url + "/mcp", 5.seconds))
      try {
        val message = failure(client.initialize())
        message should startWith("Failed to connect with both transports. Latest error: ")
        // The cause is its message, not the error object's toString.
        (message should not).include("SimpleError(")
        message should include("405")
      } finally client.close()
    }
  }

  it should "report a refused connection as a transport error without trying the fallback" in {
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("dead", deadUrl, 2.seconds))
    try {
      val message = failure(client.initialize())
      message should include("connection failed")
      (message should not).include("both transports")
    } finally client.close()
  }

  // ---- the client's tool listing

  "MCPClientImpl.getTools" should "fail, not look empty, when the server fails the listing" in {
    assume(!isWindows, "bash is not available on Windows")
    withClient(server("initialize" -> initialized, "tools/list" -> rpcError(-32603, "internal error"))) { client =>
      client.getTools().isLeft shouldBe true
    }
  }

  it should "fail when the listing carries no result" in {
    assume(!isWindows, "bash is not available on Windows")
    withClient(server("initialize" -> initialized, "tools/list" -> noResult)) { client =>
      client.getTools().isLeft shouldBe true
    }
  }

  it should "fail when the server cannot be initialized" in {
    assume(!isWindows, "bash is not available on Windows")
    val unknown = result("""{"protocolVersion":"1999-01-01","capabilities":{}}""")
    withClient(server("initialize" -> unknown))(client => client.getTools().isLeft shouldBe true)
  }

  // ---- annotations and hints

  "MCPToolAnnotations.toJson" should "write only the fields that are set" in {
    MCPToolAnnotations.toJson(MCPToolAnnotations()) shouldBe ujson.Obj()
    MCPToolAnnotations.toJson(MCPToolAnnotations(readOnlyHint = Some(true))) shouldBe
      ujson.Obj("readOnlyHint" -> true)
  }

  it should "write every hint and the title, and read back what it wrote" in {
    val all = MCPToolAnnotations(
      title = Some("Reader"),
      readOnlyHint = Some(true),
      destructiveHint = Some(false),
      idempotentHint = Some(true),
      openWorldHint = Some(false)
    )
    MCPToolAnnotations.toJson(all) shouldBe ujson.Obj(
      "title"           -> "Reader",
      "readOnlyHint"    -> true,
      "destructiveHint" -> false,
      "idempotentHint"  -> true,
      "openWorldHint"   -> false
    )
    MCPToolAnnotations.fromJson(MCPToolAnnotations.toJson(all)) shouldBe all
  }

  "an MCPClient that does not record hints" should "report none" in {
    val bare = new MCPClient {
      def getTools(): Result[Seq[ToolFunction[_, _]]] = Right(Seq.empty)
      def initialize(): Result[Unit]                  = Right(())
      def close(): Unit                               = ()
    }
    bare.getToolHints() shouldBe empty
  }
}
