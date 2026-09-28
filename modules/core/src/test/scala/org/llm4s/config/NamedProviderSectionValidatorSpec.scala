package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.testutil.FixtureChatProvider
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
 * The test fixture `FixtureChatProvider` stands in for "a provider with an API key and a
 * default base URL", so these cases do not move each time a real provider leaves core. The
 * Azure cases, which are about Azure's own endpoint requirement, moved to `llm4s-openai`'s
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

  "API-key validation" should "mention the missing fields by name" in {
    val message =
      errorFrom(validate("my-fixture", FixtureChatProvider, section("fixturechat", "fixture-model")))

    message should include("Provider 'my-fixture' (provider = fixturechat) is missing required fields")
    // The fixture declares no variable, so the vendor's shared path is named in its place - never
    // a variable guessed from the provider id, which nothing would bind.
    message should include(
      "- apiKey: set llm4s.credentials.fixturechat.apiKey, or set apiKey under llm4s.providers.my-fixture in application.conf"
    )
    (message should not).include("FIXTURECHAT_API_KEY")
  }

  it should "name a file llm4s loads, and the section's full path" in {
    // Typesafe Config loads application.conf (and reference.conf); nothing loads llm4s.conf,
    // and the section lives under llm4s.providers, not a top-level providers block.
    val message =
      errorFrom(validate("my-fixture", FixtureChatProvider, section("fixturechat", "fixture-model")))

    (message should not).include("llm4s.conf")
    (message should not).include(" providers.my-fixture")
    message should include("set apiKey under llm4s.providers.my-fixture in application.conf")
  }

  it should "not demand a baseUrl, because the descriptor supplies a default" in {
    val result =
      validate("my-fixture", FixtureChatProvider, section("fixturechat", "fixture-model", apiKey = Some("sk-test")))

    result.map(_.baseUrl) shouldBe Right(None)
    FixtureChatProvider.configSpec.defaultBaseUrl shouldBe Some(FixtureChatProvider.DefaultBaseUrl)
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
    message should include(
      "- baseUrl: set it in application.conf under llm4s.providers.my-custom.baseUrl (e.g. https://api.example.com/)"
    )
    // Nothing reads CUSTOMCLOUD_BASE_URL, so it is not suggested (#1215).
    (message should not).include("CUSTOMCLOUD_BASE_URL")
    message should include("- endpoint: the provider endpoint")
  }

  "validation" should "return the normalized section when all required fields are present" in {
    val result = validate(
      "my-fixture",
      FixtureChatProvider,
      section(
        "fixturechat",
        "fixture-model",
        baseUrl = Some("https://fixturechat.invalid/v2"),
        apiKey = Some("sk-test-key")
      )
    )

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("fixturechat")
    config.apiKey.map(_.asKey) shouldBe Some("sk-test-key")
    config.baseUrl.map(_.asUrl) shouldBe Some("https://fixturechat.invalid/v2")
  }

  it should "trim whitespace and filter empty strings for all optional fields" in {
    val result = validate(
      "my-trim-test",
      FixtureChatProvider,
      section(
        "fixturechat",
        "fixture-model",
        baseUrl = Some("  https://api.example.com  "),
        apiKey = Some("  sk-test-key  "),
        organization = Some("  org-123  "),
        endpoint = Some("   "), // whitespace only
        apiVersion = Some("")   // empty string
      )
    )

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("fixturechat")
    config.baseUrl.map(_.asUrl) shouldBe Some("https://api.example.com")
    config.apiKey.map(_.asKey) shouldBe Some("sk-test-key")
    config.organization shouldBe Some("org-123")
    config.endpoint shouldBe None   // should be filtered out because it's just whitespace
    config.apiVersion shouldBe None // should be filtered out because it's empty
  }

  it should "reject a section whose provider is not the one being validated against" in {
    val message =
      errorFrom(validate("my-fixture", FixtureChatProvider, section("openai", "gpt-4", apiKey = Some("k"))))

    message should include("Configured provider 'my-fixture' resolved to unexpected provider 'openai'")
  }
}
