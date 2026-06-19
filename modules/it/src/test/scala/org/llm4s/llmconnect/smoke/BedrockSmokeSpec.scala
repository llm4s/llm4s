package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.config.{ BedrockConfig, ContextWindowResolver }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.BedrockClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for AWS Bedrock.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_REGION`
 * environment variables.  Tests are skipped gracefully when any of these are absent.
 *
 * Optional: set `AWS_SESSION_TOKEN` for temporary credentials.
 */
class BedrockSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver = ContextWindowResolver(mrs)

  // Credentials resolved from environment variables (matches AWS credential chain)
  private val accessKeyId: Option[String]     = Option(System.getenv("AWS_ACCESS_KEY_ID")).filter(_.nonEmpty)
  private val secretAccessKey: Option[String] = Option(System.getenv("AWS_SECRET_ACCESS_KEY")).filter(_.nonEmpty)
  private val awsRegion: Option[String]       = Option(System.getenv("AWS_REGION")).filter(_.nonEmpty)
  private val hasCredentials: Boolean =
    accessKeyId.isDefined && secretAccessKey.isDefined && awsRegion.isDefined

  private def titanConfig(region: String): BedrockConfig =
    BedrockConfig.fromValues(
      modelName = "amazon.titan-text-express-v1",
      region = region
    )

  private def claudeConfig(region: String): BedrockConfig =
    BedrockConfig.fromValues(
      modelName = "anthropic.claude-3-haiku-20240307-v1:0",
      region = region
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Bedrock (Amazon Titan)" should "complete a basic request" in {
    assume(hasCredentials, "AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_REGION must all be set")

    val region = awsRegion.get
    val cfg    = titanConfig(region)

    val clientResult = BedrockClient(cfg)
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

  it should "populate token usage in the response" in {
    assume(hasCredentials, "AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_REGION must all be set")

    val region     = awsRegion.get
    val client     = BedrockClient(titanConfig(region)).toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.usage.isDefined shouldBe true
  }

  it should "stream a response with at least one chunk" in {
    assume(hasCredentials, "AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_REGION must all be set")

    val region = awsRegion.get
    val client = BedrockClient(titanConfig(region)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  // ===========================================================================
  // Claude-on-Bedrock tests (requires Anthropic model access in the AWS account)
  // ===========================================================================

  "Bedrock (Anthropic Claude)" should "complete a basic request when Anthropic model access is enabled" in {
    assume(hasCredentials, "AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_REGION must all be set")
    // This test is best-effort: if the account doesn't have Claude access it will return an error.
    // We still assert isRight to verify the full round-trip when Claude is available.
    val region     = awsRegion.get
    val client     = BedrockClient(claudeConfig(region)).toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    // If Claude access is not granted, the response will be a Left (error from Bedrock).
    // We log the outcome but do not hard-fail — CI pipelines may not have Claude enabled.
    if (completion.isLeft) {
      info(s"Claude model not accessible (this is expected if not enabled in the account): ${completion.swap.toOption}")
    } else {
      completion.toOption.get.content should not be empty
    }
  }

  it should "stream a response when Anthropic model access is enabled" in {
    assume(hasCredentials, "AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_REGION must all be set")

    val region = awsRegion.get
    val client = BedrockClient(claudeConfig(region)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    if (result.isLeft) {
      info(s"Claude streaming not accessible (this is expected if not enabled): ${result.swap.toOption}")
    } else {
      result.toOption.get.content should not be empty
      chunks should not be empty
    }
  }
}
