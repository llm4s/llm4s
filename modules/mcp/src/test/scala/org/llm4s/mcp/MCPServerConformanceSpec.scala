package org.llm4s.mcp

import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.*

/**
 * `MCPServer` against the MCP specification on the wire (#3081): `ping`, protocol versions, session expiry and the
 * session cap, 404 for an unknown session, tool annotations in `tools/list`, and `notifications/cancelled`.
 */
class MCPServerConformanceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val started               = new CountDownLatch(1)
  @volatile private var interrupted = false

  private def tool(name: String)(run: => String): ToolFunction[String] =
    ToolBuilder[String](name, s"The $name tool", Schema.`object`[Map[String, Any]]("No parameters"))
      .withHandler(_ => Right(run))
      .build()
      .fold(e => fail(e.formatted), identity)

  private val echo = tool("echo")("echoed")
  private val slow = tool("slow") {
    started.countDown()
    // Runs until it is interrupted, which only a cancellation does
    org.llm4s.error.CancelledError.catchInterrupt(Thread.sleep(30_000)).left.foreach(_ => interrupted = true)
    "finished"
  }

  /** A server whose session clock is the spec's. */
  private class Clocked(options: MCPServerOptions, val time: AtomicLong = new AtomicLong(0))
      extends MCPServer(
        options,
        Seq(echo, slow),
        Map("echo" -> MCPToolAnnotations(title = Some("Echo"), readOnlyHint = Some(true)))
      ) {
    override private[mcp] def nowMillis(): Long = time.get
  }

  private val server = new Clocked(MCPServerOptions(0, "/mcp", "Conformance", "1.0", sessionIdleTimeout = 1.minute))
  private val http   = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  override def beforeAll(): Unit = server.start().fold(e => throw e, _ => ())
  override def afterAll(): Unit  = server.stop()

  private def post(on: MCPServer, body: ujson.Value, headers: (String, String)*): HttpResponse[String] = {
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:${on.boundPort}/mcp"))
      .timeout(java.time.Duration.ofSeconds(30))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
    headers.foreach { case (k, v) => builder.header(k, v) }
    http.send(
      builder.POST(HttpRequest.BodyPublishers.ofString(body.render())).build(),
      HttpResponse.BodyHandlers.ofString()
    )
  }

  private def request(id: Int, method: String, params: ujson.Value = ujson.Obj()): ujson.Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)

  private def initialize(on: MCPServer = server, version: String = "2025-06-18"): (String, ujson.Value) = {
    val reply =
      post(on, request(0, "initialize", ujson.Obj("protocolVersion" -> version, "capabilities" -> ujson.Obj())))
    reply.statusCode() shouldBe 200
    val session = reply.headers().firstValue("mcp-session-id").orElseThrow()
    session -> ujson.read(reply.body())
  }

  private def inSession(session: String, body: ujson.Value, on: MCPServer = server) =
    post(on, body, "mcp-session-id" -> session, "MCP-Protocol-Version" -> "2025-06-18")

  "MCPServer" should "answer ping with an empty result" in {
    val (session, _) = initialize()
    val reply        = inSession(session, request(1, "ping"))
    reply.statusCode() shouldBe 200
    ujson.read(reply.body())("result") shouldBe ujson.Obj()
  }

  it should "accept the client's supported version, and answer an unknown one with the latest" in {
    initialize(version = "2025-03-26")._2("result")("protocolVersion").str shouldBe "2025-03-26"
    initialize(version = "2024-11-05")._2("result")("protocolVersion").str shouldBe "2024-11-05"
    initialize(version = "1999-01-01")._2("result")("protocolVersion").str shouldBe MCPServer.LatestVersion
  }

  it should "take a request without MCP-Protocol-Version as 2025-03-26, and refuse an unsupported one with 400" in {
    val (session, _) = initialize()
    post(server, request(2, "tools/list"), "mcp-session-id" -> session).statusCode() shouldBe 200
    val refused =
      post(server, request(3, "tools/list"), "mcp-session-id" -> session, "MCP-Protocol-Version" -> "1999-01-01")
    refused.statusCode() shouldBe 400
    ujson.read(refused.body())("id") shouldBe ujson.Num(3)
  }

  it should "answer 404 for an unknown session, a notification on one included" in {
    val unknown = inSession("not-a-session", request(4, "tools/list"))
    unknown.statusCode() shouldBe 404
    ujson.read(unknown.body())("id") shouldBe ujson.Num(4)
    inSession("not-a-session", ujson.Obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized"))
      .statusCode() shouldBe 404
  }

  it should "expire a session that has gone unused for sessionIdleTimeout, and keep one in use" in {
    val (used, _) = initialize()
    val (idle, _) = initialize()
    server.time.addAndGet(40.seconds.toMillis)
    inSession(used, request(5, "ping")).statusCode() shouldBe 200
    server.time.addAndGet(40.seconds.toMillis) // idle: 80 s unused; used: 40 s
    inSession(idle, request(6, "ping")).statusCode() shouldBe 404
    inSession(used, request(7, "ping")).statusCode() shouldBe 200
  }

  it should "keep at most maxSessions, evicting the one unused for longest" in {
    val capped = new Clocked(MCPServerOptions(0, "/mcp", "Capped", "1.0", maxSessions = 2))
    capped.start().fold(e => throw e, _ => ())
    try {
      val (first, _) = initialize(capped)
      capped.time.incrementAndGet()
      val (second, _) = initialize(capped)
      capped.time.incrementAndGet()
      initialize(capped)
      capped.sessionCount shouldBe 2
      inSession(first, request(8, "ping"), capped).statusCode() shouldBe 404
      inSession(second, request(9, "ping"), capped).statusCode() shouldBe 200
    } finally capped.stop()
  }

  it should "advertise the annotations it was given for a tool" in {
    val (session, _) = initialize()
    val tools        = ujson.read(inSession(session, request(10, "tools/list")).body())("result")("tools").arr
    val listed       = tools.find(_("name").str == "echo").getOrElse(fail("echo not listed"))
    listed("annotations") shouldBe ujson.Obj("title" -> "Echo", "readOnlyHint" -> true)
    tools.find(_("name").str == "slow").map(_.obj.contains("annotations")) shouldBe Some(false)
  }

  it should "answer an unknown tool with invalid params" in {
    val (session, _) = initialize()
    val reply        = ujson.read(inSession(session, request(11, "tools/call", ujson.Obj("name" -> "nope"))).body())
    reply("error")("code").num shouldBe MCPErrorCodes.INVALID_PARAMS
  }

  it should "interrupt a tool call the client cancels, and send no reply for it" in {
    val (session, _)     = initialize()
    @volatile var status = -1
    @volatile var body   = "unset"
    val caller = Thread.ofVirtual().start { () =>
      val reply = inSession(session, request(12, "tools/call", ujson.Obj("name" -> "slow", "arguments" -> ujson.Obj())))
      status = reply.statusCode()
      body = reply.body()
    }
    started.await(10, TimeUnit.SECONDS) shouldBe true
    val cancel = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method"  -> "notifications/cancelled",
      "params"  -> ujson.Obj("requestId" -> 12, "reason" -> "user")
    )
    inSession(session, cancel).statusCode() shouldBe 202
    caller.join(10_000)
    interrupted shouldBe true
    status shouldBe 202
    body shouldBe ""
  }
}
