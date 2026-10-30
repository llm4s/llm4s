package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.RequestyProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Requesty (`llm4s-openai`): the OpenAI client pointed at Requesty's router.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt testSmoke`.
 *
 * Requires: `REQUESTY_API_KEY` environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 *
 * The client is built by `RequestyProvider`, as a `requesty` section would build it, so it carries the
 * `requesty` provider id. Assertions are structural; generated text is never compared.
 */
@Cloud
class RequestySmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("REQUESTY_API_KEY")).filter(_.nonEmpty)

  /** Requesty routes by `<vendor>/<model>`; a cheap OpenAI model keeps the cost down. */
  private val Model = "openai/gpt-4o-mini"

  private def client(key: String): Result[LLMClient] =
    OpenAIConfig
      .fromValues(Model, key, None, RequestyProvider.DEFAULT_BASE_URL, Some(RequestyProvider.id))
      .flatMap(RequestyProvider.buildClient(_, LlmClientOptions.default))

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(16))

  "Requesty" should "complete a basic request" in {
    Tier.require(apiKey.isDefined, "REQUESTY_API_KEY not set")

    val llm        = client(apiKey.get).value
    val completion = llm.complete(conversation, options)
    llm.close()

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.value.content should not be empty
  }

  it should "stream a basic request" in {
    Tier.require(apiKey.isDefined, "REQUESTY_API_KEY not set")

    val llm    = client(apiKey.get).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = llm.streamComplete(conversation, options, c => chunks += c)
    llm.close()

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val streamed = chunks.flatMap(_.content).mkString
    streamed should not be empty
    streamed shouldBe result.value.content
  }

  it should "return AuthenticationError for an invalid API key" in {
    val llm    = client("invalid-key-for-testing").value
    val result = llm.complete(conversation, options)
    llm.close()

    result.swap.value shouldBe an[AuthenticationError]
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                          = "Requesty"
  override protected def apiKeyEnvVar: String                           = "REQUESTY_API_KEY"
  override protected def contractKey: Option[String]                    = apiKey
  override protected def contractClient(key: String): Result[LLMClient] = client(key)

  registerCapabilityContract()
}
