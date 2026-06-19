package org.llm4s.llmconnect.smoke

import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, WatsonXConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.WatsonXClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for IBM WatsonX.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `WATSONX_API_KEY` and `WATSONX_PROJECT_ID` environment variables.
 * Optional: `WATSONX_BASE_URL` (defaults to us-south.ml.cloud.ibm.com).
 *
 * Tagged as `CloudSmoke` — skip gracefully when credentials are absent.
 */
class WatsonXSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver = ContextWindowResolver(mrs)

  private val apiKey: Option[String]    = Option(System.getenv("WATSONX_API_KEY")).filter(_.nonEmpty)
  private val projectId: Option[String] = Option(System.getenv("WATSONX_PROJECT_ID")).filter(_.nonEmpty)
  private val baseUrl: String           = Option(System.getenv("WATSONX_BASE_URL")).filter(_.nonEmpty)
    .getOrElse(WatsonXConfig.DEFAULT_BASE_URL)

  private def config(key: String, pid: String): WatsonXConfig =
    WatsonXConfig.fromValues(
      modelName = "ibm/granite-3-8b-instruct",
      apiKey = key,
      projectId = pid,
      baseUrl = baseUrl
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "WatsonX" should "complete a basic request with ibm/granite-3-8b-instruct" in {
    assume(apiKey.isDefined, "WATSONX_API_KEY not set — skipping WatsonX smoke test")
    assume(projectId.isDefined, "WATSONX_PROJECT_ID not set — skipping WatsonX smoke test")

    val clientResult = WatsonXClient(config(apiKey.get, projectId.get))
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

  it should "return non-empty token usage on a basic completion" in {
    assume(apiKey.isDefined, "WATSONX_API_KEY not set — skipping WatsonX smoke test")
    assume(projectId.isDefined, "WATSONX_PROJECT_ID not set — skipping WatsonX smoke test")

    val client     = WatsonXClient(config(apiKey.get, projectId.get)).toOption.get
    val completion = client.complete(conversation, CompletionOptions()).toOption.get

    withClue("Expected token usage to be populated") {
      completion.usage.isDefined shouldBe true
    }
    completion.usage.get.promptTokens should be > 0
    completion.usage.get.completionTokens should be > 0
    completion.usage.get.totalTokens should be > 0
  }

  it should "stream a response with at least one chunk" in {
    assume(apiKey.isDefined, "WATSONX_API_KEY not set — skipping WatsonX smoke test")
    assume(projectId.isDefined, "WATSONX_PROJECT_ID not set — skipping WatsonX smoke test")

    val client = WatsonXClient(config(apiKey.get, projectId.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "succeed with IAM token exchange (verifying no credential errors)" in {
    assume(apiKey.isDefined, "WATSONX_API_KEY not set — skipping WatsonX smoke test")
    assume(projectId.isDefined, "WATSONX_PROJECT_ID not set — skipping WatsonX smoke test")

    val client = WatsonXClient(config(apiKey.get, projectId.get)).toOption.get

    // getBearerToken exercises the IAM token exchange end-to-end
    val tokenResult = client.getBearerToken()

    withClue(s"IAM token exchange failed: ${tokenResult.swap.toOption}") {
      tokenResult.isRight shouldBe true
    }
    tokenResult.toOption.get should not be empty
  }

  it should "return ConfigurationError or AuthenticationError for invalid API key" in {
    assume(projectId.isDefined, "WATSONX_PROJECT_ID not set — skipping WatsonX smoke test")

    val client = WatsonXClient(config("invalid-api-key-for-testing", projectId.get)).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get match {
      case _: ConfigurationError  => succeed
      case _: AuthenticationError => succeed
      case other                  => fail(s"Expected ConfigurationError or AuthenticationError, got: $other")
    }
  }
}
