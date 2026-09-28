package org.llm4s.trace

import org.llm4s.agent.{ Agent, AgentContext, AgentStatus }
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import scala.collection.mutable

/**
 * What `LangfuseTracing` sends for a whole agent run - a mock LLM that asks for one tool call,
 * then answers - rather than for one event at a time, which `LangfuseTracingSpec` and
 * `LangfuseTracingEdgeCasesSpec` cover (#1003).
 *
 * Nothing leaves the JVM: batches go to a `MockHttpClient`.
 *
 * Based on the mock-backed cases of `LangfuseTracingIntegrationSpec` in #1035 by
 * @kannupriyakalra. The cases that `LangfuseTracingSpec` and `LangfuseTracingEdgeCasesSpec`
 * already had (batch shapes per event, the Basic auth header, the ingestion URL suffix,
 * skipping export when a key is empty) are not repeated; the token-usage counts and the shared
 * trace id went into `LangfuseTracingEdgeCasesSpec`.
 */
class LangfuseTracingAgentRunSpec extends AnyFlatSpec with Matchers {

  /** A LangfuseTracing that also keeps every batch it sends, in order, before sending it. */
  private class RecordingLangfuseTracing(http: MockHttpClient)
      extends LangfuseTracing(
        langfuseUrl = "https://langfuse.example.com",
        publicKey = "pk-lf-test",
        secretKey = "sk-lf-test",
        environment = "test",
        release = "v0.0.0",
        version = "1.0.0",
        httpClient = http,
        restoreInterrupt = () => ()
      ) {
    val batches: mutable.Buffer[Seq[ujson.Obj]] = mutable.Buffer.empty

    override protected def sendBatch(events: Seq[ujson.Obj]): Result[Unit] = {
      batches += events
      super.sendBatch(events)
    }
  }

  /** Returns each completion in turn, then the last one again. */
  private class SequencedClient(completions: Seq[Completion]) extends LLMClient {
    private var calls = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val completion = completions(calls.min(completions.size - 1))
      calls += 1
      Right(completion)
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private case class EchoResult(echo: String)
  private object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  private def echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).buildSafe()

  private val toolCall = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))

  private val client = () =>
    new SequencedClient(
      Seq(
        Completion(
          "turn-1",
          0L,
          "",
          "test-model",
          AssistantMessage("", Seq(toolCall)),
          List(toolCall),
          Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))
        ),
        Completion(
          "turn-2",
          0L,
          "Echoed.",
          "test-model",
          AssistantMessage("Echoed."),
          usage = Some(TokenUsage(promptTokens = 30, completionTokens = 5, totalTokens = 35))
        )
      )
    )

  private def run(): (RecordingLangfuseTracing, MockHttpClient, Seq[ujson.Obj]) = {
    val http    = new MockHttpClient(HttpResponse(200, """{"successes":1}"""))
    val tracing = new RecordingLangfuseTracing(http)

    val result = echoTool.flatMap { tool =>
      new Agent(client())
        .run("Echo hello", new ToolRegistry(Seq(tool)), context = AgentContext(tracing = Some(tracing)))
    }

    result.map(_.status) shouldBe Right(AgentStatus.Complete)
    (tracing, http, tracing.batches.toSeq.flatten)
  }

  private def ofType(events: Seq[ujson.Obj], eventType: String): Seq[ujson.Obj] =
    events.filter(_("type").str == eventType)

  "LangfuseTracing, for an agent run with a tool call" should "post every batch it builds to the ingestion endpoint" in {
    val (tracing, http, _) = run()

    tracing.batches should not be empty
    http.postCallCount shouldBe tracing.batches.size
    http.lastUrl shouldBe Some("https://langfuse.example.com/api/public/ingestion")
    ujson.read(http.lastBody.getOrElse(fail("nothing was posted")))("batch").arr should not be empty
  }

  it should "send a generation per LLM call, and a span for the tool between them" in {
    val (_, _, events) = run()

    val generations = ofType(events, "generation-create")
    generations.map(_("body")("metadata")("completion_id").str) shouldBe Seq("turn-1", "turn-2")
    generations.map(_("body")("model").str).distinct shouldBe Seq("test-model")

    val tools = ofType(events, "span-create").filter(_("body")("name").str == "Tool Execution: echo")
    tools should have size 1
    tools.head("body")("input")("arguments").str shouldBe """{"message":"hello"}"""
    tools.head("body")("metadata")("success").bool shouldBe true

    val order = events.indexOf(generations.head) < events.indexOf(tools.head) &&
      events.indexOf(tools.head) < events.indexOf(generations(1))
    withClue("the tool span should come after the first generation and before the second: ")(order shouldBe true)
  }

  it should "record the token usage of each LLM call" in {
    val (_, _, events) = run()

    val usage = ofType(events, "event-create").filter(_("body")("name").str.startsWith("Token Usage"))
    usage.map(_("body")("output")("prompt_tokens").num.toInt) shouldBe Seq(20, 30)
    usage.map(_("body")("output")("total_tokens").num.toInt) shouldBe Seq(30, 35)
  }

  it should "end with a trace of the whole conversation, whose spans all belong to it" in {
    val (tracing, _, _) = run()

    val last  = tracing.batches.last
    val trace = last.head
    trace("type").str shouldBe "trace-create"
    trace("body")("name").str shouldBe "LLM4S Agent Run"
    trace("body")("input").str shouldBe "Echo hello"
    trace("body")("output").str shouldBe "Echoed."
    trace("body")("metadata")("status").str shouldBe AgentStatus.Complete.toString

    val spans = last.tail
    spans.map(_("type").str).distinct shouldBe Seq("span-create")
    // user, assistant asking for the tool, tool result, final assistant answer
    spans.map(_("body")("metadata")("message_type").str) shouldBe
      Seq("UserMessage", "AssistantMessage", "ToolMessage", "AssistantMessage")
    spans.foreach(_("body")("traceId").str shouldBe trace("body")("id").str)
  }
}
