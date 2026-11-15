package org.llm4s.examples.http.playframework

import org.apache.pekko.stream.Materializer
import org.llm4s.error.{ AuthenticationError, ConfigurationError, LLMError, NetworkError, RateLimitError, TimeoutError }
import org.llm4s.pekko.{ AgentPekko, LLMClientPekko }
import org.llm4s.examples.http.ChatAgent
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.test.FakeRequest
import play.api.test.Helpers.*

import scala.concurrent.duration.*

import PlayTestAgents.{ agentOver, application, Failing, Slow }

/** The Play app's routes in memory, with Play's test helpers (`FakeRequest`, `route`): no server, no API key. */
class ChatControllerSpec extends AnyFlatSpec with Matchers {

  /** A response: its status, headers, content type and body. */
  final case class Response(status: Int, headers: Map[String, String], contentType: Option[String], body: String) {
    def json: ujson.Value = ujson.read(body)

    /** The named events of a streamed body, with their data. */
    def events: Vector[(String, ujson.Value)] =
      body
        .split("\n\n")
        .toVector
        .flatMap { block =>
          val fields = block.linesIterator.toVector
          for {
            name <- fields.collectFirst { case l if l.startsWith("event: ") => l.stripPrefix("event: ") }
            data <- fields.collectFirst { case l if l.startsWith("data: ") => l.stripPrefix("data: ") }
          } yield name -> ujson.read(data)
        }
  }

  private def post(agent: AgentPekko, path: String, body: String, heartbeat: FiniteDuration = 15.seconds): Response = {
    val app = application(agent, heartbeat)
    running(app) {
      given Materializer = app.materializer
      val result         = route(app, FakeRequest(POST, path).withTextBody(body)).getOrElse(fail(s"no route for $path"))
      Response(status(result), headers(result), contentType(result), contentAsString(result))
    }
  }

  private def hello = ScriptedLLMClient.always(Reply.text("Hello, llm4s world")).withChunkSize(5)

  "POST /chat" should "answer the turn's result as JSON" in {
    val response = post(agentOver(hello), "/chat", """{"message": "hi"}""")
    response.status shouldBe OK
    response.contentType shouldBe Some("application/json")
    response.json("answer").str shouldBe "Hello, llm4s world"
    response.json("status").str shouldBe "Completed"
    response.json("threadId").str should not be empty
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
      withClue(s"$body: ")(response.status shouldBe BAD_REQUEST)
      response.json("error")("type").str shouldBe "ValidationError"
    }
    client.callCount shouldBe 0
  }

  it should "map the provider's error to a status" in {
    val cases: Seq[(LLMError, Int)] = Seq(
      RateLimitError("fake", 7.seconds)            -> TOO_MANY_REQUESTS,
      TimeoutError("slow", 30.seconds, "complete") -> GATEWAY_TIMEOUT,
      AuthenticationError("fake", "bad key")       -> BAD_GATEWAY,
      ConfigurationError("no model")               -> INTERNAL_SERVER_ERROR,
      NetworkError("down", None, "http://x")       -> SERVICE_UNAVAILABLE
    )
    cases.foreach { case (error, expected) =>
      val response = post(agentOver(ScriptedLLMClient.always(Reply.failure(error))), "/chat", """{"message": "hi"}""")
      withClue(s"${error.getClass.getSimpleName}: ")(response.status shouldBe expected)
      response.json("error")("type").str shouldBe error.getClass.getSimpleName
    }
  }

  it should "send Retry-After with a rate limit" in {
    val limited = ScriptedLLMClient.always(Reply.failure(RateLimitError("fake", 7.seconds)))
    post(agentOver(limited), "/chat", """{"message": "hi"}""").headers.get(RETRY_AFTER) shouldBe Some("7")
  }

  it should "answer 502 for a Future that fails with something other than an LLMError" in {
    val response = post(Failing, "/chat", """{"message": "hi"}""")
    response.status shouldBe BAD_GATEWAY
    response.json("error")("type").str shouldBe "UnknownError"
  }

  "POST /chat/stream" should "send the answer's pieces in order, then done, as server-sent events" in {
    val response = post(agentOver(hello), "/chat/stream", """{"message": "hi"}""")
    response.status shouldBe OK
    response.contentType shouldBe Some("text/event-stream")
    response.headers.get(CACHE_CONTROL) shouldBe Some("no-cache")
    val received = response.events
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
    val agent = LLMClientPekko(client)
      .agent("chat")(builder => ChatAgent.configure(builder).withTools(new ToolRegistry(Seq(clock))))
      .fold(e => fail(s"could not build the agent: $e"), identity)
    val received = post(agent, "/chat/stream", """{"message": "time?"}""").events
    received.head shouldBe ("tool_call" -> ujson.Obj("id" -> "call-1", "tool" -> "clock"))
    received.last._2("answer").str shouldBe "It is noon."
  }

  it should "end with an error event carrying the status the error maps to" in {
    val limited  = ScriptedLLMClient.always(Reply.failure(RateLimitError("fake", 7.seconds)))
    val received = post(agentOver(limited), "/chat/stream", """{"message": "hi"}""").events
    received.map(_._1) shouldBe Vector("error")
    val error = received.head._2
    error("status").num shouldBe 429
    error("retryAfterSeconds").num shouldBe 7
    error("error")("type").str shouldBe "RateLimitError"
  }

  it should "send an error event for a stream that fails with something other than an LLMError" in {
    val received = post(Failing, "/chat/stream", """{"message": "hi"}""").events
    received.map(_._1) shouldBe Vector("error")
    received.head._2("status").num shouldBe 502
  }

  it should "answer 400 for an invalid request before starting a stream" in {
    val client   = hello
    val response = post(agentOver(client), "/chat/stream", """{"message": ""}""")
    response.status shouldBe BAD_REQUEST
    response.json("error")("type").str shouldBe "ValidationError"
    client.callCount shouldBe 0
  }

  it should "send a comment while the run is quiet" in {
    val text = post(Slow, "/chat/stream", """{"message": "hi"}""", heartbeat = 20.millis).body
    text should include(": keep-alive")
    text should include("event: done")
  }

  "Another path" should "answer 404" in {
    post(agentOver(hello), "/nope", "{}").status shouldBe NOT_FOUND
  }
}
