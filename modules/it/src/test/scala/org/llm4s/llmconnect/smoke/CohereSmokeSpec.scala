package org.llm4s.llmconnect.smoke

import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.llmconnect.config.{ CohereConfig, ContextWindowResolver }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.CohereClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Cohere.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `COHERE_API_KEY` environment variable.
 * Tag: CloudSmoke
 *
 * Note: The current CohereClient implementation (v2 scope) does not support
 * streaming. The `streamComplete` test verifies that calling it returns a
 * `Left(ConfigurationError)` rather than throwing an exception, in accordance
 * with the project's `Result[A]` error handling contract.
 */
class CohereSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("COHERE_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): CohereConfig =
    CohereConfig.fromValues(
      modelName = "command-r",
      apiKey = key,
      baseUrl = CohereConfig.DEFAULT_BASE_URL
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Cohere" should "complete a basic request" in {
    assume(apiKey.isDefined, "COHERE_API_KEY not set")

    val clientResult = CohereClient(config(apiKey.get))
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
    result.usage should not be empty
    result.usage.get.promptTokens should be > 0
    result.usage.get.completionTokens should be > 0
  }

  it should "return a Left(ConfigurationError) for streamComplete (not yet implemented)" in {
    // streamComplete returns an error immediately without contacting the API,
    // so no COHERE_API_KEY is required for this test.
    val client = CohereClient(config("placeholder-key")).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[String]
    val result = client.streamComplete(conversation, CompletionOptions(), _ => chunks += "chunk")

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[ConfigurationError]
  }

  it should "return AuthenticationError for invalid key" in {
    val client = CohereClient(config("invalid-key-for-testing")).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
