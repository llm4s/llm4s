package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.provider.OllamaEmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Ollama as an embedding provider: configuration, defaults, client construction and
 * model dimensions.
 *
 * Gathered from the core specs that exercised it - `EmbeddingsConfigLoaderSpec`,
 * `EmbeddingsConfigSpec`, `Llm4sConfigEmbeddingsSpec`, `EmbeddingProviderSpiSpec`,
 * `EmbeddingClientFactorySpec`, `ModelDimensionRegistrySpec` and
 * `Llm4sConfigTextModelSpec` - when the provider moved to `llm4s-ollama` (#1132).
 *
 * Nothing here registers Ollama by hand: every case resolves it through
 * `ProviderRegistry.default`, so these also prove that this module's
 * `META-INF/services` entry is found and its `reference.conf` block is merged.
 */
class OllamaEmbeddingsSpec extends AnyWordSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
  }

  private def load(hocon: String): Result[(String, EmbeddingProviderConfig)] =
    EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

  "EmbeddingsConfigLoader" should {

    "successfully load Ollama embeddings via provider/model format" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "ollama/nomic-embed-text"
          |    ollama {
          |      baseUrl = "http://localhost:11434"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      provider shouldBe "ollama"
      cfg.model shouldBe "nomic-embed-text"
      cfg.baseUrl shouldBe "http://localhost:11434"
    }

    "successfully load Ollama embeddings via legacy provider setting" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    provider = "ollama"
          |    ollama {
          |      baseUrl = "http://ollama-server:11434"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      provider shouldBe "ollama"
      // Ollama has a default model
      cfg.model shouldBe "nomic-embed-text"
      cfg.baseUrl shouldBe "http://ollama-server:11434"
    }

    "use default baseUrl for Ollama when not specified" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "ollama/mxbai-embed-large"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value._2.baseUrl shouldBe "http://localhost:11434"
    }

    "use default model for Ollama when not specified" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    provider = "ollama"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value._2.model shouldBe "nomic-embed-text"
    }
  }

  "Llm4sConfig.embeddings" should {

    "load Ollama embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "ollama",
        "llm4s.embeddings.ollama.baseUrl" -> "http://localhost:11434",
        "llm4s.embeddings.ollama.model"   -> "nomic-embed-text"
      )
      withProps(props) {
        val (provider, cfg) = Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "ollama"
        cfg.baseUrl shouldBe "http://localhost:11434"
        cfg.model shouldBe "nomic-embed-text"
        // Ollama doesn't require API key, defaults to "not-required"
        cfg.apiKey shouldBe "not-required"
      }
    }

    "load Ollama embeddings config with defaults" in {
      val props = Map(
        "llm4s.embeddings.provider"     -> "ollama",
        "llm4s.embeddings.ollama.model" -> "mxbai-embed-large"
      )
      withProps(props) {
        val (provider, cfg) = Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "ollama"
        // Default baseUrl when not specified
        cfg.baseUrl shouldBe "http://localhost:11434"
        cfg.model shouldBe "mxbai-embed-large"
        cfg.apiKey shouldBe "not-required"
      }
    }

    "load Ollama embeddings via unified EMBEDDING_MODEL format" in {
      val props = Map(
        "llm4s.embeddings.model" -> "ollama/mxbai-embed-large"
        // No explicit baseUrl - should use default
        // No API key needed for Ollama
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "ollama"
        cfg.model shouldBe "mxbai-embed-large"
        cfg.baseUrl shouldBe "http://localhost:11434" // Default base URL
        cfg.apiKey shouldBe "not-required"
      }
    }
  }

  "the defaults that used to be duplicated" should {

    "apply with no section present at all" in {
      // An absent `llm4s.embeddings.<id>` must read as an empty section, not as a
      // config failure - a provider whose defaults cover everything needs no section.
      val (provider, config) = load("""llm4s { embeddings { model = "ollama/nomic-embed-text" } }""").value

      provider shouldBe "ollama"
      config.model shouldBe "nomic-embed-text"
    }

    "come from the descriptor rather than reference.conf" in {
      // Ollama's base URL and model were stated twice - in reference.conf and as
      // `DefaultOllamaEmbeddingBaseUrl` in the loader - with nothing keeping them in step.
      // Neither the section nor reference.conf is consulted here.
      val (_, config) = load("""llm4s { embeddings { model = "ollama/nomic-embed-text" } }""").value

      config.baseUrl shouldBe "http://localhost:11434"
      config.apiKey shouldBe "not-required"
    }

    "still let a section override them" in {
      val (_, config) = load(
        """llm4s {
          |  embeddings {
          |    model = "ollama/mxbai-embed-large"
          |    ollama { baseUrl = "http://gpu-box:11434" }
          |  }
          |}""".stripMargin
      ).value

      config.baseUrl shouldBe "http://gpu-box:11434"
      config.model shouldBe "mxbai-embed-large"
    }
  }

  "the reference.conf block" should {
    "travel with the provider" in {
      // Invariant 3 of #1126: the keys moved with the code they configure. Core's
      // reference.conf no longer mentions ollama, so this can only come from ours.
      val merged = ConfigFactory.load()
      merged.hasPath("llm4s.embeddings.ollama") shouldBe true
    }

    "bind OLLAMA_EMBEDDING_BASE_URL" in {
      // Every reference.conf on the classpath, unresolved, resolved against a stand-in for
      // the environment - `defaultReference()` has already resolved the substitution.
      val merged = ConfigFactory
        .parseString("OLLAMA_EMBEDDING_BASE_URL = \"http://from-env:11434\"")
        .withFallback(ConfigFactory.parseResourcesAnySyntax("reference"))
        .resolve()
      merged.getString("llm4s.embeddings.ollama.baseUrl") shouldBe "http://from-env:11434"
    }
  }

  "EmbeddingClient.from" should {

    "build client for ollama without throwing" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "http://localhost:11434",
        model = "nomic-embed-text",
        apiKey = "not-required"
      )
      val res = EmbeddingClient.from("ollama", cfg)
      res.isRight shouldBe true
    }

    "build client for ollama with empty apiKey" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "http://localhost:11434",
        model = "mxbai-embed-large",
        apiKey = ""
      )
      val res = EmbeddingClient.from("ollama", cfg)
      res.isRight shouldBe true
    }
  }

  "ModelDimensionRegistry" should {

    "know the documented Ollama embedding models" in {
      ModelDimensionRegistry.getDimension("ollama", "nomic-embed-text").value shouldBe 768
      ModelDimensionRegistry.getDimension("ollama", "mxbai-embed-large").value shouldBe 1024
      ModelDimensionRegistry.getDimension("ollama", "all-minilm").value shouldBe 384
    }

    "fold an Ollama :latest tag onto the untagged name, and no other tag" in {
      // Other tags of one model can differ in size, so guessing from the base name
      // would be wrong for some of them.
      ModelDimensionRegistry.getDimension("ollama", "nomic-embed-text:latest").value shouldBe 768
      ModelDimensionRegistry.getDimension("ollama", "nomic-embed-text:v1.5").left.value.formatted should include(
        "Unknown model 'nomic-embed-text:v1.5'"
      )
    }

    "declare the dimensions of the default model" in {
      val default = OllamaEmbeddingProvider.configSpec.defaultModel.getOrElse(fail("Ollama declares a default model"))
      OllamaEmbeddingProvider.dimensionsOf(default) shouldBe defined
    }
  }

  "Llm4sConfig.textEmbeddingModel" should {

    "resolve EMBEDDING_MODEL=ollama/nomic-embed-text, as the README documents" in {
      // Regression: the central dimension table had no ollama entry, so the documented
      // configuration failed here with "Unknown model 'nomic-embed-text'".
      withProps(Map("llm4s.embeddings.model" -> "ollama/nomic-embed-text")) {
        val settings = Llm4sConfig.textEmbeddingModel().value

        settings.provider shouldBe "ollama"
        settings.modelName shouldBe "nomic-embed-text"
        settings.dimensions shouldBe 768
      }
    }
  }
}
