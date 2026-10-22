package org.llm4s.examples.http.ziohttp

import org.llm4s.agent.{ AgentResult, AgentRun }
import org.llm4s.agent.graph.{ EventHandlers, EventType, InterruptId, RunConfig, ThreadId }
import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  LLMError,
  NetworkError,
  RateLimitError,
  TimeoutError,
  ValidationError
}
import org.llm4s.examples.http.ChatAgent
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.zio.{ AgentStreamItem, AgentZ, LLMClientZ }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{ Duration, IO, ZIO }
import zio.http.{ Body, Header, MediaType, Request, Response, Status }
import zio.stream.ZStream

import scala.concurrent.duration.*

import ZioRuntime.unsafeRun

/** The routes in memory (`Routes.runZIO`), against a scripted client: no server, no network, no API key. */
class ZioChatRoutesSpec extends AnyFlatSpec with Matchers {

  private def agentOver(client: ScriptedLLMClient): AgentZ =
    unsafeRun(ZioChatServer.agent(LLMClientZ(client)))

  /** The response and its whole body, read while the request's scope is open. */
  private def post(
    agent: AgentZ,
    path: String,
    body: String,
    heartbeat: Duration = ZioChatRoutes.DefaultHeartbeat
  ): (Response, String) =
    unsafeRun(
      new ZioChatRoutes(agent, heartbeat).routes
        .runZIO(Request.post(path, Body.fromString(body)))
        .flatMap(response => response.body.asString.map(response -> _))
    )

  /** The named events of a server-sent event stream, comments left out. */
  private def events(text: String): Vector[(String, ujson.Value)] =
    text
      .split("\n\n")
      .toVector
      .map(_.linesIterator.toVector)
      .flatMap { lines =>
        for {
          name <- lines.collectFirst { case l if l.startsWith("event: ") => l.stripPrefix("event: ") }
          data <- lines.collectFirst { case l if l.startsWith("data: ") => l.stripPrefix("data: ") }
        } yield name -> ujson.read(data)
      }

  private def hello = ScriptedLLMClient.always(Reply.text("Hello, llm4s world")).withChunkSize(5)

  "POST /chat" should "answer the turn's result as JSON" in {
    val (response, text) = post(agentOver(hello), "/chat", """{"message": "hi"}""")
    response.status shouldBe Status.Ok
    response.header(Header.ContentType).map(_.mediaType) shouldBe Some(MediaType.application.json)
    val body = ujson.read(text)
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
      val (response, text) = post(agent, "/chat", body)
      response.status shouldBe Status.BadRequest
      ujson.read(text)("error")("type").str shouldBe "ValidationError"
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
      val client           = ScriptedLLMClient.always(Reply.failure(error))
      val (response, text) = post(agentOver(client), "/chat", """{"message": "hi"}""")
      withClue(s"${error.getClass.getSimpleName}: ")(response.status shouldBe status)
      ujson.read(text)("error")("type").str shouldBe error.getClass.getSimpleName
    }
  }

  it should "send Retry-After with a rate limit" in {
    val limited       = ScriptedLLMClient.always(Reply.failure(RateLimitError("fake", 7.seconds)))
    val (response, _) = post(agentOver(limited), "/chat", """{"message": "hi"}""")
    response.rawHeader("Retry-After") shouldBe Some("7")
  }

  it should "answer 502 for an effect that dies instead of failing with an LLMError" in {
    val (response, text) = post(Failing, "/chat", """{"message": "hi"}""")
    response.status shouldBe Status.BadGateway
    ujson.read(text)("error")("type").str shouldBe "UnknownError"
    ujson.read(text)("error")("message").str shouldBe "boom"
  }

  "POST /chat/stream" should "send the answer's pieces in order, then done, as server-sent events" in {
    val (response, text) = post(agentOver(hello), "/chat/stream", """{"message": "hi"}""")
    response.status shouldBe Status.Ok
    response.header(Header.ContentType).map(_.mediaType) shouldBe Some(MediaType.text.`event-stream`)
    response.rawHeader("Cache-Control") shouldBe Some("no-cache")
    val received = events(text)
    received.map(_._1) shouldBe Vector("delta", "delta", "delta", "delta", "done")
    received.init.map(_._2("text").str) shouldBe Vector("Hello", ", llm", "4s wo", "rld")
    received.init.map(_._2("attempt").num) shouldBe Vector(1, 1, 1, 1)
    received.last._2("answer").str shouldBe "Hello, llm4s world"
  }

  it should "send a tool call as an event" in {
    val clock = ToolBuilder[Map[String, Any], String]("clock", "the time", Schema.`object`[Map[String, Any]]("none"))
      .withHandler(_ => Right("noon"))
      .buildSafe()
      .fold(e => fail(s"could not build the tool: $e"), identity)
    val client = ScriptedLLMClient.sequence(Reply.toolCall("clock", "{}", Some("call-1")), Reply.text("It is noon."))
    val agent = unsafeRun(
      LLMClientZ(client).agent("chat")(builder => ChatAgent.configure(builder).withTools(new ToolRegistry(Seq(clock))))
    )
    val received = events(post(agent, "/chat/stream", """{"message": "time?"}""")._2)
    received.head shouldBe ("tool_call" -> ujson.Obj("id" -> "call-1", "tool" -> "clock"))
    received.last._2("answer").str shouldBe "It is noon."
  }

  it should "end with an error event carrying the status the error maps to" in {
    val limited  = ScriptedLLMClient.always(Reply.failure(RateLimitError("fake", 7.seconds)))
    val received = events(post(agentOver(limited), "/chat/stream", """{"message": "hi"}""")._2)
    received.map(_._1) shouldBe Vector("error")
    val error = received.head._2
    error("status").num shouldBe 429
    error("retryAfterSeconds").num shouldBe 7
    error("error")("type").str shouldBe "RateLimitError"
  }

  it should "send an error event for a stream that dies instead of failing with an LLMError" in {
    val received = events(post(Failing, "/chat/stream", """{"message": "hi"}""")._2)
    received.map(_._1) shouldBe Vector("error")
    received.head._2("status").num shouldBe 502
    received.head._2("error")("message").str shouldBe "java.lang.IllegalStateException"
  }

  it should "answer 400 for an invalid request before starting a stream" in {
    val client           = hello
    val (response, text) = post(agentOver(client), "/chat/stream", """{"message": ""}""")
    response.status shouldBe Status.BadRequest
    ujson.read(text)("error")("type").str shouldBe "ValidationError"
    client.callCount shouldBe 0
  }

  it should "send a comment while the run is quiet" in {
    val text = post(Slow, "/chat/stream", """{"message": "hi"}""", heartbeat = Duration.fromScala(20.millis))._2
    text should include(": keep-alive")
    text should include("event: done")
  }

  "Another path" should "answer 404" in {
    post(agentOver(hello), "/nope", "{}")._1.status shouldBe Status.NotFound
  }

  "A request whose body cannot be read" should "answer 400" in {
    val broken   = Body.fromStreamChunked(ZStream.fail(new java.io.IOException("reset")))
    val response = unsafeRun(new ZioChatRoutes(agentOver(hello)).routes.runZIO(Request.post("/chat", broken)))
    response.status shouldBe Status.BadRequest
  }

  /** An `AgentZ` that dies with a plain exception, as no llm4s effect does. */
  private object Failing extends Unsupported {
    override def run(query: String, config: RunConfig): IO[LLMError, AgentResult] =
      ZIO.die(new RuntimeException("boom"))
    override def stream(threadId: ThreadId, query: String, config: RunConfig): ZStream[Any, LLMError, AgentStreamItem] =
      ZStream.die(new IllegalStateException())
  }

  /** An `AgentZ` whose stream is quiet for a while, then ends with a real result. */
  private object Slow extends Unsupported {
    private val agent = agentOver(ScriptedLLMClient.always(Reply.text("late")))
    override def stream(threadId: ThreadId, query: String, config: RunConfig): ZStream[Any, LLMError, AgentStreamItem] =
      ZStream.fromZIO(ZIO.sleep(Duration.fromScala(200.millis))).drain ++ agent.stream(threadId, query, config)
  }

  private trait Unsupported extends AgentZ {
    private def no[A]: IO[LLMError, A] = ZIO.fail(ValidationError("agent", "unsupported"))
    def run(query: String, config: RunConfig): IO[LLMError, AgentResult]                                         = no
    def continueConversation(previous: AgentResult, query: String, config: RunConfig): IO[LLMError, AgentResult] = no
    def recover(threadId: ThreadId, config: RunConfig): IO[LLMError, AgentResult]                                = no
    def resume(
      threadId: ThreadId,
      answers: Map[InterruptId, ujson.Value],
      config: RunConfig
    ): IO[LLMError, AgentResult] =
      no
    def stream(threadId: ThreadId, query: String, config: RunConfig): ZStream[Any, LLMError, AgentStreamItem] =
      ZStream.fromZIO(no)
    def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig) =
      ZStream.fromZIO(no[AgentStreamItem])
    def streamRecover(threadId: ThreadId, config: RunConfig): ZStream[Any, LLMError, AgentStreamItem] =
      ZStream.fromZIO(no)
    def events[A](run: AgentRun, eventType: EventType[A]): ZStream[Any, LLMError, A]          = ZStream.fromZIO(no)
    def events[R](run: AgentRun, handlers: EventHandlers[R]): ZStream[Any, LLMError, R]       = ZStream.fromZIO(no)
    def nestedEvents[R](run: AgentRun, handlers: EventHandlers[R]): ZStream[Any, LLMError, R] = ZStream.fromZIO(no)
  }
}
