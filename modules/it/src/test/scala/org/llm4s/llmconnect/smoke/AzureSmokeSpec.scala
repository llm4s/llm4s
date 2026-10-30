package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Azure OpenAI (`llm4s-openai`, on the `openai-java` SDK since #1209).
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt testSmoke`.
 *
 * Requires:
 *  - `AZURE_OPENAI_API_KEY` - the resource key;
 *  - `AZURE_API_BASE` - the resource endpoint, e.g. `https://my-resource.openai.azure.com`;
 *  - `AZURE_OPENAI_DEPLOYMENT` - the name of a chat deployment in that resource, e.g. a `gpt-4o-mini` one.
 *
 * Optional: `AZURE_OPENAI_CUSTOM_ENDPOINT` - the same resource behind a custom domain (an API Management gateway,
 * a private endpoint). Its test is cancelled when it is unset, even under `LLM4S_IT_STRICT`, since not every
 * resource has one.
 *
 * Tier: `@Cloud` - `sbt testSmoke`.
 *
 * Covers the three endpoint shapes the client distinguishes (#1213):
 *  - the classic deployment path, `<endpoint>/openai/deployments/<deployment>`, with the default `api-version`;
 *  - a custom-domain endpoint, which is still treated as Azure;
 *  - the `/openai/v1` unified API, where no `api-version` is sent unless one is set (#1209).
 *
 * Assertions are structural; generated text is never compared.
 */
@Cloud
class AzureSmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private def env(name: String): Option[String] = Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty)

  private val apiKey: Option[String]         = env("AZURE_OPENAI_API_KEY")
  private val endpoint: Option[String]       = env("AZURE_API_BASE").map(_.stripSuffix("/"))
  private val deployment: Option[String]     = env("AZURE_OPENAI_DEPLOYMENT")
  private val customEndpoint: Option[String] = env("AZURE_OPENAI_CUSTOM_ENDPOINT").map(_.stripSuffix("/"))

  private val Required = "AZURE_OPENAI_API_KEY, AZURE_API_BASE and AZURE_OPENAI_DEPLOYMENT"

  /** The key, when every required variable is set; the contract runs only then. */
  private val credentials: Option[String] =
    for {
      key <- apiKey
      _   <- endpoint
      _   <- deployment
    } yield key

  private def requireCredentials(): Unit = Tier.require(credentials.isDefined, s"$Required not set")

  private def config(key: String, at: String): AzureConfig =
    AzureConfig
      .fromValues(
        modelName = deployment.getOrElse("gpt-4o-mini"),
        endpoint = at,
        apiKey = key,
        apiVersion = AzureConfig.DEFAULT_API_VERSION
      )
      .value

  private def classic(key: String): AzureConfig = config(key, endpoint.getOrElse("https://invalid.openai.azure.com"))

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(16))

  private def completes(cfg: AzureConfig): Unit = {
    val client     = OpenAIClient(cfg).value
    val completion = client.complete(conversation, options)
    client.close()

    withClue(s"Completion against ${cfg.endpoint} failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.value.content should not be empty
  }

  "Azure OpenAI" should "complete a basic request on the classic deployment path" in {
    requireCredentials()
    completes(classic(credentials.get))
  }

  it should "stream a basic request and report its usage" in {
    requireCredentials()

    val client = OpenAIClient(classic(credentials.get)).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, options, c => chunks += c)
    client.close()

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val streamed = chunks.flatMap(_.content).mkString
    streamed should not be empty
    streamed shouldBe result.value.content
    // The default api-version (2025-01-01-preview) accepts stream_options, so the stream reports usage.
    result.value.usage shouldBe defined
  }

  it should "complete a request on the /openai/v1 unified API, which is sent no default api-version" in {
    requireCredentials()
    completes(config(credentials.get, s"${endpoint.get}/openai/v1"))
  }

  it should "complete a request through a custom-domain endpoint" in {
    requireCredentials()
    if (customEndpoint.isEmpty) cancel("AZURE_OPENAI_CUSTOM_ENDPOINT not set (optional: not every resource has one)")
    completes(config(credentials.get, customEndpoint.get))
  }

  it should "return AuthenticationError for an invalid API key" in {
    // Needs a real resource: an unknown host fails before any key is checked.
    Tier.require(endpoint.isDefined && deployment.isDefined, "AZURE_API_BASE and AZURE_OPENAI_DEPLOYMENT not set")

    val client = OpenAIClient(classic("invalid-key-for-testing")).value
    val result = client.complete(conversation, options)
    client.close()

    result.swap.value shouldBe an[AuthenticationError]
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                          = "Azure OpenAI"
  override protected def apiKeyEnvVar: String                           = Required
  override protected def contractKey: Option[String]                    = credentials
  override protected def contractClient(key: String): Result[LLMClient] = OpenAIClient(classic(key))

  registerCapabilityContract()
}
