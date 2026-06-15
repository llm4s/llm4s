package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.config.{ ContextWindowResolver, FireworksConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.FireworksClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for the Fireworks AI provider.
 *
 * These tests require a live `FIREWORKS_API_KEY` environment variable and are
 * excluded from `sbt test`. Run them with:
 * {{{
 *   sbt "it/testOnly org.llm4s.llmconnect.smoke.FireworksSmokeSpec"
 * }}}
 * or via the `sbt testSmoke` alias.
 *
 * Tests are tagged `CloudSmoke` and skip gracefully when the API key is absent.
 */
class FireworksSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("FIREWORKS_API_KEY")).filter(_.nonEmpty)

  private val llamaModel      = "accounts/fireworks/models/llama-v3p1-8b-instruct"
  private val fireFunctionModel = "accounts/fireworks/models/firefunction-v2"

  private def config(model: String, key: String): FireworksConfig =
    FireworksConfig.fromValues(
      modelName = model,
      apiKey = key,
      baseUrl = FireworksConfig.DEFAULT_BASE_URL
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  // ==========================================================================
  // complete()
  // ==========================================================================

  "Fireworks AI" should "complete a basic request with llama-v3p1-8b-instruct" in {
    assume(apiKey.isDefined, "FIREWORKS_API_KEY not set — skipping smoke test")

    val clientResult = FireworksClient(config(llamaModel, apiKey.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    val result = completion.toOption.get
    result.content should not be empty
    result.usage shouldBe defined
    result.usage.get.promptTokens should be > 0
    result.usage.get.completionTokens should be > 0
    result.usage.get.totalTokens should be > 0
  }

  // ==========================================================================
  // streamComplete()
  // ==========================================================================

  it should "stream a response from llama-v3p1-8b-instruct" in {
    assume(apiKey.isDefined, "FIREWORKS_API_KEY not set — skipping smoke test")

    val client = FireworksClient(config(llamaModel, apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  // ==========================================================================
  // tool calling with firefunction-v2
  // ==========================================================================

  it should "support tool calling with firefunction-v2" in {
    assume(apiKey.isDefined, "FIREWORKS_API_KEY not set — skipping smoke test")

    val client     = FireworksClient(config(fireFunctionModel, apiKey.get)).toOption.get
    val toolConv   = Conversation(Seq(UserMessage("What is 2 + 2? Use the calculator tool.")))
    val completion = client.complete(toolConv, CompletionOptions())

    withClue(s"Tool-calling completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    // Either a text response or tool calls — both are valid
    val result = completion.toOption.get
    (result.content.nonEmpty || result.toolCalls.nonEmpty) shouldBe true
  }
}
