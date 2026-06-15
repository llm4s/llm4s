package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.{ ContextWindowResolver, GroqConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for the Groq ultra-low latency inference provider.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast and does not require any API keys. Run them with:
 *
 * {{{
 *   sbt "it/testOnly org.llm4s.llmconnect.smoke.*"
 * }}}
 *
 * or the `sbt testSmoke` alias.
 *
 * Requires: `GROQ_API_KEY` environment variable.
 *
 * Groq's core differentiator is ultra-low latency (up to 800 tokens/sec on LPU hardware).
 * The `complete` test enforces a 5-second wall-clock bound to catch regressions.
 */
class GroqSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver = ContextWindowResolver(mrs)

  // scalafix:off DisableSyntax.NoSystemGetenv
  private val apiKey: Option[String] = Option(System.getenv("GROQ_API_KEY")).filter(_.nonEmpty)
  // scalafix:on DisableSyntax.NoSystemGetenv

  private val model = "llama-3.1-8b-instant"

  private def config(key: String): GroqConfig =
    GroqConfig.fromValues(
      modelName = model,
      apiKey = key,
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Groq" should "complete a basic request within 5 seconds" in {
    assume(apiKey.isDefined, "GROQ_API_KEY not set — skipping cloud smoke test")

    val clientResult = LLMConnect.getClient(config(apiKey.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client    = clientResult.toOption.get
    val startedAt = System.currentTimeMillis()
    val result    = client.complete(conversation, CompletionOptions())
    val elapsed   = System.currentTimeMillis() - startedAt

    withClue(s"Completion failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    withClue(s"Groq latency exceeded 5s SLA: ${elapsed}ms") {
      elapsed should be < 5000L
    }
  }

  it should "stream a response and emit chunks" in {
    assume(apiKey.isDefined, "GROQ_API_KEY not set — skipping cloud smoke test")

    val client = LLMConnect.getClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }
}
