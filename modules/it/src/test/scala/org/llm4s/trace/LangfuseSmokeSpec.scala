package org.llm4s.trace

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.config.LangfuseConfigLoader
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.LangfuseConfig
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }
import upickle.default.{ macroRW, ReadWriter }

import java.nio.charset.StandardCharsets
import java.util.{ Base64, UUID }
import scala.collection.mutable
import java.time.Instant

/**
 * Cloud smoke test for Langfuse: an agent run traced to a real Langfuse project arrives there.
 *
 * The LLM is a mock - the only live service is Langfuse - so this costs nothing but a trace in
 * the project it points at. It checks that Langfuse's OpenTelemetry endpoint accepts every
 * export `LangfuseTracing` sends for a run with a tool call (#2883), and then reads the run's
 * events back through Langfuse's observations API. The shape of each export is covered without a
 * network by `LangfuseTracingSpec`, `LangfuseTracingEdgeCasesSpec`, `LangfuseOtlpServerSpec` and
 * `LangfuseTracingAgentRunSpec` in `llm4s-observability`.
 *
 * Requires `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY`, and `LANGFUSE_URL` for anything
 * but Langfuse Cloud. They are read through `LangfuseConfigLoader` - the
 * `llm4s.tracing.langfuse` block of `llm4s-observability`'s `reference.conf` - as an
 * application would read them, not from the environment directly.
 * Tier: `@Cloud` - `sbt testSmoke`.
 *
 * Based on the live-Langfuse cases of `LangfuseTracingIntegrationSpec` in #1035 by
 * @kannupriyakalra (#1003).
 */
@Cloud
class LangfuseSmokeSpec extends AnyFlatSpec with Matchers with EitherValues with Eventually {

  private val config: LangfuseConfig = LangfuseConfigLoader.default().value

  private def keys: Option[(Secret, Secret)] =
    for {
      publicKey <- config.publicKey
      secretKey <- config.secretKey
    } yield (publicKey, secretKey)

  /** A LangfuseTracing that keeps the result of every export it sends; the agent swallows them. */
  private class CheckedLangfuseTracing(publicKey: Secret, secretKey: Secret)
      extends LangfuseTracing(config.url, publicKey, secretKey, config.env, config.release, config.version) {
    val results: mutable.Buffer[Result[Unit]] = mutable.Buffer.empty

    override protected def sendBatch(request: ujson.Obj): Result[Unit] = {
      val result = super.sendBatch(request)
      results.synchronized(results += result)
      result
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

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private case class EchoResult(echo: String)
  private object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  private def echoTool = ToolBuilder[EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).buildSafe()

  private def client: LLMClient = {
    val call  = ToolCall(ToolCallId("call-1"), "echo", ujson.Obj("message" -> "hello"))
    val usage = Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))
    new SequencedClient(
      Seq(
        Completion("turn-1", Instant.EPOCH, ModelName("mock-model"), AssistantMessage("", Seq(call)), usage),
        Completion("turn-2", Instant.EPOCH, ModelName("mock-model"), AssistantMessage("Echoed."), usage = usage)
      )
    )
  }

  // Langfuse ingests asynchronously; a trace can take a few seconds to become readable.
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(2, Seconds))

  "Langfuse" should "accept every export of a traced agent run, and serve its events back" in {
    Tier.require(keys.isDefined, "LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY are not set")
    val (publicKey, secretKey) = keys.get

    // The query is the conversation trace's input, so a unique one finds this run's trace.
    val query   = s"llm4s smoke ${UUID.randomUUID()}: echo hello"
    val tracing = new CheckedLangfuseTracing(publicKey, secretKey)
    val result = for {
      tool <- echoTool
      agent <- Agent
        .builder("smoke", client)
        .withTools(ToolRegistry.of(Seq(tool)).toOption.get)
        .withTracing(tracing)
        .build()
      done <- agent.run(query)
    } yield done

    result.map(_.status) shouldBe Right(AgentStatus.Completed("Echoed."))
    val done     = result.fold(e => fail(e.message), identity)
    val threadId = done.threadId.value
    tracing.results should not be empty
    all(tracing.results) shouldBe Right(())

    val apiBase = LangfuseOtlp.baseUrl(config.url)
    val auth =
      "Basic " + Base64.getEncoder.encodeToString(
        s"${publicKey.reveal}:${secretKey.reveal}".getBytes(StandardCharsets.UTF_8)
      )
    val http = Llm4sHttpClient.create()

    eventually {
      val response = http.get(
        s"$apiBase/api/public/v2/observations",
        headers = Map("Authorization" -> auth),
        params = Map("name" -> "graph.run_completed", "fields" -> "core,basic,io", "limit" -> "50"),
        timeout = scala.concurrent.duration.Duration(30, "seconds")
      ) match {
        case Right(r)    => r
        case Left(error) => fail(s"GET /api/public/v2/observations failed: ${error.message}")
      }
      withClue(s"GET /api/public/v2/observations returned ${response.statusCode}: ") {
        response.statusCode shouldBe 200
      }
      // The agent traces its run as graph.* events, each carrying the thread it ran on. The v2 API
      // returns input as the raw string sent: here, the event's data as JSON text.
      def input(observation: ujson.Value): Option[ujson.Value] =
        observation.obj.get("input").flatMap(_.strOpt).flatMap(text => scala.util.Try(ujson.read(text)).toOption)
      val ours = ujson
        .read(response.body)("data")
        .arr
        .flatMap(input)
        .filter(_.objOpt.exists(_.get("threadId").exists(_.str == threadId)))
      ours should not be empty
      ours.map(_("runId").str) shouldBe Seq(done.runId.value)
    }
  }
}
