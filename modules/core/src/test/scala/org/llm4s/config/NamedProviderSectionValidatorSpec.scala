package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.provider.DeepSeekProvider
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
 *
 * DeepSeek stands in for "a provider with an API key and a default base URL". The Azure
 * cases, which are about Azure's own endpoint requirement, moved to `llm4s-openai`'s
 * `AzureSectionValidationSpec` with the provider (#1132).
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

  "DeepSeek validation" should "mention missing DeepSeek fields by name" in {
    val message = errorFrom(validate("my-deepseek", DeepSeekProvider, section("deepseek", "deepseek-chat")))

    message should include("Provider 'my-deepseek' (provider = deepseek) is missing required fields")
    message should include(
      "- apiKey: set it in llm4s.conf under providers.my-deepseek.apiKey (optionally from an env var, e.g. apiKey = ${?DEEPSEEK_API_KEY})"
    )
  }

  it should "not demand a baseUrl, because the descriptor supplies a default" in {
    val result =
      validate("my-deepseek", DeepSeekProvider, section("deepseek", "deepseek-chat", apiKey = Some("sk-test")))

    result.map(_.baseUrl) shouldBe Right(None)
    DeepSeekProvider.configSpec.defaultBaseUrl shouldBe Some(DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL)
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
      "my-deepseek",
      DeepSeekProvider,
      section("deepseek", "deepseek-chat", baseUrl = Some("https://api.deepseek.com"), apiKey = Some("sk-test-key"))
    )

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("deepseek")
    config.apiKey.map(_.asKey) shouldBe Some("sk-test-key")
    config.baseUrl.map(_.asUrl) shouldBe Some("https://api.deepseek.com")
  }

  it should "trim whitespace and filter empty strings for all optional fields" in {
    val result = validate(
      "my-trim-test",
      DeepSeekProvider,
      section(
        "deepseek",
        "deepseek-chat",
        baseUrl = Some("  https://api.example.com  "),
        apiKey = Some("  sk-test-key  "),
        organization = Some("  org-123  "),
        endpoint = Some("   "), // whitespace only
        apiVersion = Some("")   // empty string
      )
    )

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("deepseek")
    config.baseUrl.map(_.asUrl) shouldBe Some("https://api.example.com")
    config.apiKey.map(_.asKey) shouldBe Some("sk-test-key")
    config.organization shouldBe Some("org-123")
    config.endpoint shouldBe None   // should be filtered out because it's just whitespace
    config.apiVersion shouldBe None // should be filtered out because it's empty
  }

  it should "reject a section whose provider is not the one being validated against" in {
    val message =
      errorFrom(validate("my-deepseek", DeepSeekProvider, section("openai", "gpt-4", apiKey = Some("k"))))

    message should include("Configured provider 'my-deepseek' resolved to unexpected provider 'openai'")
  }
}
