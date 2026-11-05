package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.security.Secret
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.types.ProviderModelTypes.{ ApiKey, ProviderId }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

/**
 * `apiKeyFile` beyond a chat section's own (#2478): in `llm4s.credentials.<id>`, which every client of the vendor
 * falls back to, and in an embeddings block. The reranker's is in `llm4s-rag`'s `RerankerConfigLoaderSpec`.
 */
class SharedApiKeyFileSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  private given ProviderRegistry = ProviderRegistry.default

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("shared-api-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content, StandardCharsets.UTF_8)

  private def missingFile: Path = Files.createTempDirectory("shared-api-key-dir").resolve("absent")

  /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
  private def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

  private def credentialsFile(id: String, file: Path): String =
    s"llm4s.credentials.$id.apiKeyFile = ${quoted(file)}\n"

  private val chatSection = "llm4s.providers.main { provider = fixturechat, model = m }\n"

  private def loadChat(hocon: String): Result[ProviderConfig] =
    Llm4sConfig.provider(ConfigSource.string(hocon), "main")

  private val selectEmbedding = "llm4s.embeddings.model = \"fixtureembedding/fixture-embed-small\"\n"

  private def loadEmbeddings(hocon: String): Result[EmbeddingProviderConfig] =
    EmbeddingsConfigLoader.loadProvider(ConfigSource.string(selectEmbedding + hocon)).map(_._2)

  "llm4s.credentials.<id>.apiKeyFile" should {

    "give a chat section without a key of its own the key the file holds, re-read when it changes" in {
      val file   = keyFile("sk-shared-file-0001\n")
      val config = loadChat(credentialsFile("fixturechat", file) + chatSection).value.asInstanceOf[FixtureChatConfig]
      config.apiKey shouldBe "sk-shared-file-0001"
      Files.writeString(file, "sk-shared-file-rotated")
      config.apiKeyProvider.value.current().value.asKey shouldBe "sk-shared-file-rotated"
    }

    "keep the file on the validated section, so the loader hands it to the client" in {
      val file = keyFile("sk-shared-file-0001")
      val validated: Result[NamedProviderConfig] =
        ProvidersConfigLoader
          .loadSections(ConfigSource.string(credentialsFile("fixturechat", file) + chatSection))
          .flatMap(_.validated(ProviderName("main")))
      validated.value.apiKeyFile shouldBe Some(file.toAbsolutePath.normalize)
    }

    "not be read by a section that sets its own apiKey" in {
      val hocon =
        credentialsFile("fixturechat", missingFile) + chatSection.replace("model = m", "model = m, apiKey = own")
      val config = loadChat(hocon).value.asInstanceOf[FixtureChatConfig]
      config.apiKey shouldBe "own"
      config.apiKeyProvider shouldBe None
    }

    "fail a section that falls back to it, naming the setting and the path, when the file is missing" in {
      val missing = missingFile
      val error   = loadChat(credentialsFile("fixturechat", missing) + chatSection).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("llm4s.credentials.fixturechat.apiKeyFile")
      error.message should include(missing.toString)
    }

    "be refused beside apiKey in the same entry" in {
      val hocon =
        credentialsFile("fixturechat", keyFile("sk-shared-file-0001")) +
          "llm4s.credentials.fixturechat.apiKey = \"sk-literal-0001\"\n" + chatSection
      val error = loadChat(hocon).left.value
      error.message should include("llm4s.credentials.fixturechat sets both apiKey and apiKeyFile")
      (error.message should not).include("sk-literal-0001")
    }

    "be refused when it is not a valid path" in {
      val error = loadChat("llm4s.credentials.fixturechat.apiKeyFile = \"bad\\u0000path\"\n" + chatSection).left.value
      error.message should include("llm4s.credentials.fixturechat.apiKeyFile is not a valid path")
    }

    "be read by a lookup, which names the file it came from" in {
      val file     = keyFile("sk-shared-file-0001")
      val resolved = SharedCredentials.read(ConfigSource.string(credentialsFile("keyed", file)))
      val key      = resolved.apiKey(ProviderId("keyed")).value.value
      key.value.reveal shouldBe "sk-shared-file-0001"
      key.file shouldBe Some(file.toAbsolutePath.normalize)
      key.path should include("llm4s.credentials.keyed.apiKeyFile")
      resolved.toString shouldBe "SharedCredentials(keyed -> ***)"
    }

    "treat a blank apiKeyFile as unset" in {
      SharedCredentials
        .read(ConfigSource.string("llm4s.credentials.keyed.apiKeyFile = \" \""))
        .apiKey(ProviderId("keyed"))
        .value shouldBe None
    }
  }

  "an embeddings block" should {

    "read its own apiKeyFile at load, and give the config a provider that re-reads it" in {
      val file   = keyFile("sk-embed-file-0001\n")
      val config = loadEmbeddings(s"llm4s.embeddings.fixtureembedding.apiKeyFile = ${quoted(file)}\n").value
      config.apiKey.reveal shouldBe "sk-embed-file-0001"
      config.currentApiKey().value.reveal shouldBe "sk-embed-file-0001"
      Files.writeString(file, "sk-embed-file-rotated")
      config.currentApiKey().value.reveal shouldBe "sk-embed-file-rotated"
      config.toString should include(file.toAbsolutePath.normalize.toString)
      (config.toString should not).include("sk-embed-file")
    }

    "use its own apiKeyFile over the vendor's shared key" in {
      val file = keyFile("sk-embed-file-0001")
      val hocon =
        "llm4s.credentials.fixtureembedding.apiKey = \"sk-shared-0001\"\n" +
          s"llm4s.embeddings.fixtureembedding.apiKeyFile = ${quoted(file)}\n"
      loadEmbeddings(hocon).value.apiKey.reveal shouldBe "sk-embed-file-0001"
    }

    "fall back to the vendor's shared apiKeyFile" in {
      val file   = keyFile("sk-shared-file-0001")
      val config = loadEmbeddings(credentialsFile("fixtureembedding", file)).value
      config.apiKey.reveal shouldBe "sk-shared-file-0001"
      Files.writeString(file, "sk-shared-file-rotated")
      config.currentApiKey().value.reveal shouldBe "sk-shared-file-rotated"
    }

    "be refused when it sets both apiKey and apiKeyFile" in {
      val hocon =
        s"llm4s.embeddings.fixtureembedding { apiKey = sk-literal-0001, apiKeyFile = ${quoted(keyFile("k-0001"))} }\n"
      val error = loadEmbeddings(hocon).left.value
      error.message should include("llm4s.embeddings.fixtureembedding sets both apiKey and apiKeyFile")
    }

    "fail the load, naming the setting and the path, when its file is missing" in {
      val missing = missingFile
      val error   = loadEmbeddings(s"llm4s.embeddings.fixtureembedding.apiKeyFile = ${quoted(missing)}\n").left.value
      error.message should include("llm4s.embeddings.fixtureembedding.apiKeyFile")
      error.message should include(missing.toString)
    }

    "be refused when its apiKeyFile is not a string" in {
      val error = loadEmbeddings("llm4s.embeddings.fixtureembedding.apiKeyFile { a = 1 }\n").left.value
      error.message should include("llm4s.embeddings.fixtureembedding.apiKeyFile")
    }

    "send the literal key, with no provider, when no file is set" in {
      val config = loadEmbeddings("llm4s.embeddings.fixtureembedding.apiKey = \"sk-literal-0001\"\n").value
      config.apiKeyProvider shouldBe None
      config.currentApiKey().value.reveal shouldBe "sk-literal-0001"
    }
  }

  "EmbeddingProviderConfig.currentApiKey" should {

    "report a provider's failure as a Left" in {
      val failing = new ApiKeyProvider:
        def current(): Result[ApiKey] = Left(ConfigurationError("API key file /x is empty"))
      val config = EmbeddingProviderConfig("https://e.invalid", "m", Secret("k")).withApiKeyProvider(failing)
      config.currentApiKey().left.value.message should include("is empty")
      config.withApiKeyProvider(None).currentApiKey().value.reveal shouldBe "k"
    }
  }
