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
        Seq(echo, slow, gate),
        Map("echo" -> MCPToolAnnotations(title = Some("Echo"), readOnlyHint = Some(true)))
      ) {
    override private[mcp] def nowMillis(): Long = time.get
  }

  // `gate` waits until the test releases its key; it says whether it was interrupted, and whether its thread already
  // carried an interrupt when it started (a stale one from a cancellation of another call)
  private val gates = new java.util.concurrent.ConcurrentHashMap[String, (CountDownLatch, CountDownLatch)]()
  private def gateFor(key: String) = gates.computeIfAbsent(key, _ => new CountDownLatch(1) -> new CountDownLatch(1))
  private val gate = ToolBuilder[ujson.Value](
    "gate",
    "Waits for its key",
    Schema.`object`[Map[String, Any]]("Gate").withRequiredField("key", Schema.string("key"))
  ).withHandler { params =>
    params.getString("key").map { key =>
      val staleInterrupt     = Thread.currentThread().isInterrupted
      val (entered, release) = gateFor(key)
      entered.countDown()
      val wasInterrupted = org.llm4s.error.CancelledError.catchInterrupt(release.await(30, TimeUnit.SECONDS)).isLeft
      ujson.Obj("interrupted" -> wasInterrupted, "staleInterrupt" -> staleInterrupt): ujson.Value
    }
  }.build()
    .fold(e => fail(e.formatted), identity)

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

  private def gateCall(id: ujson.Value, key: String) =
    ujson.Obj(
      "jsonrpc" -> "2.0",
      "id"      -> id,
      "method"  -> "tools/call",
      "params"  -> ujson.Obj("name" -> "gate", "arguments" -> ujson.Obj("key" -> key))
    )

  private def cancelOf(id: ujson.Value) =
    ujson.Obj("jsonrpc" -> "2.0", "method" -> "notifications/cancelled", "params" -> ujson.Obj("requestId" -> id))

  /** Sends `body` on a virtual thread; the reply, once the thread is joined. */
  private def inBackground(send: => HttpResponse[String]) = {
    @volatile var reply: Option[HttpResponse[String]] = None
    val thread                                        = Thread.ofVirtual().start(() => reply = Some(send))
    () => { thread.join(15_000); reply.getOrElse(fail("no reply")) }
  }

  private def gateResult(reply: HttpResponse[String]) = ujson.read(reply.body())("result")("structuredContent")

  it should "never let a client without a session cancel a call, its own or another's" in {
    val call =
      inBackground(post(server, gateCall(ujson.Num(20), "sessionless"), "MCP-Protocol-Version" -> "2025-06-18"))
    gateFor("sessionless")._1.await(10, TimeUnit.SECONDS) shouldBe true
    post(server, cancelOf(ujson.Num(20))).statusCode() shouldBe 202
    gateFor("sessionless")._2.countDown()
    val reply = call()
    reply.statusCode() shouldBe 200
    gateResult(reply)("interrupted").bool shouldBe false
  }

  it should "tell a numeric id from a string one when cancelling" in {
    val (session, _) = initialize()
    val numeric      = inBackground(inSession(session, gateCall(ujson.Num(1), "numeric")))
    val text         = inBackground(inSession(session, gateCall(ujson.Str("1"), "text")))
    gateFor("numeric")._1.await(10, TimeUnit.SECONDS) shouldBe true
    gateFor("text")._1.await(10, TimeUnit.SECONDS) shouldBe true

    inSession(session, cancelOf(ujson.Num(1))).statusCode() shouldBe 202
    val cancelled = numeric()
    cancelled.statusCode() shouldBe 202
    cancelled.body() shouldBe ""

    gateFor("text")._2.countDown()
    val kept = text()
    ujson.read(kept.body())("id") shouldBe ujson.Str("1")
    gateResult(kept)("interrupted").bool shouldBe false
  }

  /** A server that can hold a call between running and being marked completed, for a cancel to arrive then. */
  private class Hooked extends MCPServer(MCPServerOptions(0, "/mcp", "Hooked", "1.0"), Seq(gate)) {
    @volatile var hold: Option[(CountDownLatch, CountDownLatch)] = None
    override private[mcp] def beforeCallCompletes(): Unit = hold.foreach { case (reached, proceed) =>
      reached.countDown()
      // Waits through the cancellation's interrupt, which is then set again for the server to clear
      var interrupted = false
      while (org.llm4s.error.CancelledError.catchInterrupt(proceed.await()).isLeft) interrupted = true
      if (interrupted) Thread.currentThread().interrupt()
    }
  }

  private def withHooked(test: Hooked => Unit): Unit = {
    val hooked = new Hooked
    hooked.start().fold(e => throw e, _ => ())
    try test(hooked)
    finally hooked.stop()
  }

  it should "let a cancellation that arrives after the tool ran but before it completed win, and leave no interrupt behind" in
    withHooked { hooked =>
      val (session, _) = initialize(hooked)
      val reached      = new CountDownLatch(1)
      val proceed      = new CountDownLatch(1)
      hooked.hold = Some(reached -> proceed)
      val call = inBackground(inSession(session, gateCall(ujson.Num(30), "late-cancel"), hooked))
      gateFor("late-cancel")._2.countDown() // the tool returns at once
      reached.await(10, TimeUnit.SECONDS) shouldBe true
      inSession(session, cancelOf(ujson.Num(30)), hooked).statusCode() shouldBe 202
      proceed.countDown()
      val reply = call()
      reply.statusCode() shouldBe 202
      reply.body() shouldBe ""
      hooked.hold = None

      // The pool's threads carry no interrupt into the next calls
      (1 to 4).foreach { n =>
        gateFor(s"after-late-$n")._2.countDown()
        gateResult(inSession(session, gateCall(ujson.Num(31 + n), s"after-late-$n"), hooked))(
          "staleInterrupt"
        ).bool shouldBe false
      }
    }

  it should "ignore a cancellation that arrives after the call completed" in withHooked { hooked =>
    val (session, _) = initialize(hooked)
    gateFor("done")._2.countDown()
    val reply = inSession(session, gateCall(ujson.Num(40), "done"), hooked)
    reply.statusCode() shouldBe 200
    inSession(session, cancelOf(ujson.Num(40)), hooked).statusCode() shouldBe 202
    (1 to 4).foreach { n =>
      gateFor(s"after-done-$n")._2.countDown()
      gateResult(inSession(session, gateCall(ujson.Num(40 + n), s"after-done-$n"), hooked))(
        "staleInterrupt"
      ).bool shouldBe false
    }
  }

  /** A server that holds an HTTP+SSE request between its 202 and its processing, for a cancel to arrive then. */
  private class AckHooked extends MCPServer(MCPServerOptions(0, "/mcp", "AckHooked", "1.0"), Seq(gate)) {
    val acknowledged = new CountDownLatch(1)
    val proceed      = new CountDownLatch(1)
    override private[mcp] def afterAcknowledged(): Unit = {
      acknowledged.countDown()
      proceed.await(10, TimeUnit.SECONDS): Unit
    }
  }

  it should "honour an HTTP+SSE cancel sent between the 202 and the call's start: the tool never runs" in {
    val hooked = new AckHooked
    hooked.start().fold(e => throw e, _ => ())
    val stream = SseTestStream.open(hooked.boundPort, "/mcp/sse")
    try {
      val events = new java.util.concurrent.LinkedBlockingQueue[String]()
      val reader = Thread.ofVirtual().start { () =>
        val lines = new java.io.BufferedReader(new java.io.InputStreamReader(stream.body, "UTF-8"))
        val event = new StringBuilder
        org.llm4s.error.CancelledError.catchInterrupt(scala.util.Try {
          var line = lines.readLine()
          while (line != null) {
            if (line.isEmpty) { if (event.nonEmpty) events.put(event.toString); event.clear() }
            else event.append(line).append('\n')
            line = lines.readLine()
          }
        }): Unit
      }
      val endpoint = events.poll(10, TimeUnit.SECONDS)
      endpoint should include("event: endpoint")
      val path = endpoint.linesIterator.find(_.startsWith("data: ")).map(_.stripPrefix("data: ").trim).getOrElse("")
      def postSse(body: ujson.Value): Int =
        http
          .send(
            HttpRequest
              .newBuilder(URI.create(s"http://127.0.0.1:${hooked.boundPort}$path"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body.render()))
              .build(),
            HttpResponse.BodyHandlers.ofString()
          )
          .statusCode()

      @volatile var callStatus = -1
      val call = Thread.ofVirtual().start(() => callStatus = postSse(gateCall(ujson.Num(60), "never-runs")))
      hooked.acknowledged.await(10, TimeUnit.SECONDS) shouldBe true
      postSse(cancelOf(ujson.Num(60))) shouldBe 202
      hooked.proceed.countDown()
      call.join(10_000)
      callStatus shouldBe 202

      val deadline = scala.concurrent.duration.Deadline.now + 10.seconds
      while (hooked.registeredCalls > 0 && deadline.hasTimeLeft()) Thread.sleep(10)
      hooked.registeredCalls shouldBe 0
      gateFor("never-runs")._1.getCount shouldBe 1 // the tool never started
      Option(events.poll(200, TimeUnit.MILLISECONDS)).filterNot(_.startsWith(":")) shouldBe None // and nothing replied
      reader.interrupt()
    } finally {
      stream.close()
      hooked.stop()
    }
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
