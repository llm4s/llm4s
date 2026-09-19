package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.provider.{ AzureProvider, OllamaProvider, OpenAIProvider }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Covers the single spec-driven validator that replaced the twelve
 * per-provider `NamedProviderValidator` objects in #1131.
 *
 * The messages asserted here are the ones users see when a provider section is
 * incomplete, and they are unchanged by the move: what changed is that each
 * provider now declares its requirements in a
 * [[org.llm4s.llmconnect.spi.ProviderConfigSpec]] instead of core holding an
 * object per provider.
 */
class NamedProviderSectionValidatorSpec extends AnyFlatSpec with Matchers {

  private def validate(
    providerName: String,
    descriptor: ProviderDescriptor,
    section: RawNamedProviderSection
  ): org.llm4s.types.Result[NamedProviderConfig] =
    val name = ProviderName(providerName)
    NamedProviderConfigNormalizer
      .normalize(name, section)
      .flatMap(NamedProviderSectionValidator.validate(name, descriptor, _))

  private def section(
    provider: String,
    model: String,
    baseUrl: Option[String] = None,
    apiKey: Option[String] = None,
    organization: Option[String] = None,
    endpoint: Option[String] = None,
    apiVersion: Option[String] = None
  ): RawNamedProviderSection =
    RawNamedProviderSection(Some(provider), Some(model), baseUrl, apiKey, organization, endpoint, apiVersion)

  private def errorFrom(result: org.llm4s.types.Result[NamedProviderConfig]): String =
    result.left.toOption.getOrElse(fail(s"Expected Left, got $result")).asInstanceOf[ConfigurationError].message

  "Azure validation" should "mention missing Azure fields by name" in {
    val message = errorFrom(validate("my-azure", AzureProvider, section("azure", "gpt-4")))

    message should include("Provider 'my-azure' (provider = azure) is missing required fields")
    message should include(
      "- apiKey: set it in llm4s.conf under providers.my-azure.apiKey (optionally from an env var, e.g. apiKey = ${?AZURE_API_KEY})"
    )
    message should include("- endpoint: the model endpoint/deployment name in your Azure OpenAI resource")
  }

  "OpenAI validation" should "mention missing OpenAI fields by name" in {
    val message = errorFrom(validate("my-openai", OpenAIProvider, section("openai", "gpt-4")))

    message should include("Provider 'my-openai' (provider = openai) is missing required fields")
    message should include(
      "- apiKey: set it in llm4s.conf under providers.my-openai.apiKey (optionally from an env var, e.g. apiKey = ${?OPENAI_API_KEY})"
    )
  }

  it should "not demand a baseUrl, because the descriptor supplies a default" in {
    val result = validate("my-openai", OpenAIProvider, section("openai", "gpt-4", apiKey = Some("sk-test")))

    result.map(_.baseUrl) shouldBe Right(None)
    OpenAIProvider.configSpec.defaultBaseUrl shouldBe Some(DefaultConfig.DEFAULT_OPENAI_BASE_URL)
  }

  "Ollama validation" should "mention missing Ollama fields by name" in {
    val message = errorFrom(validate("my-ollama", OllamaProvider, section("ollama", "llama3")))

    message should include("Provider 'my-ollama' (provider = ollama) is missing required fields")
    message should include("- baseUrl: set OLLAMA_BASE_URL (e.g. http://localhost:11434)")
  }

  "a provider from outside core" should "get its requirements honoured with default example text" in {
    // Nothing here is registered in `llm4s-core` - this is the shape a provider
    // module supplies, and it is validated by the same code path as the built-ins.
    object CustomProvider extends ProviderDescriptor:
      val id: ProviderId                 = ProviderId("customcloud")
      val configSpec: ProviderConfigSpec = ProviderConfigSpec(requiresBaseUrl = true, requiresEndpoint = true)

      def buildConfig(providerName: String, section: NamedProviderConfig)(using
        org.llm4s.llmconnect.config.ContextWindowResolver
      ): org.llm4s.types.Result[org.llm4s.llmconnect.config.ProviderConfig] =
        Left(ConfigurationError("not needed for this test"))

      def buildClient(
        config: org.llm4s.llmconnect.config.ProviderConfig,
        options: org.llm4s.llmconnect.LlmClientOptions
      )(using org.llm4s.model.ModelRegistryService): org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
        Left(ConfigurationError("not needed for this test"))

    val message = errorFrom(validate("my-custom", CustomProvider, section("customcloud", "v1")))

    message should include("Provider 'my-custom' (provider = customcloud) is missing required fields")
    message should include("- baseUrl: set CUSTOMCLOUD_BASE_URL (e.g. https://api.example.com/)")
    message should include("- endpoint: the provider endpoint")
  }

  "validation" should "return the normalized section when all required fields are present" in {
    val result = validate(
      "my-openai",
      OpenAIProvider,
      section("openai", "gpt-4", baseUrl = Some("https://api.openai.com/v1"), apiKey = Some("sk-test-key"))
    )

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("openai")
    config.apiKey.map(_.asKey) shouldBe Some("sk-test-key")
    config.baseUrl.map(_.asUrl) shouldBe Some("https://api.openai.com/v1")
  }

  it should "trim whitespace and filter empty strings for all optional fields" in {
    val result = validate(
      "my-trim-test",
      OpenAIProvider,
      section(
        "openai",
        "gpt-4",
        baseUrl = Some("  https://api.example.com  "),
        apiKey = Some("  sk-test-key  "),
        organization = Some("  org-123  "),
        endpoint = Some("   "), // whitespace only
        apiVersion = Some("")   // empty string
      )
    )

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("openai")
    config.baseUrl.map(_.asUrl) shouldBe Some("https://api.example.com")
    config.apiKey.map(_.asKey) shouldBe Some("sk-test-key")
    config.organization shouldBe Some("org-123")
    config.endpoint shouldBe None   // should be filtered out because it's just whitespace
    config.apiVersion shouldBe None // should be filtered out because it's empty
  }

  it should "accept Azure when all required fields including endpoint are present" in {
    val result = validate(
      "my-azure",
      AzureProvider,
      section("azure", "gpt-4", apiKey = Some("azure-key"), endpoint = Some("my-deployment"))
    )

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("azure")
    config.apiKey.map(_.asKey) shouldBe Some("azure-key")
    config.endpoint shouldBe Some("my-deployment")
  }

  it should "reject a section whose provider is not the one being validated against" in {
    val message = errorFrom(validate("my-azure", AzureProvider, section("openai", "gpt-4", apiKey = Some("k"))))

    message should include("Configured provider 'my-azure' resolved to unexpected provider 'openai'")
  }
}
