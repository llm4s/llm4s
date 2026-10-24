package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor, ProviderRegistry }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/** `ProviderConfigSpec.keylessWhen` (#2274): a provider-specific setting that authenticates without an API key. */
class KeylessProviderConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  /** Needs an API key unless the section sets `authentication = "token"`, as Azure's `entra-id` does. */
  private object KeylessProvider extends ProviderDescriptor:
    val id: ProviderId = ProviderId("keylessfixture")
    val configSpec: ProviderConfigSpec =
      ProviderConfigSpec
        .apiKeyAndDefaultBaseUrl(FixtureChatProvider.DefaultBaseUrl, Seq("KEYLESSFIXTURE_API_KEY"))
        .withExtras(Seq(ProviderConfigKey.optional("authentication", "how to authenticate", Some("key"))))
        .withKeylessWhen("authentication", "token")
    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] =
      FixtureChatConfig.fromValues(section.model.asString, "unused", FixtureChatProvider.DefaultBaseUrl)
    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
      FixtureChatProvider.buildClient(config, options)

  /** Names a keyless key it does not declare: a descriptor bug. */
  private object UndeclaredKeyless extends ProviderDescriptor:
    val id: ProviderId = ProviderId("undeclaredkeyless")
    val configSpec: ProviderConfigSpec =
      ProviderConfigSpec(requiresApiKey = true, keylessWhen = Some("authentication" -> "token"))
    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] = KeylessProvider.buildConfig(providerName, section)
    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
      FixtureChatProvider.buildClient(config, options)

  private given ProviderRegistry = ProviderRegistry.of(KeylessProvider, UndeclaredKeyless, FixtureChatProvider)

  private val sharedKey = "llm4s.credentials.keylessfixture.apiKey = \"sk-shared\"\n"

  private def sections(hocon: String) = ProvidersConfigLoader.loadSections(ConfigSource.string(hocon))

  private def load(hocon: String): Result[NamedProviderConfig] =
    sections(hocon).flatMap(_.validated(ProviderName("main")))

  private def section(body: String, provider: String = "keylessfixture") =
    s"llm4s.providers.main { provider = $provider, model = m, $body }\n"

  "a keyless section" should {
    "load without an apiKey, the setting compared ignoring case and blanks" in {
      val config = load(section("""authentication = " Token """")).value
      config.apiKey shouldBe None
      config.extra("authentication") shouldBe Some("Token")
    }

    "not take the vendor's shared key" in {
      load(sharedKey + section("""authentication = "token"""")).value.apiKey shouldBe None
    }

    "be refused when it also sets an apiKey of its own" in {
      val error = load(section("""authentication = "token", apiKey = "sk-own"""")).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("sets both apiKey and authentication = \"token\"")
    }

    "be reported as WorkloadIdentity by apiKeySources, at the setting's path" in {
      sections(section("""authentication = "token"""")).value.apiKeySources shouldBe
        Map(ProviderName("main") -> ApiKeySource.WorkloadIdentity("llm4s.providers.main.authentication"))
    }
  }

  "a section of a keyless-capable provider" should {
    "still need a key with the default setting" in {
      val error = load(section("""authentication = "key"""")).left.value
      error.message should include("apiKey")
      load(sharedKey + section("")).value.apiKey.map(_.asKey) shouldBe Some("sk-shared")
    }
  }

  "a provider naming an undeclared keyless key" should {
    "fail validation as a descriptor bug" in {
      val error = load(section("""apiKey = "k"""", provider = "undeclaredkeyless")).left.value
      error.message should include("names authentication in keylessWhen but does not declare it")
    }
  }

  "ProviderConfigSpec" should {
    "keep keylessWhen through its setters and the eight-argument apply" in {
      val spec = ProviderConfigSpec(true, false, None, "e.g.", None, Seq.empty, Seq.empty, Seq.empty)
      spec.keylessWhen shouldBe None
      spec.withKeylessWhen("authentication", "token").keylessWhen shouldBe Some("authentication" -> "token")
      spec.withKeylessWhen(None).isKeyless(Map("authentication" -> "token")) shouldBe false
    }
  }
