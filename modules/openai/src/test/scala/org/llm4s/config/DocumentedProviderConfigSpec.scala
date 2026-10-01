package org.llm4s.config

import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.provider.OpenAIProvider
import org.llm4s.model.ModelRegistryService
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * The configuration the getting-started docs teach, loaded exactly as written.
 *
 * `docs/getting-started/configuration.md` (and the README, installation and first-example
 * pages) tell users to export `OPENAI_API_KEY`, put a named provider section holding only
 * `provider` and `model` in their own `application.conf`, and call
 * `Llm4sConfig.defaultProvider()`. The key reaches the section through
 * `llm4s.credentials.openai.apiKey`, which this module's `reference.conf` binds. A section for a
 * second account sets its own `apiKey = ${?OPENAI_BATCH_API_KEY}`. If one of these fails, the
 * docs are teaching something that does not work.
 *
 * Every `reference.conf` on the classpath is loaded, as for a user, and the environment is
 * injected with the real one switched off, so the cases do not depend on whether the machine
 * running them has `OPENAI_API_KEY` set. No request is sent: the client is built, not called.
 */
class DocumentedProviderConfigSpec extends AnyWordSpec with Matchers:

  /** The getting-started `application.conf`, verbatim. */
  private val gettingStarted =
    """
      |llm4s {
      |  providers {
      |    provider = "openai-main"          # the default: the name of a section below
      |
      |    openai-main {
      |      provider = "openai"
      |      model    = "gpt-4o-mini"
      |    }
      |  }
      |}
      |""".stripMargin

  /** The "switching providers" `application.conf`: two sections, default overridable by a variable. */
  private val twoSections =
    """
      |llm4s {
      |  providers {
      |    provider = "openai-main"
      |    provider = ${?LLM4S_PROVIDER}
      |
      |    openai-main {
      |      provider = "openai"
      |      model    = "gpt-4o-mini"
      |    }
      |
      |    openai-large {
      |      provider = "openai"
      |      model    = "gpt-4o"
      |    }
      |  }
      |}
      |""".stripMargin

  /** The "second account" `application.conf`: one section inherits, one sets its own key. */
  private val secondAccount =
    """
      |llm4s {
      |  providers {
      |    provider = "openai-main"
      |
      |    openai-main {
      |      provider = "openai"
      |      model    = "gpt-4o-mini"
      |    }
      |
      |    openai-batch {
      |      provider = "openai"
      |      model    = "gpt-4o-mini"
      |      apiKey   = ${?OPENAI_BATCH_API_KEY}
      |    }
      |  }
      |}
      |""".stripMargin

  private def withEnv(hocon: String, env: Map[String, String]): ConfigSource = ReferenceConfig.withEnv(hocon, env)

  private def apiKey(result: org.llm4s.types.Result[org.llm4s.llmconnect.config.ProviderConfig]): String =
    result match
      case Right(openai: OpenAIConfig) => openai.apiKey
      case other                       => fail(s"Expected OpenAIConfig, got $other")

  "The getting-started application.conf" should {

    "resolve the default provider, taking the API key from OPENAI_API_KEY with no apiKey line" in {
      Llm4sConfig.defaultProvider(withEnv(gettingStarted, Map("OPENAI_API_KEY" -> "sk-from-env"))) match
        case Right(openai: OpenAIConfig) =>
          openai.model shouldBe "gpt-4o-mini"
          openai.apiKey shouldBe "sk-from-env"
          openai.baseUrl shouldBe OpenAIProvider.DEFAULT_BASE_URL
        case other =>
          fail(s"Expected OpenAIConfig, got $other")
    }

    "build a client from that config without sending a request" in {
      val source = withEnv(gettingStarted, Map("OPENAI_API_KEY" -> "sk-from-env"))
      val client =
        for
          config   <- Llm4sConfig.defaultProvider(source)
          registry <- Llm4sConfig.modelRegistryService(source)
          given ModelRegistryService = registry
          client <- LLMConnect.getClient(config)
        yield client

      client.isRight shouldBe true
    }

    "fail naming both the variable and the section when OPENAI_API_KEY is unset" in {
      Llm4sConfig.defaultProvider(withEnv(gettingStarted, Map.empty)) match
        case Left(err) =>
          err.message should include(
            "apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf"
          )
        case Right(cfg) =>
          fail(s"Expected a missing-apiKey error, got $cfg")
    }

    "ignore LLM_MODEL, which nothing reads" in {
      val env = Map("OPENAI_API_KEY" -> "sk-from-env", "LLM_MODEL" -> "openai/gpt-4o")
      Llm4sConfig.defaultProvider(withEnv(gettingStarted, env)).map(_.model) shouldBe Right("gpt-4o-mini")
    }
  }

  "The two-section application.conf" should {

    "use the section named by `provider` when LLM4S_PROVIDER is unset" in {
      Llm4sConfig
        .defaultProvider(withEnv(twoSections, Map("OPENAI_API_KEY" -> "sk-from-env")))
        .map(_.model) shouldBe Right("gpt-4o-mini")
    }

    "switch the default to the section LLM4S_PROVIDER names" in {
      val env = Map("OPENAI_API_KEY" -> "sk-from-env", "LLM4S_PROVIDER" -> "openai-large")
      Llm4sConfig.defaultProvider(withEnv(twoSections, env)).map(_.model) shouldBe Right("gpt-4o")
    }

    "load a section by name regardless of the default" in {
      Llm4sConfig
        .provider(withEnv(twoSections, Map("OPENAI_API_KEY" -> "sk-from-env")), "openai-large")
        .map(_.model) shouldBe Right("gpt-4o")
    }
  }

  "The second-account application.conf" should {

    val env = Map("OPENAI_API_KEY" -> "sk-default-account", "OPENAI_BATCH_API_KEY" -> "sk-batch-account")

    "give the section without a key the shared OPENAI_API_KEY" in {
      apiKey(Llm4sConfig.provider(withEnv(secondAccount, env), "openai-main")) shouldBe "sk-default-account"
    }

    "give the section with its own key that key, not the shared one" in {
      apiKey(Llm4sConfig.provider(withEnv(secondAccount, env), "openai-batch")) shouldBe "sk-batch-account"
    }

    "be flagged as inheriting by apiKeySources, for the prod policy" in {
      Llm4sConfig.apiKeySourcesFrom(withEnv(secondAccount, env)).map(_.map((k, v) => k.asName -> v)) shouldBe Right(
        Map(
          "openai-main"  -> ApiKeySource.Credentials("llm4s.credentials.openai.apiKey"),
          "openai-batch" -> ApiKeySource.Section("llm4s.providers.openai-batch.apiKey")
        )
      )
    }
  }

  "The documented OpenAI embeddings config" should {

    // Only the model: the key is OpenAI's shared llm4s.credentials.openai.apiKey, which this
    // module binds to OPENAI_API_KEY, the same key the chat sections use.
    val embeddings = """llm4s.embeddings.model = "openai/text-embedding-3-small""""

    "take the API key from OPENAI_API_KEY with no apiKey line" in {
      EmbeddingsConfigLoader.loadProvider(withEnv(embeddings, Map("OPENAI_API_KEY" -> "sk-from-env"))) match
        case Right((provider, cfg)) =>
          provider shouldBe "openai"
          cfg.model shouldBe "text-embedding-3-small"
          cfg.apiKey shouldBe "sk-from-env"
        case other =>
          fail(s"Expected an openai embedding config, got $other")
    }

    "take an embeddings-only key over OPENAI_API_KEY when the block sets one" in {
      val hocon = embeddings + "\nllm4s.embeddings.openai.apiKey = ${?OPENAI_EMBEDDINGS_API_KEY}"
      val env   = Map("OPENAI_API_KEY" -> "sk-chat", "OPENAI_EMBEDDINGS_API_KEY" -> "sk-embeddings")
      EmbeddingsConfigLoader.loadProvider(withEnv(hocon, env)).map(_._2.apiKey) shouldBe Right("sk-embeddings")
    }
  }
