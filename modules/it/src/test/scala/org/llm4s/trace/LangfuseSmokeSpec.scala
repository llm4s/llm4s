package org.llm4s.trace

import org.llm4s.agent.{ Agent, AgentContext, AgentStatus }
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

/**
 * Cloud smoke test for Langfuse: an agent run traced to a real Langfuse project arrives there.
 *
 * The LLM is a mock - the only live service is Langfuse - so this costs nothing but a trace in
 * the project it points at. It checks that the ingestion API accepts every batch
 * `LangfuseTracing` sends for a run with a tool call, and then reads the run's conversation
 * trace back through Langfuse's public API. The shape of each batch is covered without a
 * network by `LangfuseTracingSpec`, `LangfuseTracingEdgeCasesSpec` and
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

  private def keys: Option[(String, String)] =
    for {
      publicKey <- config.publicKey
      secretKey <- config.secretKey
    } yield (publicKey, secretKey)

  /** A LangfuseTracing that keeps the result of every batch it sends; the agent swallows them. */
  private class CheckedLangfuseTracing(publicKey: String, secretKey: String)
      extends LangfuseTracing(config.url, publicKey, secretKey, config.env, config.release, config.version) {
    val results: mutable.Buffer[Result[Unit]] = mutable.Buffer.empty

    override protected def sendBatch(events: Seq[ujson.Obj]): Result[Unit] = {
      val result = super.sendBatch(events)
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

  private def client: LLMClient = {
    val call  = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))
    val usage = Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))
    new SequencedClient(
      Seq(
        Completion("turn-1", 0L, "", "mock-model", AssistantMessage("", Seq(call)), List(call), usage),
        Completion("turn-2", 0L, "Echoed.", "mock-model", AssistantMessage("Echoed."), usage = usage)
      )
    )
  }

  // Langfuse ingests asynchronously; a trace can take a few seconds to become readable.
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(2, Seconds))

  "Langfuse" should "accept every batch of a traced agent run, and serve its trace back" in {
    Tier.require(keys.isDefined, "LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY are not set")
    val (publicKey, secretKey) = keys.get

    // The query is the conversation trace's input, so a unique one finds this run's trace.
    val query   = s"llm4s smoke ${UUID.randomUUID()}: echo hello"
    val tracing = new CheckedLangfuseTracing(publicKey, secretKey)
    val state = echoTool.flatMap { tool =>
      new Agent(client).run(query, new ToolRegistry(Seq(tool)), context = AgentContext(tracing = Some(tracing)))
    }

    state.map(_.status) shouldBe Right(AgentStatus.Complete)
    tracing.results should not be empty
    all(tracing.results) shouldBe Right(())

    val apiBase = DefaultLangfuseBatchSender.normalizeIngestionUrl(config.url).stripSuffix("/api/public/ingestion")
    val auth =
      "Basic " + Base64.getEncoder.encodeToString(s"$publicKey:$secretKey".getBytes(StandardCharsets.UTF_8))
    val http = Llm4sHttpClient.create()

    eventually {
      val response = http.get(
        s"$apiBase/api/public/traces",
        headers = Map("Authorization" -> auth),
        params = Map("name" -> "LLM4S Agent Run", "limit" -> "50"),
        timeout = scala.concurrent.duration.Duration(30, "seconds")
      ) match {
        case Right(r)    => r
        case Left(error) => fail(s"GET /api/public/traces failed: ${error.message}")
      }
      withClue(s"GET /api/public/traces returned ${response.statusCode}: ") {
        response.statusCode shouldBe 200
      }
      val ours = ujson.read(response.body)("data").arr.filter(_("input").strOpt.contains(query))
      ours should not be empty
      // The last state the agent traced is the finished conversation.
      ours.map(_("output").strOpt) should contain(Some("Echoed."))
    }
  }
}
