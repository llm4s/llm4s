package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ BedrockConfig, BedrockCredentials, ContextWindowResolver }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.BedrockClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * Cloud smoke tests for AWS Bedrock.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires `AWS_REGION` and AWS credentials the default credential chain can find
 * (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, optionally `AWS_SESSION_TOKEN`, or `AWS_PROFILE`),
 * for an account with access to the model. `BEDROCK_MODEL_ID` overrides the model.
 * The invalid-credentials test needs only network access.
 */
@Cloud
class BedrockSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private def env(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)

  private val region: Option[String] = env("AWS_REGION")
  private val hasCredentials: Boolean =
    env("AWS_ACCESS_KEY_ID").isDefined || env("AWS_PROFILE").isDefined
  private val model: String = env("BEDROCK_MODEL_ID").getOrElse("us.amazon.nova-micro-v1:0")

  private def client(region: String, credentials: Option[BedrockCredentials] = None): BedrockClient =
    BedrockClient(BedrockConfig.fromValues(model, region, credentials).value).value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  private def requireAws(): Unit =
    Tier.require(region.isDefined && hasCredentials, "AWS_REGION and AWS credentials are not set")

  "Bedrock" should "complete a basic request" in {
    requireAws()

    val completion = client(region.get).complete(conversation, CompletionOptions(maxTokens = Some(64)))

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.value.content should not be empty
  }

  it should "stream a response through ConverseStream" in {
    requireAws()

    val chunks = ListBuffer.empty[StreamedChunk]
    val result =
      client(region.get).streamComplete(conversation, CompletionOptions(maxTokens = Some(64)), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.value.content should not be empty
    chunks.flatMap(_.content) should not be empty
  }

  it should "return AuthenticationError for invalid credentials" in {
    val bogus  = Some(BedrockCredentials("AKIAINVALIDKEYFORSMOKE", "invalid-secret-key-for-smoke-test"))
    val result = client(region.getOrElse("us-east-1"), bogus).complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.value shouldBe an[AuthenticationError]
  }
}
