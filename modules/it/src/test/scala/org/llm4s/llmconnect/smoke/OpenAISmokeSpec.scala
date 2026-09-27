package org.llm4s.llmconnect.smoke

import org.scalatest.EitherValues
import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ReasoningEffort, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud

/**
 * Cloud smoke tests for OpenAI.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `OPENAI_API_KEY` environment variable.
 */
@Cloud
class OpenAISmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  /**
   * The cheapest reasoning model in the gpt-5 family. The registry flags it `supports_reasoning`,
   * so it gets `reasoning_effort`, `max_completion_tokens` and no sampling parameters.
   */
  private val ReasoningModel = "gpt-5-mini"

  private def config(key: String, model: String = "gpt-4o-mini"): OpenAIConfig =
    OpenAIConfig
      .fromValues(
        modelName = model,
        apiKey = key,
        organization = None,
        baseUrl = "https://api.openai.com/v1"
      )
      .value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "OpenAI" should "complete a basic request" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val clientResult = OpenAIClient(config(apiKey.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }

  it should "stream a response" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val client = OpenAIClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "complete with a reasoning effort on a reasoning model and report its reasoning tokens" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val client = OpenAIClient(config(apiKey.get, ReasoningModel)).toOption.get
    // max_completion_tokens counts reasoning tokens too; too small a cap can be spent entirely on
    // reasoning and leave no visible content, so this leaves room at low effort.
    val options    = CompletionOptions(maxTokens = Some(1024)).withReasoning(ReasoningEffort.Low)
    val completion = client.complete(conversation, options)
    client.close()

    withClue(s"Reasoning completion failed (was reasoning_effort rejected?): ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
    // OpenAI reports completion_tokens_details.reasoning_tokens for reasoning models, but at low
    // effort on a trivial prompt the count can legitimately be 0, so assert it is reported, not
    // that it is positive.
    val usage = completion.toOption.get.usage
    usage shouldBe defined
    usage.get.thinkingTokens shouldBe defined
    usage.get.thinkingTokens.get should be >= 0
  }

  it should "complete on a gpt-5 model with default CompletionOptions" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    // The defaults include temperature = 0.7, which the gpt-5 family rejects; the client must
    // leave sampling parameters out for reasoning models. No maxTokens: these are the defaults.
    val client     = OpenAIClient(config(apiKey.get, ReasoningModel)).toOption.get
    val completion = client.complete(conversation, CompletionOptions())
    client.close()

    withClue(s"gpt-5 completion with default options failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }

  it should "report token usage on a streamed completion" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    // Streams carry usage only because the client sets stream_options.include_usage; OpenAI
    // then sends it on a final chunk with no choices.
    val client = OpenAIClient(config(apiKey.get)).toOption.get
    val result = client.streamComplete(conversation, CompletionOptions(maxTokens = Some(16)), _ => ())
    client.close()

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val usage = result.toOption.get.usage
    withClue("A streamed completion reported no usage: ") {
      usage shouldBe defined
    }
    usage.get.promptTokens should be > 0
    usage.get.completionTokens should be > 0
    usage.get.totalTokens should be > 0
  }

  it should "return AuthenticationError for invalid key" in {
    val client = OpenAIClient(config("sk-invalid-key-for-testing")).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
