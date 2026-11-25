package org.llm4s.trace

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
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
import java.time.Instant

/**
 * What `LangfuseTracing` sends for a whole agent run, which the agent traces as `graph.*` custom
 * events - a mock LLM that asks for one tool call, then answers - rather than for one event at a
 * time, which `LangfuseTracingSpec` and `LangfuseTracingEdgeCasesSpec` cover (#1003).
 *
 * Nothing leaves the JVM: batches go to a `MockHttpClient`.
 *
 * Based on the mock-backed cases of `LangfuseTracingIntegrationSpec` in #1035 by
 * @kannupriyakalra. The cases that `LangfuseTracingSpec` and `LangfuseTracingEdgeCasesSpec`
 * already had (span shapes per event, the Basic auth header, the endpoint path,
 * skipping export when a key is empty) are not repeated; the token-usage counts and the shared
 * trace id went into `LangfuseTracingEdgeCasesSpec`.
 */
class LangfuseTracingAgentRunSpec extends AnyFlatSpec with Matchers {

  /** A LangfuseTracing that also keeps every batch it sends, in order, before sending it. */
  private class RecordingLangfuseTracing(http: MockHttpClient)
      extends LangfuseTracing(
        langfuseUrl = "https://langfuse.example.com",
        publicKey = Secret("pk-lf-test"),
        secretKey = Secret("sk-lf-test"),
        environment = "test",
        release = "v0.0.0",
        version = "1.0.0",
        httpClient = http,
        restoreInterrupt = () => ()
      ) {
    val batches: mutable.Buffer[Seq[SentSpan]] = mutable.Buffer.empty

    override protected def sendBatch(request: ujson.Obj): Result[Unit] = {
      batches += SentSpan.all(request.render())
      super.sendBatch(request)
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

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def contextWindow: Int     = 4096
    override def reserveCompletion: Int = 1024
  }

  private case class EchoResult(echo: String)
  private object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  private def echoTool = ToolBuilder[EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).build()

  private val toolCall = ToolCall(ToolCallId("call-1"), "echo", ujson.Obj("message" -> "hello"))

  private val client = () =>
    new SequencedClient(
      Seq(
        Completion(
          "turn-1",
          Instant.EPOCH,
          ModelName("test-model"),
          AssistantMessage("", Seq(toolCall)),
          Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))
        ),
        Completion(
          "turn-2",
          Instant.EPOCH,
          ModelName("test-model"),
          AssistantMessage("Echoed."),
          usage = Some(TokenUsage(promptTokens = 30, completionTokens = 5, totalTokens = 35))
        )
      )
    )

  private def run(): (RecordingLangfuseTracing, MockHttpClient, Seq[SentSpan]) = {
    val http    = new MockHttpClient(HttpResponse(200, "{}"))
    val tracing = new RecordingLangfuseTracing(http)

    val result = for {
      tool <- echoTool
      agent <- Agent
        .builder("assistant", client())
        .withTools(ToolRegistry.of(Seq(tool)).toOption.get)
        .withTracing(tracing)
        .build()
      done <- agent.run("Echo hello")
    } yield done

    result.map(_.status) shouldBe Right(AgentStatus.Completed("Echoed."))
    (tracing, http, tracing.batches.toSeq.flatten)
  }

  private def graphEvents(spans: Seq[SentSpan]): Seq[SentSpan] =
    spans.filter(s => s.kind == "event" && s.name.startsWith("graph."))

  private def names(spans: Seq[SentSpan]): Seq[String] = graphEvents(spans).map(_.name)

  "LangfuseTracing, for an agent run with a tool call" should "post every export it builds to the OTel endpoint" in {
    val (tracing, http, _) = run()

    tracing.batches should not be empty
    http.postCallCount shouldBe tracing.batches.size
    http.lastUrl shouldBe Some("https://langfuse.example.com/api/public/otel/v1/traces")
    SentSpan.all(http.lastBody.getOrElse(fail("nothing was posted"))) should not be empty
  }

  it should "send the whole run as one trace, every span under the run's" in {
    val (_, _, spans) = run()

    spans.map(_.traceId).distinct should have size 1
    val runSpan = spans.find(_.name == "invoke_agent assistant").getOrElse(fail("no run span"))
    runSpan.setsTrace shouldBe true
    runSpan.parentSpanId shouldBe None
    runSpan.output.str shouldBe "Echoed."
    val ids = spans.map(_.spanId).toSet
    spans
      .filterNot(_ eq runSpan)
      .foreach(s => ids should contain(s.parentSpanId.getOrElse(fail(s"${s.name} has no parent"))))
    spans.filter(_.kind == "generation").map(_.name).distinct shouldBe Seq("chat test-model")
    spans.find(_.name == "execute_tool echo") should not be empty
  }

  it should "send the run's graph events as custom events, from its start to its completion" in {
    val (_, _, events) = run()

    val all = names(events)
    all.head shouldBe "graph.run_started"
    all.last shouldBe "graph.run_completed"
    all should contain("graph.task_completed")
    graphEvents(events).foreach(_.meta("source").str shouldBe "custom_event")
  }

  it should "send a task for each model call, and for the tool between them" in {
    val (_, _, events) = run()

    val completed = graphEvents(events).filter(_.name == "graph.task_completed")
    completed.map(_.input("nodeId").str) shouldBe Seq(
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

    val inputs = graphEvents(events).map(_.input)
    inputs.map(_("runId").str).distinct should have size 1
    inputs.map(_("threadId").str).distinct should have size 1
    inputs.map(_("seq").num) shouldBe sorted
  }
}
