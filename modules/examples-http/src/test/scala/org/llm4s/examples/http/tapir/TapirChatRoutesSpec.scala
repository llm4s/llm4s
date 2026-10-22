package org.llm4s.examples.http.tapir

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.{ MediaType, Method, Request, Response, ServerSentEvent, Status, Uri }
import org.http4s.implicits.*
import org.llm4s.effect.cats.{ AgentIO, LLMClientIO }
import org.llm4s.error.{ AuthenticationError, ConfigurationError, LLMError, NetworkError, RateLimitError, TimeoutError }
import org.llm4s.examples.http.http4s.Http4sChatServer
import org.llm4s.examples.http.http4s.TestAgents.{ agentOver, Failing, Slow }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** The Tapir endpoints interpreted as http4s routes, in memory, against a scripted client: no server, no API key. */
class TapirChatRoutesSpec extends AnyFlatSpec with Matchers {

  private def run(agent: AgentIO[IO], request: Request[IO], heartbeat: FiniteDuration = 15.seconds): Response[IO] =
    new TapirChatRoutes(agent, heartbeat).routes.orNotFound.run(request).unsafeRunSync()

  private def post(agent: AgentIO[IO], path: String, body: String): Response[IO] =
    run(agent, Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body))

  private def get(path: String): Response[IO] =
    run(agentOver(hello), Request[IO](Method.GET, Uri.unsafeFromString(path)))

  private def text(response: Response[IO]): String = response.as[String].unsafeRunSync()

  private def json(response: Response[IO]): ujson.Value = ujson.read(text(response))

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.get(CIString(name)).map(_.head.value)

  private def events(response: Response[IO]): Vector[(String, ujson.Value)] =
    response.body
      .through(ServerSentEvent.decoder[IO])
      .collect { case ServerSentEvent(Some(data), Some(name), _, _, _) => name -> ujson.read(data) }
      .compile
      .toVector
      .unsafeRunSync()

  private def hello = ScriptedLLMClient.always(Reply.text("Hello, llm4s world")).withChunkSize(5)

  "POST /chat" should "answer the turn's result as JSON" in {
    val response = post(agentOver(hello), "/chat", """{"message": "hi"}""")
    response.status shouldBe Status.Ok
    response.contentType.map(_.mediaType) shouldBe Some(MediaType.application.json)
    val body = json(response)
    body("answer").str shouldBe "Hello, llm4s world"
    body("status").str shouldBe "Completed"
    body("threadId").str should not be empty
  }

  it should "send the request's message to the model" in {
    val client = ScriptedLLMClient.always(Reply.text("ok"))
    post(agentOver(client), "/chat", """{"message": "What is 2 + 2?"}""")
    client.lastCall.flatMap(_.lastUserText) shouldBe Some("What is 2 + 2?")
  }

  it should "answer 400 with the shared error body for an invalid body, without calling the model" in {
    val client = ScriptedLLMClient.always(Reply.text("never"))
    val agent  = agentOver(client)
    Seq("not json", """{"text": "hi"}""", """{"message": 42}""", """{"message": "  "}""", "[]").foreach { body =>
      val response = post(agent, "/chat", body)
      withClue(s"$body: ")(response.status shouldBe Status.BadRequest)
      response.contentType.map(_.mediaType) shouldBe Some(MediaType.application.json)
      json(response)("error")("type").str shouldBe "ValidationError"
    }
    client.callCount shouldBe 0
  }

  it should "map the provider's error to a status through HttpErrors" in {
    val cases: Seq[(LLMError, Status)] = Seq(
      RateLimitError("fake", 7.seconds)            -> Status.TooManyRequests,
      TimeoutError("slow", 30.seconds, "complete") -> Status.GatewayTimeout,
      AuthenticationError("fake", "bad key")       -> Status.BadGateway,
      ConfigurationError("no model")               -> Status.InternalServerError,
      NetworkError("down", None, "http://x")       -> Status.ServiceUnavailable
    )
    cases.foreach { case (error, status) =>
      val response = post(agentOver(ScriptedLLMClient.always(Reply.failure(error))), "/chat", """{"message": "hi"}""")
      withClue(s"${error.getClass.getSimpleName}: ")(response.status shouldBe status)
      val body = json(response)("error")
      body("type").str shouldBe error.getClass.getSimpleName
      body("recoverable").bool shouldBe LLMError.isRecoverable(error)
    }
  }

  it should "send Retry-After with a rate limit, and only then" in {
    val limited  = ScriptedLLMClient.always(Reply.failure(RateLimitError("fake", 7.seconds)))
    val response = post(agentOver(limited), "/chat", """{"message": "hi"}""")
    header(response, "Retry-After") shouldBe Some("7")
    val timedOut = ScriptedLLMClient.always(Reply.failure(TimeoutError("slow", 30.seconds, "complete")))
    header(post(agentOver(timedOut), "/chat", """{"message": "hi"}"""), "Retry-After") shouldBe None
  }

  it should "answer 502 for an effect that fails with something other than an LLMError" in {
    val response = post(Failing, "/chat", """{"message": "hi"}""")
    response.status shouldBe Status.BadGateway
    json(response)("error")("type").str shouldBe "UnknownError"
  }

  "POST /chat/stream" should "send the answer's pieces in order, then done, as server-sent events" in {
    val response = post(agentOver(hello), "/chat/stream", """{"message": "hi"}""")
    response.status shouldBe Status.Ok
    response.contentType.map(_.mediaType) shouldBe Some(MediaType.`text/event-stream`)
    header(response, "Cache-Control") shouldBe Some("no-cache")
    val received = events(response)
    received.map(_._1) shouldBe Vector("delta", "delta", "delta", "delta", "done")
    received.init.map(_._2("text").str) shouldBe Vector("Hello", ", llm", "4s wo", "rld")
    received.last._2("answer").str shouldBe "Hello, llm4s world"
  }

  it should "send a tool call as an event" in {
    val clock = ToolBuilder[Map[String, Any], String]("clock", "the time", Schema.`object`[Map[String, Any]]("none"))
      .withHandler(_ => Right("noon"))
      .buildSafe()
      .fold(e => fail(s"could not build the tool: $e"), identity)
    val client = ScriptedLLMClient.sequence(Reply.toolCall("clock", "{}", Some("call-1")), Reply.text("It is noon."))
    val agent = LLMClientIO[IO](client)
      .agent("chat")(builder => Http4sChatServer.configureAgent(builder).withTools(new ToolRegistry(Seq(clock))))
      .unsafeRunSync()
    val received = events(post(agent, "/chat/stream", """{"message": "time?"}"""))
    received.head shouldBe ("tool_call" -> ujson.Obj("id" -> "call-1", "tool" -> "clock"))
    received.last._2("answer").str shouldBe "It is noon."
  }

  it should "end with an error event carrying the status the error maps to" in {
    val limited  = ScriptedLLMClient.always(Reply.failure(RateLimitError("fake", 7.seconds)))
    val received = events(post(agentOver(limited), "/chat/stream", """{"message": "hi"}"""))
    received.map(_._1) shouldBe Vector("error")
    val error = received.head._2
    error("status").num shouldBe 429
    error("retryAfterSeconds").num shouldBe 7
    error("error")("type").str shouldBe "RateLimitError"
  }

  it should "send an error event for an effect that fails with something other than an LLMError" in {
    val received = events(post(Failing, "/chat/stream", """{"message": "hi"}"""))
    received.map(_._1) shouldBe Vector("error")
    received.head._2("status").num shouldBe 502
  }

  it should "answer 400 for an invalid request before starting a stream" in {
    val client   = hello
    val response = post(agentOver(client), "/chat/stream", """{"message": ""}""")
    response.status shouldBe Status.BadRequest
    json(response)("error")("type").str shouldBe "ValidationError"
    client.callCount shouldBe 0
  }

  it should "send a comment while the run is quiet" in {
    val response = run(
      Slow,
      Request[IO](Method.POST, uri"/chat/stream").withEntity("""{"message": "hi"}"""),
      heartbeat = 20.millis
    )
    val body = text(response)
    body should include(": keep-alive")
    body should include("event: done")
  }

  "GET /docs/openapi.yaml" should "serve the OpenAPI document of both endpoints" in {
    val response = get(s"/docs/${TapirChatRoutes.OpenApiFile}")
    response.status shouldBe Status.Ok
    val yaml = text(response)
    yaml should include("/chat:")
    yaml should include("/chat/stream:")
    yaml should include("'429':")
  }

  "GET /docs" should "serve the Swagger UI, pointed at the OpenAPI document" in {
    val index = get("/docs/index.html")
    index.status shouldBe Status.Ok
    text(index).toLowerCase should include("swagger")
    val initializer = get("/docs/swagger-initializer.js")
    initializer.status shouldBe Status.Ok
    text(initializer) should include(TapirChatRoutes.OpenApiFile)
  }

  "Another path" should "answer 404" in {
    post(agentOver(hello), "/nope", "{}").status shouldBe Status.NotFound
  }

  "The routes" should "serve with the default heartbeat" in {
    val response = new TapirChatRoutes(agentOver(hello)).routes.orNotFound
      .run(Request[IO](Method.POST, uri"/chat").withEntity("""{"message": "hi"}"""))
      .unsafeRunSync()
    response.status shouldBe Status.Ok
  }
}
