package org.llm4s.config

import org.llm4s.security.Secret
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.reranker.{ CohereReranker, RerankProviderConfig }
import org.llm4s.testkit.CredentialsRoundTrip
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

/**
 * The reranker's config, and its share of Cohere's key.
 *
 * Every `reference.conf` on `llm4s-rag`'s test classpath is loaded - its own, which binds
 * `RERANK_PROVIDER` and `COHERE_API_KEY`, and `llm4s-openai-compatible`'s, which binds the Cohere
 * chat provider's key to the same `llm4s.credentials.cohere.apiKey` - with an injected
 * environment and the real one switched off.
 */
class RerankerConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  private val cohereSelected = Map("RERANK_PROVIDER" -> "cohere")

  "RerankerConfigLoader" should {

    "select no reranker when RERANK_PROVIDER is unset or none" in {
      RerankerConfigLoader.load(ReferenceConfig.withEnv("", Map.empty)).value shouldBe None
      RerankerConfigLoader.load(ReferenceConfig.withEnv("", Map("RERANK_PROVIDER" -> "none"))).value shouldBe None
    }

    "take the Cohere key from COHERE_API_KEY, through llm4s.credentials.cohere.apiKey" in {
      RerankerConfigLoader
        .load(ReferenceConfig.withEnv("", cohereSelected + ("COHERE_API_KEY" -> "co-shared")))
        .value shouldBe Some(
        RerankProviderConfig(CohereReranker.DEFAULT_BASE_URL, Secret("co-shared"), CohereReranker.DEFAULT_MODEL)
      )
    }

    "prefer a key set under llm4s.rerank.cohere, the reranker's own" in {
      val hocon = """llm4s.rerank.cohere.apiKey = "co-reranker-account""""
      RerankerConfigLoader
        .load(ReferenceConfig.withEnv(hocon, cohereSelected + ("COHERE_API_KEY" -> "co-shared")))
        .value
        .map(_.apiKey.reveal) shouldBe Some("co-reranker-account")
    }

    "read the model and base URL variables" in {
      val env = cohereSelected ++ Map(
        "COHERE_API_KEY"         -> "co-shared",
        "COHERE_RERANK_MODEL"    -> "rerank-v3.5",
        "COHERE_RERANK_BASE_URL" -> "https://cohere.example"
      )
      RerankerConfigLoader.load(ReferenceConfig.withEnv("", env)).value shouldBe Some(
        RerankProviderConfig("https://cohere.example", Secret("co-shared"), "rerank-v3.5")
      )
    }

    "fail naming COHERE_API_KEY and the reranker's block when no key is set" in {
      RerankerConfigLoader.load(ReferenceConfig.withEnv("", cohereSelected)).left.value.message shouldBe
        "Missing Cohere reranker apiKey: set COHERE_API_KEY, or set apiKey under llm4s.rerank.cohere in application.conf"
    }

    "refuse llm, which needs a client config cannot supply, and an unknown provider" in {
      RerankerConfigLoader
        .load(ReferenceConfig.withEnv("", Map("RERANK_PROVIDER" -> "llm")))
        .left
        .value
        .message should include("RerankerFactory.llm")
      RerankerConfigLoader
        .load(ReferenceConfig.withEnv("", Map("RERANK_PROVIDER" -> "acme")))
        .left
        .value
        .message should include("Unknown llm4s.rerank.provider 'acme'")
    }
  }

  "one COHERE_API_KEY" should {

    given ProviderRegistry = ProviderRegistry.default

    "serve both the Cohere reranker and a Cohere chat section" in {
      val env = cohereSelected + ("COHERE_API_KEY" -> "co-shared")

      RerankerConfigLoader.load(ReferenceConfig.withEnv("", env)).value.map(_.apiKey.reveal) shouldBe Some("co-shared")
      CredentialsRoundTrip.chatSectionKey("cohere", env) shouldBe Right(Some("co-shared"))
    }
  }

  "apiKeyFile (#2478)" should {

    def keyFile(content: String): Path = {
      val file = Files.createTempFile("rerank-api-key", ".txt")
      file.toFile.deleteOnExit()
      Files.writeString(file, content, StandardCharsets.UTF_8)
    }

    /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
    def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

    "read the reranker's own file at load, over COHERE_API_KEY, and re-read it when it changes" in {
      val file  = keyFile("co-file-0001\n")
      val hocon = s"llm4s.rerank.cohere.apiKeyFile = ${quoted(file)}"
      val config =
        RerankerConfigLoader
          .load(ReferenceConfig.withEnv(hocon, cohereSelected + ("COHERE_API_KEY" -> "co-env")))
          .value
          .value
      config.apiKey.reveal shouldBe "co-file-0001"
      Files.writeString(file, "co-file-rotated")
      config.currentApiKey().value.reveal shouldBe "co-file-rotated"
    }

    "fall back to Cohere's shared llm4s.credentials.cohere.apiKeyFile" in {
      val file   = keyFile("co-shared-file-0001")
      val hocon  = s"llm4s.credentials.cohere.apiKeyFile = ${quoted(file)}"
      val config = RerankerConfigLoader.load(ReferenceConfig.withEnv(hocon, cohereSelected)).value.value
      config.apiKey.reveal shouldBe "co-shared-file-0001"
      config.apiKeyProvider shouldBe defined
    }

    "be refused beside the reranker's own apiKey" in {
      val hocon = s"""llm4s.rerank.cohere { apiKey = "co-literal", apiKeyFile = ${quoted(keyFile("co-file"))} }"""
      RerankerConfigLoader.load(ReferenceConfig.withEnv(hocon, cohereSelected)).left.value.message should include(
        "llm4s.rerank.cohere sets both apiKey and apiKeyFile"
      )
    }

    "fail naming the setting and the path when the file is missing" in {
      val missing = Files.createTempDirectory("rerank-api-key-dir").resolve("absent")
      val error = RerankerConfigLoader
        .load(ReferenceConfig.withEnv(s"llm4s.rerank.cohere.apiKeyFile = ${quoted(missing)}", cohereSelected))
        .left
        .value
      error.message should include("llm4s.rerank.cohere.apiKeyFile")
      error.message should include(missing.toString)
    }

    "send the literal key, with no provider, when no file is set" in {
      val config = RerankerConfigLoader
        .load(ReferenceConfig.withEnv("", cohereSelected + ("COHERE_API_KEY" -> "co-shared")))
        .value
        .value
      config.apiKeyProvider shouldBe None
      config.currentApiKey().value.reveal shouldBe "co-shared"
    }
  }
