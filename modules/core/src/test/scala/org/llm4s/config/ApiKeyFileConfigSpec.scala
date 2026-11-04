package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor, ProviderRegistry }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

/** A section's `apiKeyFile` (#2313): read at load, and handed to the client to re-read when it changes. */
class ApiKeyFileConfigSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  /** A config that cannot take an `ApiKeyProvider` (the trait's default `withApiKeyProvider`), holding its key. */
  final private case class StaticKeyConfig(key: String, providerId: ProviderId) extends ProviderConfig:
    val model: String                            = "m"
    val contextWindow: Int                       = 8192
    val reserveCompletion: Int                   = 1024
    val endpointUrl: Option[String]              = None
    def withModel(model: String): ProviderConfig = this

  /** A provider whose key is read when its config is built, not per request. */
  private object NoRotation extends ProviderDescriptor:
    val id: ProviderId                 = ProviderId("norotation")
    val configSpec: ProviderConfigSpec = ProviderConfigSpec.apiKeyAndDefaultBaseUrl(FixtureChatProvider.DefaultBaseUrl)
    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] =
      ProviderDescriptor.requireApiKey(providerName, section).map(key => StaticKeyConfig(key.reveal, id))
    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
      FixtureChatProvider.buildClient(config, options)

  /** A provider that takes no API key, as Ollama and Bedrock do. */
  private object Keyless extends ProviderDescriptor:
    val id: ProviderId                 = ProviderId("keylessprovider")
    val configSpec: ProviderConfigSpec = ProviderConfigSpec(defaultBaseUrl = Some(FixtureChatProvider.DefaultBaseUrl))
    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] = Right(StaticKeyConfig("", id))
    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
      FixtureChatProvider.buildClient(config, options)

  private given ProviderRegistry = ProviderRegistry.of(FixtureChatProvider, NoRotation, Keyless)

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("api-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content, StandardCharsets.UTF_8)

  /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
  private def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

  private def hocon(body: String, provider: String = "fixturechat") =
    s"llm4s.providers.main { provider = $provider, model = m, $body }\n"

  private def load(hocon: String): Result[ProviderConfig] =
    Llm4sConfig.provider(ConfigSource.string(hocon), "main")

  private def validated(hocon: String): Result[NamedProviderConfig] =
    ProvidersConfigLoader.loadSections(ConfigSource.string(hocon)).flatMap(_.validated(ProviderName("main")))

  "a section with apiKeyFile" should {
    "load the key the file holds, and give the config a provider that re-reads it" in {
      val file   = keyFile("sk-file-key-000001\n")
      val config = load(hocon(s"apiKeyFile = ${quoted(file)}")).value.asInstanceOf[FixtureChatConfig]
      config.apiKey shouldBe "sk-file-key-000001"
      config.apiKeyProvider.value.current().value.asKey shouldBe "sk-file-key-000001"
      Files.writeString(file, "sk-file-key-rotated-02")
      config.apiKeyProvider.value.current().value.asKey shouldBe "sk-file-key-rotated-02"
    }

    "keep the absolute path on the validated section" in {
      val file = keyFile("sk-file-key-000001")
      validated(hocon(s"apiKeyFile = ${quoted(file)}")).value.apiKeyFile shouldBe Some(file.toAbsolutePath.normalize)
    }

    "use the file, not the vendor's shared key" in {
      val file   = keyFile("sk-own-file-key-0001")
      val shared = "llm4s.credentials.fixturechat.apiKey = \"sk-shared-key-0001\"\n"
      val config = load(shared + hocon(s"apiKeyFile = ${quoted(file)}")).value.asInstanceOf[FixtureChatConfig]
      config.apiKey shouldBe "sk-own-file-key-0001"
    }

    "fail the load with a ConfigurationError naming the path when the file is missing" in {
      val missing = Files.createTempDirectory("api-key-dir").resolve("absent")
      val error   = load(hocon(s"apiKeyFile = ${quoted(missing)}")).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("llm4s.providers.main.apiKeyFile")
      error.message should include(missing.toString)
    }

    "fail the load when the file is empty" in {
      val error = load(hocon(s"apiKeyFile = ${quoted(keyFile("  \n"))}")).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("is empty")
    }

    "be refused together with apiKey" in {
      val file  = keyFile("sk-file-key-000001")
      val error = load(hocon(s"""apiKeyFile = ${quoted(file)}, apiKey = "sk-literal-0001"""")).left.value
      error.message should include("sets apiKeyFile and apiKey")
    }

    "give a provider that cannot rotate the key the file holds when the section is loaded" in {
      val file   = keyFile("sk-file-key-000001")
      val config = load(hocon(s"apiKeyFile = ${quoted(file)}", provider = "norotation")).value
      config shouldBe StaticKeyConfig("sk-file-key-000001", ProviderId("norotation"))
      config.apiKeyProvider shouldBe None
    }

    "be refused for a provider that takes no API key" in {
      val file  = keyFile("sk-file-key-000001")
      val error = load(hocon(s"apiKeyFile = ${quoted(file)}", provider = "keylessprovider")).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("'keylessprovider' takes no API key")
    }

    "treat a blank apiKeyFile as unset" in {
      val config = load(hocon("""apiKeyFile = " ", apiKey = "sk-literal-0001"""")).value
      config.apiKeyProvider shouldBe None
    }

    "be reported as the section's own key" in {
      val file = keyFile("sk-file-key-000001")
      Llm4sConfig.apiKeySourcesFrom(ConfigSource.string(hocon(s"apiKeyFile = ${quoted(file)}"))).value shouldBe
        Map(ProviderName("main") -> ApiKeySource.Section("llm4s.providers.main.apiKeyFile"))
    }

    "name the path, not the key, in the validated section's toString" in {
      val file = keyFile("sk-file-key-000001")
      val text = validated(hocon(s"apiKeyFile = ${quoted(file)}")).value.toString
      text should include(file.toAbsolutePath.normalize.toString)
      (text should not).include("sk-file-key")
    }
  }
