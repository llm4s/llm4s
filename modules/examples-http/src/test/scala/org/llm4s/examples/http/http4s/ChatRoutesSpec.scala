package org.llm4s.examples.http.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.http4s.{ MediaType, Method, Request, Response, ServerSentEvent, Status, Uri }
import org.http4s.implicits.*
import org.llm4s.agent.{ AgentResult, AgentRun }
import org.llm4s.agent.graph.{ EventHandlers, EventType, InterruptId, RunConfig, ThreadId }
import org.llm4s.effect.cats.{ AgentIO, AgentStreamItem, LLMClientIO }
import org.llm4s.error.{ AuthenticationError, ConfigurationError, LLMError, NetworkError, RateLimitError, TimeoutError }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/** The routes in memory, against a scripted client: no server, no network, no API key. */
class ChatRoutesSpec extends AnyFlatSpec with Matchers {

  private def agentOver(client: ScriptedLLMClient): AgentIO[IO] =
    Http4sChatServer.agent(LLMClientIO[IO](client)).unsafeRunSync()

  private def post(agent: AgentIO[IO], path: String, body: String): Response[IO] =
    new ChatRoutes(agent).routes.orNotFound
      .run(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body))
      .unsafeRunSync()

  private def json(response: Response[IO]): ujson.Value = ujson.read(response.as[String].unsafeRunSync())

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

  it should "answer 400 for a body that is not JSON, has no message or a blank one, without calling the model" in {
    val client = ScriptedLLMClient.always(Reply.text("never"))
    val agent  = agentOver(client)
    Seq("not json", """{"text": "hi"}""", """{"message": 42}""", """{"message": "  "}""", "[]").foreach { body =>
      val response = post(agent, "/chat", body)
      response.status shouldBe Status.BadRequest
      json(response)("error")("type").str shouldBe "ValidationError"
    }
    client.callCount shouldBe 0
  }

  it should "map the provider's error to a status" in {
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
      json(response)("error")("type").str shouldBe error.getClass.getSimpleName
    }
  }

  it should "send Retry-After with a rate limit" in {
    val limited  = ScriptedLLMClient.always(Reply.failure(RateLimitError("fake", 7.seconds)))
    val response = post(agentOver(limited), "/chat", """{"message": "hi"}""")
    response.headers.get(org.typelevel.ci.CIString("Retry-After")).map(_.head.value) shouldBe Some("7")
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
    client.callCount shouldBe 0
  }

  it should "send a comment while the run is quiet" in {
    val slow = new ChatRoutes(Slow, heartbeat = 20.millis).routes.orNotFound
      .run(Request[IO](Method.POST, uri"/chat/stream").withEntity("""{"message": "hi"}"""))
      .unsafeRunSync()
    val text = slow.body.through(fs2.text.utf8.decode).compile.string.unsafeRunSync()
    text should include(": keep-alive")
    text should include("event: done")
  }

  "Another path" should "answer 404" in {
    post(agentOver(hello), "/nope", "{}").status shouldBe Status.NotFound
  }

  /** An `AgentIO` that fails with a plain exception, as no llm4s effect does. */
  private object Failing extends Unsupported {
    override def run(query: String, config: RunConfig): IO[AgentResult] = IO.raiseError(new RuntimeException("boom"))
    override def stream(threadId: ThreadId, query: String, config: RunConfig): Stream[IO, AgentStreamItem] =
      Stream.raiseError[IO](new IllegalStateException())
  }

  /** An `AgentIO` whose stream is quiet for a while, then ends with a real result. */
  private object Slow extends Unsupported {
    private val agent = agentOver(ScriptedLLMClient.always(Reply.text("late")))
    override def stream(threadId: ThreadId, query: String, config: RunConfig): Stream[IO, AgentStreamItem] =
      Stream.sleep_[IO](200.millis) ++ agent.stream(threadId, query, config)
  }

  private trait Unsupported extends AgentIO[IO] {
    private def no[A]: IO[A]                                   = IO.raiseError(new UnsupportedOperationException)
    def run(query: String, config: RunConfig): IO[AgentResult] = no
    def continueConversation(previous: AgentResult, query: String, config: RunConfig): IO[AgentResult]         = no
    def recover(threadId: ThreadId, config: RunConfig): IO[AgentResult]                                        = no
    def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig): IO[AgentResult] = no
    def stream(threadId: ThreadId, query: String, config: RunConfig): Stream[IO, AgentStreamItem] = Stream.eval(no)
    def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig) =
      Stream.eval(no[AgentStreamItem])
    def streamRecover(threadId: ThreadId, config: RunConfig): Stream[IO, AgentStreamItem] = Stream.eval(no)
    def events[A](run: AgentRun, eventType: EventType[A]): Stream[IO, A]                  = Stream.eval(no)
    def events[R](run: AgentRun, handlers: EventHandlers[R]): Stream[IO, R]               = Stream.eval(no)
    def nestedEvents[R](run: AgentRun, handlers: EventHandlers[R]): Stream[IO, R]         = Stream.eval(no)
  }
}
