package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver }
import org.llm4s.llmconnect.provider.AzureProvider
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Azure's requirements through the spec-driven section validator: an API key and a
 * deployment endpoint, and the messages users see when either is missing.
 *
 * `endpoint` and `apiVersion` are Azure's own provider-specific keys since #1133, not fields of
 * `NamedProviderConfig`, so a missing endpoint is reported as a missing required key.
 *
 * Moved from core's `NamedProviderSectionValidatorSpec` with the provider (#1132); the
 * provider-neutral cases stayed in core, using the test fixture `FixtureChatProvider`.
 */
class AzureSectionValidationSpec extends AnyFlatSpec with Matchers {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

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
    endpoint: Option[String] = None,
    extras: Map[String, String] = Map.empty
  ): RawNamedProviderSection =
    RawNamedProviderSection(
      Some("azure"),
      Some(model),
      None,
      apiKey,
      extras = extras ++ endpoint.map(AzureProvider.EndpointKey -> _)
    )

  "Azure validation" should "mention missing Azure fields by name" in {
    val message = validate("my-azure", section("gpt-4")).left.toOption
      .getOrElse(fail("Expected Left"))
      .asInstanceOf[ConfigurationError]
      .message

    message should include("Provider 'my-azure' (provider = azure) is missing required fields")
    message should include(
      "- apiKey: set AZURE_OPENAI_API_KEY, or set apiKey under llm4s.providers.my-azure in application.conf"
    )
    message should include("- endpoint: the model endpoint/deployment name in your Azure OpenAI resource")
  }

  it should "name the endpoint key, where to set it and the variable that can bind it" in {
    val message = validate("my-azure", section("gpt-4", apiKey = Some("azure-key"))).left.toOption
      .getOrElse(fail("Expected Left"))
      .message

    message should include(
      "- endpoint: the model endpoint/deployment name in your Azure OpenAI resource " +
        "(set it in application.conf under llm4s.providers.my-azure.endpoint; to read it from AZURE_API_BASE, " +
        "add endpoint = ${?AZURE_API_BASE} to the section)"
    )
    // The API key is present, so the endpoint is the only problem.
    (message should not).include("apiKey")
  }

  it should "treat a blank endpoint as missing" in {
    val result = validate("my-azure", section("gpt-4", apiKey = Some("azure-key"), endpoint = Some("   ")))

    result.left.toOption.getOrElse(fail(s"Expected Left, got $result")).message should include("- endpoint:")
  }

  it should "accept Azure when all required fields including endpoint are present" in {
    val result = validate("my-azure", section("gpt-4", apiKey = Some("azure-key"), endpoint = Some("my-deployment")))

    val config = result.getOrElse(fail(s"Expected Right, got $result"))
    config.provider shouldBe ProviderId("azure")
    config.apiKey.map(_.asKey) shouldBe Some("azure-key")
    config.extra(AzureProvider.EndpointKey) shouldBe Some("my-deployment")
    config.extra(AzureProvider.ApiVersionKey) shouldBe Some(AzureConfig.DEFAULT_API_VERSION)
  }

  it should "keep a configured apiVersion" in {
    val result = validate(
      "my-azure",
      section("gpt-4", apiKey = Some("k"), endpoint = Some("e"), extras = Map("apiVersion" -> "2024-02-01"))
    )

    result.map(_.extra(AzureProvider.ApiVersionKey)) shouldBe Right(Some("2024-02-01"))
  }

  it should "fail buildConfig for a section built in code without an endpoint, naming the key" in {
    val section = NamedProviderConfig(
      provider = AzureProvider.id,
      model = ModelName("gpt-4"),
      baseUrl = None,
      apiKey = Some(ApiKey("k"))
    )

    AzureProvider.buildConfig("in-code", section).left.toOption.getOrElse(fail("Expected Left")).message shouldBe
      "Configured provider 'in-code' is missing endpoint (llm4s.providers.<name>.endpoint)"
  }
}
