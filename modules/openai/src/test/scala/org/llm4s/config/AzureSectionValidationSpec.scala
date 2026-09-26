package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.provider.AzureProvider
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Azure's requirements through the spec-driven section validator: an API key and a
 * deployment endpoint, and the messages users see when either is missing.
 *
 * Moved from core's `NamedProviderSectionValidatorSpec` with the provider (#1132); the
 * provider-neutral cases stayed in core, using the test fixture `FixtureChatProvider`.
 */
class AzureSectionValidationSpec extends AnyFlatSpec with Matchers {

  private def validate(
    providerName: String,
    section: RawNamedProviderSection
  ): org.llm4s.types.Result[NamedProviderConfig] =
    val name = ProviderName(providerName)
    NamedProviderConfigNormalizer
      .normalize(name, section)
      .flatMap(NamedProviderSectionValidator.validate(name, AzureProvider, _))

  private def section(
    model: String,
    apiKey: Option[String] = None,
    endpoint: Option[String] = None
  ): RawNamedProviderSection =
    RawNamedProviderSection(Some("azure"), Some(model), None, apiKey, None, endpoint, None)

  "Azure validation" should "mention missing Azure fields by name" in {
    val message = validate("my-azure", section("gpt-4")).left.toOption
      .getOrElse(fail("Expected Left"))
      .asInstanceOf[ConfigurationError]
      .message

    message should include("Provider 'my-azure' (provider = azure) is missing required fields")
    message should include(
      "- apiKey: set it in llm4s.conf under providers.my-azure.apiKey (optionally from an env var, e.g. apiKey = ${?AZURE_API_KEY})"
    )
    message should include("- endpoint: the model endpoint/deployment name in your Azure OpenAI resource")
  }

  it should "accept Azure when all required fields including endpoint are present" in {
    val result = validate("my-azure", section("gpt-4", apiKey = Some("azure-key"), endpoint = Some("my-deployment")))

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("azure")
    config.apiKey.map(_.asKey) shouldBe Some("azure-key")
    config.endpoint shouldBe Some("my-deployment")
  }
}
