package org.llm4s.mcp

import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The HTTP+SSE transport speaks MCP 2024-11-05 (#3073): it `GET`s the SSE URL, POSTs to the endpoint the `endpoint`
 * event names, and reads each reply from the stream, matched by id. Against llm4s's own `MCPServer` legacy endpoints
 * and a scripted server ([[LegacySseServer]]).
 */
class SSETransportImplSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def request(method: String, id: String = "1") = JsonRpcRequest("2.0", id, method, None)
  private val notification = JsonRpcNotification("2.0", "notifications/initialized", Some(ujson.Obj()))

  private def withServer[A](server: LegacySseServer)(test: LegacySseServer => A): A =
    try test(server)
    finally server.close()

  private def transportFor(
    server: LegacySseServer,
    timeout: FiniteDuration = 5.seconds,
    http: MCPHttpOptions = MCPHttpOptions.default
  ) =
    new SSETransportImpl(server.url, "legacy", timeout, http)

  private def using[A](transport: SSETransportImpl)(test: SSETransportImpl => A): A =
    try test(transport)
    finally transport.close()

  "SSETransportImpl" should "complete the handshake and list tools with llm4s's own MCPServer at {path}/sse" in {
    val tool = ToolBuilder[String]("ping_tool", "Answers", Schema.`object`[Map[String, Any]]("No parameters"))
      .withHandler(_ => Right("pong"))
      .build()
      .fold(e => fail(e.formatted), identity)
    val server = new MCPServer(MCPServerOptions(0, "/mcp", "LegacyServer", "1.0"), Seq(tool))
    server.start().fold(e => throw e, _ => ())
    try
      using(new SSETransportImpl(s"http://127.0.0.1:${server.boundPort}/mcp/sse", "own", 5.seconds)) { transport =>
        transport.sendRequest(request("initialize")).value.result.map(_("protocolVersion").str) should not be empty
        transport.sendNotification(notification) shouldBe Right(())
        val tools = transport.sendRequest(request("tools/list", "2")).value
        tools.result.map(_("tools").arr.map(_("name").str).toList) shouldBe Some(List("ping_tool"))
      }
    finally server.stop()
  }

  it should "POST to an absolute endpoint on the same origin" in withServer(
    new LegacySseServer(endpoint = base => s"$base/messages?sessionId=abs")
  ) { server =>
    using(transportFor(server))(_.sendRequest(request("tools/list")).value.result.map(_("echo").str)) shouldBe
      Some("tools/list")
  }

  it should "refuse an endpoint on another origin, posting nothing there" in withServer(
    new LegacySseServer(endpoint = _ => "http://evil.example.com/messages")
  ) { server =>
    using(transportFor(server))(_.sendRequest(request("initialize")).left.value.message) should include(
      "another origin"
    )
    server.posted.asScala shouldBe empty
  }

  it should "match the reply by id, answer the server's own requests, and skip other messages" in withServer(
    new LegacySseServer(onPost =
      body =>
        if (body.obj.contains("method"))
          202 -> Seq(
            """{"jsonrpc":"2.0","id":"0","result":{"wrong":true}}""",
            """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""",
            """{"jsonrpc":"2.0","id":"1","method":"ping"}""",
            """{"jsonrpc":"2.0","id":9,"method":"sampling/createMessage"}""",
            """{"jsonrpc":"2.0","id":"1","result":{"right":true}}"""
          ).map(LegacySseServer.message).mkString
        else 202 -> ""
    )
  ) { server =>
    using(transportFor(server)) { transport =>
      transport.sendRequest(request("tools/list")).value.result shouldBe Some(ujson.Obj("right" -> true))
      val deadline = Deadline.now + 5.seconds
      def answers  = server.posted.asScala.toList.filterNot(_.obj.contains("method"))
      while (answers.size < 2 && deadline.hasTimeLeft()) Thread.sleep(10)
      answers.find(_("id") == ujson.Str("1")).map(_("result")) shouldBe Some(ujson.Obj())
      answers.find(_("id") == ujson.Num(9)).map(_("error")("code").num.toInt) shouldBe Some(-32601)
    }
  }

  it should "read data with no space after the colon, and join data split over several lines" in withServer(
    new LegacySseServer(onPost =
      body =>
        202 -> s"event: message\ndata:{\"jsonrpc\":\"2.0\",\ndata:\"id\":${body("id").render()},\"result\":{\"split\":true}}\n\n"
    )
  ) { server =>
    using(transportFor(server))(_.sendRequest(request("tools/list")).value.result) shouldBe
      Some(ujson.Obj("split" -> true))
  }

  it should "give up on a reply that does not arrive within the timeout" in withServer(
    new LegacySseServer(onPost = _ => 202 -> "")
  ) { server =>
    val started = System.nanoTime()
    val message = using(transportFor(server, 1.second))(_.sendRequest(request("tools/list")).left.value.message)
    message should include("no reply to request 1")
    message should include("1 second")
    (System.nanoTime() - started).nanos should be < 10.seconds
  }

  it should "refuse an event larger than the reply bound, rather than buffer it" in withServer(
    new LegacySseServer(onPost =
      _ => 202 -> ("event: message\n" + Seq.fill(11)(s"data: ${"x" * (1024 * 1024)}\n").mkString + "\n")
    )
  ) { server =>
    using(transportFor(server, 30.seconds))(_.sendRequest(request("tools/list")).left.value.message) should include(
      "reply exceeds"
    )
  }

  it should "send the configured headers and bearer key on the GET and every POST, and Accept on a notification" in
    withServer(new LegacySseServer()) { server =>
      val http = MCPHttpOptions().withHeader("X-Tenant", "acme").withApiKey(ApiKeyProvider.static(ApiKey("k-3078")))
      using(transportFor(server, http = http)) { transport =>
        transport.sendRequest(request("initialize")).isRight shouldBe true
        transport.sendNotification(notification) shouldBe Right(())
      }
      val gets  = server.headersOf("GET")
      val posts = server.headersOf("POST")
      gets should have size 1
      posts should have size 2
      (gets ++ posts).foreach { headers =>
        headers.get("x-tenant") shouldBe Some("acme")
        headers.get("authorization") shouldBe Some("Bearer k-3078")
      }
      gets.head.get("accept") shouldBe Some("text/event-stream")
      posts.foreach(_.get("accept") shouldBe Some("application/json, text/event-stream"))
    }

  it should "report a POST the server answers 404 as a lost session, then open a new stream" in {
    @volatile var refuse = true
    withServer(new LegacySseServer(onPost = body => if (refuse) 404 -> "gone" else LegacySseServer.echoResult(body))) {
      server =>
        using(transportFor(server)) { transport =>
          transport.sendRequest(request("tools/list")).left.value shouldBe MCPSessionLost.Expired
          refuse = false
          transport.sendRequest(request("tools/list", "2")).left.value shouldBe MCPSessionLost.StreamLost
          transport.sendRequest(request("initialize", "3")).isRight shouldBe true
          transport.sendRequest(request("tools/list", "4")).isRight shouldBe true
          server.streamsOpened shouldBe 2
        }
    }
  }

  it should "fail the calls waiting on a stream that ends, and refuse all but initialize on the next one" in withServer(
    new LegacySseServer(onPost = _ => 202 -> "")
  ) { server =>
    using(transportFor(server, 30.seconds)) { transport =>
      @volatile var outcome: Option[Either[String, JsonRpcResponse]] = None
      val waiting =
        Thread.ofVirtual().start(() => outcome = Some(transport.sendRequest(request("tools/list")).left.map(_.message)))
      val deadline = Deadline.now + 5.seconds
      while (server.posted.isEmpty && deadline.hasTimeLeft()) Thread.sleep(10)
      server.dropStreams()
      waiting.join(10_000)
      outcome.flatMap(_.left.toOption).getOrElse(fail(s"expected a failure, got $outcome")) should include(
        "the server closed the event stream"
      )
      transport.sendRequest(request("tools/list", "2")).left.value shouldBe MCPSessionLost.StreamLost
    }
  }

  "MCPClientImpl" should "fall back to HTTP+SSE when Streamable HTTP is refused, and recover a dropped stream" in
    withServer(
      new LegacySseServer(onPost =
        body =>
          body("method").strOpt match {
            case Some("tools/list") =>
              202 -> LegacySseServer.message(
                ujson
                  .Obj(
                    "jsonrpc" -> "2.0",
                    "id"      -> body("id"),
                    "result" -> ujson.Obj(
                      "tools" -> ujson.Arr(
                        ujson.Obj("name" -> "t", "description" -> "d", "inputSchema" -> ujson.Obj("type" -> "object"))
                      )
                    )
                  )
                  .render()
              )
            case _ => LegacySseServer.echoResult(body)
          }
      )
    ) { server =>
      val client = new MCPClientImpl(MCPServerConfig.sse("legacy", server.url, 5.seconds))
      try {
        client.getTools().map(_.map(_.name)) shouldBe Right(Seq("t"))
        client.protocolVersion shouldBe Some("2024-11-05")
        server.dropStreams()
        val deadline = Deadline.now + 5.seconds
        def open     = client.transport.collect { case t: SSETransportImpl => t.streamOpen }.getOrElse(false)
        while (open && deadline.hasTimeLeft()) Thread.sleep(10)
        open shouldBe false
        client.getTools().map(_.map(_.name)) shouldBe Right(Seq("t"))
        server.streamsOpened shouldBe 2
      } finally client.close()
    }

  "SSETransportImpl.resolveEndpoint" should "resolve a path or an absolute URL on the same origin, and refuse another" in {
    val url = "http://127.0.0.1:8080/mcp/sse"
    SSETransportImpl.resolveEndpoint(url, "/mcp/messages?sessionId=a") shouldBe
      Right("http://127.0.0.1:8080/mcp/messages?sessionId=a")
    SSETransportImpl.resolveEndpoint(url, "messages?sessionId=a") shouldBe
      Right("http://127.0.0.1:8080/mcp/messages?sessionId=a")
    SSETransportImpl.resolveEndpoint(url, "http://127.0.0.1:8080/x") shouldBe Right("http://127.0.0.1:8080/x")
    SSETransportImpl.resolveEndpoint(url, "http://127.0.0.1:9090/x").isLeft shouldBe true
    SSETransportImpl.resolveEndpoint(url, "https://127.0.0.1:8080/x").isLeft shouldBe true
    SSETransportImpl.resolveEndpoint("https://h.example/sse", "https://h.example:443/m") shouldBe
      Right("https://h.example:443/m")
  }
}
