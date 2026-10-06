package org.llm4s.trace

import org.llm4s.agent.{ Agent, AgentStatus }
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
 * What `LangfuseTracing` sends for a whole agent run, which the agent traces as `graph.*` custom
 * events - a mock LLM that asks for one tool call, then answers - rather than for one event at a
 * time, which `LangfuseTracingSpec` and `LangfuseTracingEdgeCasesSpec` cover (#1003).
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

    val result = for {
      tool  <- echoTool
      agent <- Agent.builder("assistant", client()).withTools(new ToolRegistry(Seq(tool))).withTracing(tracing).build()
      done  <- agent.run("Echo hello")
    } yield done

    result.map(_.status) shouldBe Right(AgentStatus.Completed("Echoed."))
    (tracing, http, tracing.batches.toSeq.flatten)
  }

  private def graphEvents(events: Seq[ujson.Obj]): Seq[ujson.Obj] =
    events.filter(e => e("type").str == "event-create" && e("body")("name").str.startsWith("graph."))

  private def names(events: Seq[ujson.Obj]): Seq[String] = graphEvents(events).map(_("body")("name").str)

  "LangfuseTracing, for an agent run with a tool call" should "post every batch it builds to the ingestion endpoint" in {
    val (tracing, http, _) = run()

    tracing.batches should not be empty
    http.postCallCount shouldBe tracing.batches.size
    http.lastUrl shouldBe Some("https://langfuse.example.com/api/public/ingestion")
    ujson.read(http.lastBody.getOrElse(fail("nothing was posted")))("batch").arr should not be empty
  }

  it should "send the run's graph events as custom events, from its start to its completion" in {
    val (_, _, events) = run()

    val all = names(events)
    all.head shouldBe "graph.run_started"
    all.last shouldBe "graph.run_completed"
    all should contain("graph.task_completed")
    graphEvents(events).foreach(_("body")("metadata")("source").str shouldBe "custom_event")
  }

  it should "send a task for each model call, and for the tool between them" in {
    val (_, _, events) = run()

    val completed = graphEvents(events).filter(_("body")("name").str == "graph.task_completed")
    completed.map(_("body")("input")("nodeId").str) shouldBe Seq(
      "input",
      "assistant/model",
      "assistant/call-tool",
      "assistant/collect",
      "assistant/model",
      "assistant/finish"
    )
  }

  it should "give every event the same run and thread, in order" in {
    val (_, _, events) = run()

    val inputs = graphEvents(events).map(_("body")("input"))
    inputs.map(_("runId").str).distinct should have size 1
    inputs.map(_("threadId").str).distinct should have size 1
    inputs.map(_("seq").num) shouldBe sorted
  }
}
