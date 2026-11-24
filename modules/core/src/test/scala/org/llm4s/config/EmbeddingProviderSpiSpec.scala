package org.llm4s.config

import org.llm4s.security.Secret
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{
  EmbeddingBuildContext,
  EmbeddingConfigContext,
  EmbeddingConfigSpec,
  EmbeddingProviderDescriptor,
  EmbeddingProviderSection,
  ProviderConfigKey,
  ProviderRegistry
}
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * The end-to-end claim of the embedding half of the SPI (#1131): an embedding
 * provider `llm4s-core` knows nothing about is '''configurable''', not merely
 * registrable.
 *
 * `EmbeddingClientFactorySpec` covers the client half - a registered descriptor
 * builds a client. This covers the half that was still missing afterwards: the
 * same descriptor's `llm4s.embeddings.<id>` section is read, its own defaults
 * are applied, and its own errors surface. Before this, a third-party provider
 * could be resolved and then had nothing to be resolved *with*, because
 * `EmbeddingsConfigLoader` held a typed section per built-in provider.
 */
class EmbeddingProviderSpiSpec extends AnyWordSpec with Matchers with EitherValues {

  /** A provider nothing in core mentions, with a spec exercising every default. */
  private object FixtureEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId                = ProviderId("fixturecloud")
    override val aliases: Set[String] = Set("fixture-cloud")

    override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
      requiresApiKey = true,
      defaultBaseUrl = Some("https://fixture.example/v1"),
      defaultModel = Some("fixture-default-model"),
      apiKeyEnv = Seq("FIXTURE_API_KEY")
    )

    def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }

  /** A provider needing no key and no section, as a local server does - Ollama's shape. */
  private object KeylessFixtureEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId = ProviderId("keylessfixture")

    override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
      defaultBaseUrl = Some("http://localhost:9999"),
      defaultModel = Some("fixture-default"),
      defaultApiKey = Some("fixture-not-required")
    )

    def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }

  private given ProviderRegistry =
    ProviderRegistry.default
      .withEmbeddingProvider(FixtureEmbeddings)
      .withEmbeddingProvider(KeylessFixtureEmbeddings)

  private def load(hocon: String): Result[(String, EmbeddingProviderConfig)] =
    EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

  "an embedding provider core knows nothing about" should {

    "be configurable through its own section" in {
      val (provider, config) = load(
        """llm4s {
          |  embeddings {
          |    model = "fixturecloud/fixture-large"
          |    fixturecloud { apiKey = "fk-test", baseUrl = "https://tenant.fixture.example/v1" }
          |  }
          |}""".stripMargin
      ).value

      provider shouldBe "fixturecloud"
      config shouldBe EmbeddingProviderConfig(
        baseUrl = "https://tenant.fixture.example/v1",
        model = "fixture-large",
        apiKey = Some(Secret("fk-test"))
      )
    }

    "fall back to the defaults its descriptor declares" in {
      // The section carries only what the descriptor requires; the base URL is its own
      // default, which core has no way to know.
      val (_, config) = load(
        """llm4s {
          |  embeddings {
          |    model = "fixturecloud/some-model"
          |    fixturecloud { apiKey = "fk-test" }
          |  }
          |}""".stripMargin
      ).value

      config.baseUrl shouldBe "https://fixture.example/v1"
    }

    "prefer the unified EMBEDDING_MODEL over the section's model" in {
      val (_, config) = load(
        """llm4s {
          |  embeddings {
          |    model = "fixturecloud/from-unified"
          |    fixturecloud { apiKey = "fk", model = "from-section" }
          |  }
          |}""".stripMargin
      ).value

      config.model shouldBe "from-unified"
    }

    "be reachable by an alias it declares" in {
      val (provider, _) = load(
        """llm4s {
          |  embeddings {
          |    model = "fixture-cloud/m"
          |    fixturecloud { apiKey = "fk" }
          |  }
          |}""".stripMargin
      ).value

      // Reported under the canonical id, not the alias the user typed.
      provider shouldBe "fixturecloud"
    }

    "surface its own requirements as errors" in {
      val error = load(
        """llm4s { embeddings { model = "fixturecloud/m" } }"""
      ).left.value.message

      error should include("Missing fixturecloud embeddings apiKey")
      // The error names both the config path and the environment variable the descriptor
      // declared - neither of which core could have known.
      error should include("apiKey under llm4s.embeddings.fixturecloud")
      error should include("FIXTURE_API_KEY")
    }
  }

  "a provider that is registered for chat only" should {
    "not be configurable as an embedding provider" in {
      // The test fixture provider supplies chat and nothing else.
      val error = load("""llm4s { embeddings { model = "fixturechat/whatever" } }""").left.value.message

      error should include("Embedding provider 'fixturechat'")
      error should include("is not registered")
    }
  }

  "a descriptor" should {
    "build its config from a section alone, reading nothing" in {
      // The configuration boundary: `org.llm4s.config` reads raw config, everything else
      // consumes typed settings handed to it. `buildConfig` takes no config source and no
      // lookup, so a descriptor cannot reach for a key even if it wanted to - which is why
      // the shared `llm4s.credentials.<id>.apiKey` fallback happens in the loader.
      val config = FixtureEmbeddings
        .buildConfig(EmbeddingProviderSection(apiKey = Some(Secret("fk-direct"))), EmbeddingConfigContext(Some("m")))
        .value

      config shouldBe EmbeddingProviderConfig(
        baseUrl = "https://fixture.example/v1",
        model = "m",
        apiKey = Some(Secret("fk-direct"))
      )
    }
  }

  "a provider whose id contains a dot" should {

    /** `ProviderId` canonicalises but does not restrict, so this is a legal id. */
    object DottedEmbeddings extends EmbeddingProviderDescriptor {
      val id: ProviderId = ProviderId("acme.embeddings")

      override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
        requiresApiKey = true,
        defaultBaseUrl = Some("https://acme.example/v1")
      )

      def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
        Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
    }

    "read the section the user actually wrote" in {
      // Interpolating the id would look under llm4s.embeddings.acme.embeddings - a nested
      // path - and silently find nothing, replacing the section with defaults.
      given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(DottedEmbeddings)

      val (provider, config) = EmbeddingsConfigLoader
        .loadProvider(
          ConfigSource.string(
            """llm4s {
              |  embeddings {
              |    model = "acme.embeddings/m"
              |    "acme.embeddings" { apiKey = "ak", baseUrl = "https://tenant.acme.example/v1" }
              |  }
              |}""".stripMargin
          )
        )
        .value

      provider shouldBe "acme.embeddings"
      config.apiKey.map(_.reveal) shouldBe Some("ak")
      config.baseUrl shouldBe "https://tenant.acme.example/v1"
    }

    "name a quoted path in its errors, so the reader can copy it" in {
      EmbeddingConfigSpec.sectionPath(DottedEmbeddings.id) shouldBe """llm4s.embeddings."acme.embeddings""""
      EmbeddingConfigSpec.fieldPath(DottedEmbeddings.id, "apiKey") shouldBe
        """llm4s.embeddings."acme.embeddings".apiKey"""
    }

    "leave an ordinary id unquoted" in {
      EmbeddingConfigSpec.sectionPath(ProviderId("openai")) shouldBe "llm4s.embeddings.openai"
    }
  }

  "an unregistered provider" should {

    "be reported against llm4s.embeddings.model when selected that way" in {
      val error = load("""llm4s { embeddings { model = "nosuch/whatever" } }""").left.value.message

      error should include("(from llm4s.embeddings.model)")
    }

  }

  "the defaults that used to be duplicated" should {

    "apply with no section present at all" in {
      // An absent `llm4s.embeddings.<id>` must read as an empty section, not as a
      // config failure - a provider whose defaults cover everything needs no section.
      val (provider, config) = load("""llm4s { embeddings { model = "keylessfixture/fixture-small" } }""").value

      provider shouldBe "keylessfixture"
      config.model shouldBe "fixture-small"
    }

    "come from the descriptor rather than reference.conf" in {
      // Ollama's base URL and model were stated twice - in reference.conf and as
      // `DefaultOllamaEmbeddingBaseUrl` in the loader - with nothing keeping them in step.
      // Ollama now lives in llm4s-ollama, which checks its own; this is the same property
      // for a provider with no reference.conf block at all.
      val (_, config) = load("""llm4s { embeddings { model = "keylessfixture/fixture-small" } }""").value

      config.baseUrl shouldBe "http://localhost:9999"
      config.apiKey.map(_.reveal) shouldBe Some("fixture-not-required")
    }

    "still let a section override them" in {
      val (_, config) = load(
        """llm4s {
          |  embeddings {
          |    model = "keylessfixture/fixture-large"
          |    keylessfixture { baseUrl = "http://gpu-box:9999" }
          |  }
          |}""".stripMargin
      ).value

      config.baseUrl shouldBe "http://gpu-box:9999"
      config.model shouldBe "fixture-large"
    }
  }

  "an embedding provider's declared extras (#2811)" should {

    /** A provider with a required key, one with a default, and a `buildConfig` of its own that reads them. */
    object RegionalEmbeddings extends EmbeddingProviderDescriptor {
      val id: ProviderId = ProviderId("regionalfixture")

      val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
        defaultBaseUrl = Some("https://regional.fixture.example/v1"),
        defaultModel = Some("regional-small"),
        extras = Seq(
          ProviderConfigKey.required("region", "the region serving the model").withEnv("REGIONAL_FIXTURE_REGION"),
          ProviderConfigKey.optional("task", "what the vectors are for", default = Some("retrieval"))
        )
      )

      def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
        Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
    }

    /** Declares a key every section already has. */
    object ClashingEmbeddings extends EmbeddingProviderDescriptor {
      val id: ProviderId = ProviderId("clashingfixture")
      val configSpec: EmbeddingConfigSpec =
        EmbeddingConfigSpec(extras = Seq(ProviderConfigKey.optional("baseUrl", "not its to declare")))
      def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
        Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
    }

    given ProviderRegistry = ProviderRegistry.ofEmbeddings(RegionalEmbeddings, ClashingEmbeddings)

    def loaded(hocon: String): Result[(String, EmbeddingProviderConfig)] =
      EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

    "reach the config the descriptor builds, defaults applied and undeclared keys left out" in {
      val (_, config) = loaded(
        """llm4s.embeddings {
          |  model = "regionalfixture/regional-large"
          |  regionalfixture { region = " eu-west ", colour = "blue" }
          |}""".stripMargin
      ).value

      config.extras shouldBe Map("region" -> "eu-west", "task" -> "retrieval")
      config.extra("region") shouldBe Some("eu-west")
      config.extra("colour") shouldBe None
    }

    "let the section override a default" in {
      val (_, config) = loaded(
        """llm4s.embeddings {
          |  model = "regionalfixture/regional-large"
          |  regionalfixture { region = "us-east", task = "clustering" }
          |}""".stripMargin
      ).value

      config.extra("task") shouldBe Some("clustering")
    }

    "name a required key that is missing, its path and the binding for its variable" in {
      val message = loaded("""llm4s.embeddings.model = "regionalfixture/regional-large"""").left.value.message

      message should include("Embedding provider 'regionalfixture' is missing required fields")
      message should include("region: the region serving the model")
      message should include("llm4s.embeddings.regionalfixture.region")
      message should include("region = ${?REGIONAL_FIXTURE_REGION}")
    }

    "treat a blank value as unset" in {
      loaded(
        """llm4s.embeddings {
          |  model = "regionalfixture/regional-large"
          |  regionalfixture { region = "  " }
          |}""".stripMargin
      ).left.value.message should include("missing required fields")
    }

    "refuse a provider that declares a key every section has" in {
      loaded("""llm4s.embeddings.model = "clashingfixture/m"""").left.value.message should include(
        "declares baseUrl as provider-specific key(s)"
      )
    }

    "keep extra values out of the config's toString" in {
      val (_, config) = loaded(
        """llm4s.embeddings {
          |  model = "regionalfixture/regional-large"
          |  regionalfixture { region = "eu-west-secret" }
          |}""".stripMargin
      ).value

      config.toString should include("region=***")
      (config.toString should not).include("eu-west-secret")
    }
  }
}
