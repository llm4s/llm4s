package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.{ ConfigFactory, ConfigResolveOptions }
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.provider.OpenAIProvider
import org.llm4s.model.ModelRegistryService
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*

/**
 * The configuration the getting-started docs teach, loaded exactly as written.
 *
 * `docs/getting-started/configuration.md` (and the README, installation and first-example
 * pages) tell users to put a named provider section in their own `application.conf`, bind the
 * API key with `apiKey = ${?OPENAI_API_KEY}` and call `Llm4sConfig.defaultProvider()`. Nothing
 * in the library reads `LLM_MODEL` or `OPENAI_API_KEY` by itself (#1132), so this is the route
 * that has to work. If one of these fails, the docs are teaching something that does not.
 *
 * The environment is supplied as root-level keys with the real process environment switched
 * off, so the cases do not depend on whether the machine running them has `OPENAI_API_KEY` set.
 * No request is sent: the client is built, not called.
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
      |      apiKey   = ${?OPENAI_API_KEY}
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
      |      apiKey   = ${?OPENAI_API_KEY}
      |    }
      |
      |    openai-large {
      |      provider = "openai"
      |      model    = "gpt-4o"
      |      apiKey   = ${?OPENAI_API_KEY}
      |    }
      |  }
      |}
      |""".stripMargin

  /** `hocon` resolved against `env` as if it were the process environment, and only that. */
  private def withEnv(hocon: String, env: Map[String, String]): ConfigSource =
    ConfigSource.fromConfig(
      // The variables sit at the root, where `${?VAR}` looks first, and the real environment -
      // where it looks next - is switched off.
      ConfigFactory
        .parseString(hocon)
        .withFallback(ConfigFactory.parseMap(env.asJava))
        .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
    )

  "The getting-started application.conf" should {

    "resolve the default provider, taking the API key from OPENAI_API_KEY" in {
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

    "fail naming the key to set when OPENAI_API_KEY is unset" in {
      Llm4sConfig.defaultProvider(withEnv(gettingStarted, Map.empty)) match
        case Left(err) =>
          err.message should include("providers.openai-main.apiKey")
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

  "The documented OpenAI embeddings config" should {

    // OpenAI embeddings read `llm4s.embeddings.openai.apiKey`. No reference.conf binds it to
    // OPENAI_API_KEY - llm4s reads no provider API-key variable on its own - so the docs tell
    // users to bind it themselves.
    val embeddings =
      """
        |llm4s {
        |  embeddings {
        |    model = "openai/text-embedding-3-small"
        |    openai.apiKey = ${?OPENAI_API_KEY}
        |  }
        |}
        |""".stripMargin

    "take the API key from OPENAI_API_KEY once the section binds it" in {
      EmbeddingsConfigLoader.loadProvider(withEnv(embeddings, Map("OPENAI_API_KEY" -> "sk-from-env"))) match
        case Right((provider, cfg)) =>
          provider shouldBe "openai"
          cfg.model shouldBe "text-embedding-3-small"
          cfg.apiKey shouldBe "sk-from-env"
        case other =>
          fail(s"Expected an openai embedding config, got $other")
    }

    "not see OPENAI_API_KEY when nothing binds it" in {
      val unbound = "llm4s.embeddings.model = \"openai/text-embedding-3-small\""
      EmbeddingsConfigLoader
        .loadProvider(withEnv(unbound, Map("OPENAI_API_KEY" -> "sk-from-env")))
        .isLeft shouldBe true
    }
  }
