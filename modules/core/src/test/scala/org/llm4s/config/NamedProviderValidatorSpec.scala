package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.config.ProvidersConfigModel.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NamedProviderValidatorSpec extends AnyFlatSpec with Matchers {

  "AzureValidator" should "mention missing Azure fields by name" in {
    val section = RawNamedProviderSection(
      provider = Some("azure"),
      model = Some("gpt-4"),
      baseUrl = None,
      apiKey = None,
      organization = None,
      endpoint = None,
      apiVersion = None
    )
    val result = NamedProviderValidators.Azure.validate(ProviderName("my-azure"), section)

    result.isLeft shouldBe true
    val err = result.left.toOption.get.asInstanceOf[ConfigurationError]

    err.message should include("Azure OpenAI provider 'my-azure' is missing required fields")
    err.message should include(
      "- apiKey: set AZURE_API_KEY or provide it in llm4s.conf under providers.my-azure.apiKey"
    )
    err.message should include("- endpoint: the model endpoint/deployment name in your Azure OpenAI resource")
  }

  "OpenAIValidator" should "mention missing OpenAI fields by name" in {
    val section = RawNamedProviderSection(
      provider = Some("openai"),
      model = Some("gpt-4"),
      baseUrl = None,
      apiKey = None,
      organization = None,
      endpoint = None,
      apiVersion = None
    )
    val result = NamedProviderValidators.OpenAI.validate(ProviderName("my-openai"), section)

    result.isLeft shouldBe true
    val err = result.left.toOption.get.asInstanceOf[ConfigurationError]

    err.message should include("OpenAI provider 'my-openai' is missing required fields")
    err.message should include(
      "- apiKey: set OPENAI_API_KEY or provide it in llm4s.conf under providers.my-openai.apiKey"
    )
  }

  "OllamaValidator" should "mention missing Ollama fields by name" in {
    val section = RawNamedProviderSection(
      provider = Some("ollama"),
      model = Some("llama3"),
      baseUrl = None,
      apiKey = None,
      organization = None,
      endpoint = None,
      apiVersion = None
    )
    val result = NamedProviderValidators.Ollama.validate(ProviderName("my-ollama"), section)

    result.isLeft shouldBe true
    val err = result.left.toOption.get.asInstanceOf[ConfigurationError]

    err.message should include("Ollama provider 'my-ollama' is missing required fields")
    err.message should include("- baseUrl: set OLLAMA_BASE_URL (e.g. http://localhost:11434)")
  }

  "validateNamedProviderConfig" should "mention missing generic fields using default examples" in {
    val section = RawNamedProviderSection(
      provider = Some("openai"),
      model = Some("test-model"),
      baseUrl = None,
      apiKey = None,
      organization = None,
      endpoint = None,
      apiVersion = None
    )

    object GenericTestValidator extends NamedProviderValidator {
      def validate(
        providerName: ProviderName,
        section: RawNamedProviderSection
      ): org.llm4s.types.Result[NamedProviderConfig] =
        NamedProviderValidators.validateNamedProviderConfig(
          providerName = providerName,
          providerKind = ProviderKind.OpenAI, // non-Ollama to hit the `case _` branch
          section = section,
          requireBaseUrl = true
        )
    }

    val result = GenericTestValidator.validate(ProviderName("my-generic"), section)
    result.isLeft shouldBe true
    val err = result.left.toOption.get.asInstanceOf[ConfigurationError]

    // "OPENAI_BASE_URL" is generated because we passed ProviderKind.OpenAI
    err.message should include("- baseUrl: set OPENAI_BASE_URL (e.g. https://api.example.com/)")
  }

  "validateNamedProviderConfig" should "return Right(normalized) when all required fields are present" in {
    val section = RawNamedProviderSection(
      provider = Some("openai"),
      model = Some("gpt-4"),
      baseUrl = Some("https://api.openai.com/v1"),
      apiKey = Some("sk-test-key"),
      organization = None,
      endpoint = None,
      apiVersion = None
    )

    val result = NamedProviderValidators.OpenAI.validate(ProviderName("my-openai"), section)

    result.isRight shouldBe true
    val config = result.getOrElse(fail("Expected Right"))
    config.provider shouldBe ProviderKind.OpenAI
    config.apiKey shouldBe Some("sk-test-key")
    config.baseUrl shouldBe Some("https://api.openai.com/v1")
  }

  it should "trim whitespace and filter empty strings for all optional fields" in {
    val section = RawNamedProviderSection(
      provider = Some("openai"),
      model = Some("gpt-4"),
      baseUrl = Some("  https://api.example.com  "),
      apiKey = Some("  sk-test-key  "),
      organization = Some("  org-123  "),
      endpoint = Some("   "), // whitespace only
      apiVersion = Some("")   // empty string
    )

    // Azure requires endpoint and apiKey, so we need a provider that doesn't strictly require endpoint for this specific test
    // Let's use OpenAI and pass requireApiKey = false or provide it.
    // OpenAIValidator requires apiKey. So we provide apiKey.
    val result = NamedProviderValidators.OpenAI.validate(ProviderName("my-trim-test"), section)

    result.isRight shouldBe true
    val config = result.getOrElse(fail("Expected Right"))
    config.provider shouldBe ProviderKind.OpenAI
    config.baseUrl shouldBe Some("https://api.example.com")
    config.apiKey shouldBe Some("sk-test-key")
    config.organization shouldBe Some("org-123")
    config.endpoint shouldBe None   // should be filtered out because it's just whitespace
    config.apiVersion shouldBe None // should be filtered out because it's empty
  }

  it should "return Right(normalized) for Azure when all required fields including endpoint are present" in {
    val section = RawNamedProviderSection(
      provider = Some("azure"),
      model = Some("gpt-4"),
      baseUrl = None,
      apiKey = Some("azure-key"),
      organization = None,
      endpoint = Some("my-deployment"),
      apiVersion = None
    )

    val result = NamedProviderValidators.Azure.validate(ProviderName("my-azure"), section)

    result.isRight shouldBe true
    val config = result.getOrElse(fail("Expected Right"))
    config.provider shouldBe ProviderKind.Azure
    config.apiKey shouldBe Some("azure-key")
    config.endpoint shouldBe Some("my-deployment")
  }
}
