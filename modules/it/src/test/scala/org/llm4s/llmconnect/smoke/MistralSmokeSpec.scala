package org.llm4s.llmconnect.smoke

import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, MistralConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.MistralClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Mistral.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `MISTRAL_API_KEY` environment variable.
 */
class MistralSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("MISTRAL_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): MistralConfig =
    MistralConfig.fromValues(
      modelName = "mistral-small-latest",
      apiKey = key,
      baseUrl = "https://api.mistral.ai"
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Mistral" should "complete a basic request" in {
    assume(apiKey.isDefined, "MISTRAL_API_KEY not set")

    val clientResult = MistralClient(config(apiKey.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
    completion.toOption.get.usage shouldBe defined
    completion.toOption.get.usage.get.promptTokens should be > 0
    completion.toOption.get.usage.get.completionTokens should be > 0
  }

  it should "indicate that streaming is not yet supported" in {
    assume(apiKey.isDefined, "MISTRAL_API_KEY not set")

    val client = MistralClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    // Mistral v1 does not support streaming; verify it returns Left(ConfigurationError)
    // rather than throwing an exception — the contract is graceful degradation.
    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ConfigurationError]
  }

  it should "return AuthenticationError for an invalid API key" in {
    val client = MistralClient(config("invalid-key-for-testing")).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
