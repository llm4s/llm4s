package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAICompatibleConfig, ProviderConfig }
import org.llm4s.llmconnect.provider.OpenAICompatibleProvider
import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistryService }
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Where the generic `openai-compatible` provider gets its context window (#1217): the section's own
 * `contextWindow`, else the model registry's entry under an explicit `registryProvider`, else the entry under
 * the provider inferred from the `baseUrl` host, else the default.
 *
 * The first half runs from HOCON through `Llm4sConfig` against the bundled registry, as a user's
 * `application.conf` does. The second half drives `buildConfig` with registries built here, so each rule is
 * pinned against data the test controls.
 */
class OpenAICompatibleRegistryContextWindowSpec extends AnyWordSpec with Matchers:

  private val Default = OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW

  /** Loads one `openai-compatible` section through the whole named-provider path. */
  private def load(baseUrl: String, model: String, extra: String = ""): Result[ProviderConfig] =
    val hocon =
      s"""llm4s.providers {
         |  provider = "t"
         |  t {
         |    provider = "openai-compatible"
         |    baseUrl = "$baseUrl"
         |    model = "$model"
         |    $extra
         |  }
         |}""".stripMargin
    Llm4sConfig.provider(ConfigSource.string(hocon), "t")

  private def generic(result: Result[ProviderConfig]): OpenAICompatibleConfig =
    result match
      case Right(cfg: OpenAICompatibleConfig) => cfg
      case other                              => fail(s"expected an OpenAICompatibleConfig, got $other")

  private def window(baseUrl: String, model: String, extra: String = ""): Int =
    generic(load(baseUrl, model, extra)).contextWindow

  // ---------------------------------------------------------------------------------------------
  // the bundled registry, from HOCON
  // ---------------------------------------------------------------------------------------------

  "an openai-compatible section on a hosted API the registry knows" should {

    "take Groq's window from the registry, with no contextWindow set" in {
      window("https://api.groq.com/openai/v1", "openai/gpt-oss-120b") shouldBe 131072
    }

    "take Fireworks' window for a model id that is a full path" in {
      window("https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/gpt-oss-120b") shouldBe 131072
    }

    "take Perplexity's window, though its baseUrl has no /v1" in {
      window("https://api.perplexity.ai", "sonar-pro") shouldBe 200000
    }

    "take xAI's window" in {
      window("https://api.x.ai/v1", "grok-2") shouldBe 131072
    }

    "take Together's window from either of its hosts" in {
      val model = "Qwen/Qwen3-235B-A22B-Instruct-2507-tput"
      window("https://api.together.xyz/v1", model) shouldBe 262000
      window("https://api.together.ai/v1", model) shouldBe 262000
    }

    "infer the host whatever the case, with a port, or with a trailing slash" in {
      window("https://API.GROQ.COM/openai/v1", "openai/gpt-oss-120b") shouldBe 131072
      window("https://api.groq.com:443/openai/v1", "openai/gpt-oss-120b") shouldBe 131072
      window("https://api.groq.com/openai/v1/", "openai/gpt-oss-120b") shouldBe 131072
    }

    "keep the reserve's own rule: a quarter of the window, up to 2048" in {
      val cfg = generic(load("https://api.groq.com/openai/v1", "openai/gpt-oss-120b"))

      cfg.reserveCompletion shouldBe OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION
    }

    "accept a configured reserve that fits inside the registry's window" in {
      val cfg = generic(load("https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "reserveCompletion = 8192"))

      cfg.contextWindow shouldBe 131072
      cfg.reserveCompletion shouldBe 8192
    }
  }

  "a configured contextWindow" should {

    "always win over the registry" in {
      val cfg = generic(load("https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "contextWindow = 4096"))

      cfg.contextWindow shouldBe 4096
      cfg.reserveCompletion shouldBe 1024 // a quarter of the smaller window
    }

    "win over an explicit registryProvider too" in {
      window(
        "https://api.groq.com/openai/v1",
        "llama-3.1-8b-instant",
        """registryProvider = "groq"
          |contextWindow = 2048""".stripMargin
      ) shouldBe 2048
    }
  }

  "an explicit registryProvider" should {

    "supply the window for an endpoint the host table does not know" in {
      window(
        "https://llm-gateway.internal.example/v1",
        "llama-3.1-8b-instant",
        """registryProvider = "groq""""
      ) shouldBe
        128000
    }

    "switch the host inference off, so a provider with no such model gives the default" in {
      // api.groq.com would infer groq, which has this model; naming perplexity means perplexity's entries only.
      window(
        "https://api.groq.com/openai/v1",
        "openai/gpt-oss-120b",
        """registryProvider = "perplexity""""
      ) shouldBe Default
    }

    "give the default for a registry provider the registry has never heard of" in {
      window(
        "https://llm-gateway.internal.example/v1",
        "llama-3.1-8b-instant",
        """registryProvider = "no-such-provider""""
      ) shouldBe Default
    }

    "reach NVIDIA NIM, which the host table leaves out, though the registry has no usable entry there" in {
      window("https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct") shouldBe Default
      window(
        "https://integrate.api.nvidia.com/v1",
        "meta/llama-3.3-70b-instruct",
        """registryProvider = "nvidia_nim""""
      ) shouldBe Default
    }

    "be the same as none when blank, since a blank extra is an unset one" in {
      // The group's normalizer drops a blank value, so the host is inferred as if the key were absent.
      window("https://api.groq.com/openai/v1", "openai/gpt-oss-120b", """registryProvider = "  """") shouldBe 131072
      window("https://llm-gateway.internal.example/v1", "llama-3.1-8b-instant", """registryProvider = """"") shouldBe
        Default
    }
  }

  "a model the registry cannot give a window for" should {

    "take the default, not the registry's 4096 placeholder, for an 80k-context Fireworks model" in {
      // The registry lists minimax-m1-80k with 4096 for input, output and total alike.
      window("https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/minimax-m1-80k") shouldBe Default
    }

    "still accept a reserveCompletion that fits the default, where the registry's placeholder would reject it" in {
      val cfg = generic(
        load(
          "https://api.fireworks.ai/inference/v1",
          "accounts/fireworks/models/qwen3-coder-480b-instruct-bf16",
          "reserveCompletion = 4096"
        )
      )

      cfg.contextWindow shouldBe Default
      cfg.reserveCompletion shouldBe 4096
    }

    "take the default when the registry has no such model under the host's provider" in {
      window("https://api.groq.com/openai/v1", "a-model-groq-has-not-listed") shouldBe Default
    }

    "take the default when the registry lists the model with no input limit" in {
      // Together's registry entry for this model carries no max_input_tokens, which is why the recipe keeps
      // its hand-set contextWindow.
      window("https://api.together.ai/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo") shouldBe Default
    }
  }

  "a baseUrl that is not one of the known hosts" should {

    "get no registry window, however much it looks like one" in {
      val lookalikes = Seq(
        "https://api.groq.com.evil.example/openai/v1", // the known host is a prefix of the real one
        "https://api.groq.com@evil.example/openai/v1", // the known host is only the userinfo
        "https://evil.example/api.groq.com/openai/v1", // the known host is only in the path
        "https://evil.example/v1?host=api.groq.com",   // ... or only in the query
        "https://eu.api.groq.com/openai/v1",           // a subdomain of it
        "https://notapi.groq.com/openai/v1",           // a different host that ends the same way
        "http://localhost:8000/v1"
      )

      lookalikes.foreach(url => withClue(url)(window(url, "openai/gpt-oss-120b") shouldBe Default))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // the recipes in docs/guide/providers.md, as written there
  // ---------------------------------------------------------------------------------------------

  private def recipe(name: String, body: String): OpenAICompatibleConfig =
    generic(Llm4sConfig.provider(ConfigSource.string(s"llm4s.providers {\n$body\n}"), name))

  "the recipes in the providers guide" should {

    "give Groq's, which sets no contextWindow, the registry's window and the reserve it does set" in {
      val cfg = recipe(
        "groq-main",
        """groq-main {
          |  provider = "openai-compatible"
          |  baseUrl = "https://api.groq.com/openai/v1"
          |  model = "openai/gpt-oss-120b"
          |  apiKey = ${?GROQ_API_KEY}
          |  reserveCompletion = 8192
          |}""".stripMargin
      )

      cfg.contextWindow shouldBe 131072
      cfg.reserveCompletion shouldBe 8192
    }

    "give Fireworks' the registry's window" in {
      recipe(
        "fireworks-main",
        """fireworks-main {
          |  provider = "openai-compatible"
          |  baseUrl = "https://api.fireworks.ai/inference/v1"
          |  model = "accounts/fireworks/models/gpt-oss-120b"
          |  apiKey = ${?FIREWORKS_API_KEY}
          |}""".stripMargin
      ).contextWindow shouldBe 131072
    }

    "give Perplexity's the registry's window" in {
      recipe(
        "perplexity-sonar",
        """perplexity-sonar {
          |  provider = "openai-compatible"
          |  baseUrl = "https://api.perplexity.ai"    # no /v1: requests go to /chat/completions
          |  model = "sonar-pro"
          |  apiKey = ${?PERPLEXITY_API_KEY}
          |}""".stripMargin
      ).contextWindow shouldBe 200000
    }

    "keep the hand-set window of the recipes whose model the registry cannot give" in {
      recipe(
        "together-main",
        """together-main {
          |  provider = "openai-compatible"
          |  baseUrl = "https://api.together.ai/v1"
          |  model = "meta-llama/Llama-3.3-70B-Instruct-Turbo"
          |  apiKey = ${?TOGETHER_API_KEY}
          |  contextWindow = 131072
          |}""".stripMargin
      ).contextWindow shouldBe 131072

      recipe(
        "xai-main",
        """xai-main {
          |  provider = "openai-compatible"
          |  baseUrl = "https://api.x.ai/v1"
          |  model = "grok-4.7"
          |  apiKey = ${?XAI_API_KEY}
          |  contextWindow = 500000
          |}""".stripMargin
      ).contextWindow shouldBe 500000

      recipe(
        "nim-cloud",
        """nim-cloud {
          |  provider = "openai-compatible"
          |  baseUrl = "https://integrate.api.nvidia.com/v1"
          |  model = "meta/llama-3.3-70b-instruct"
          |  apiKey = ${?NVIDIA_API_KEY}
          |  contextWindow = 128000
          |}""".stripMargin
      ).contextWindow shouldBe 128000
    }

    "explain why those recipes keep it: without it the provider would use the default" in {
      window("https://api.together.ai/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo") shouldBe Default
      window("https://api.x.ai/v1", "grok-4.7") shouldBe Default
    }

    "give the gateway example, which names its registry provider, Groq's window for the model" in {
      recipe(
        "internal-gateway",
        """internal-gateway {
          |  provider = "openai-compatible"
          |  baseUrl = "https://llm-gateway.internal.example/v1"   # not a known host, so name the registry provider
          |  model = "llama-3.1-8b-instant"
          |  registryProvider = "groq"                             # the window comes from groq/llama-3.1-8b-instant
          |}""".stripMargin
      ).contextWindow shouldBe 128000
    }
  }

  // ---------------------------------------------------------------------------------------------
  // registries built here: each rule against data the test controls
  // ---------------------------------------------------------------------------------------------

  private def entry(modelId: String, provider: String, maxInput: Option[Int]): ModelMetadata =
    ModelMetadata(
      modelId = modelId,
      provider = provider,
      mode = ModelMode.Chat,
      maxInputTokens = maxInput,
      maxOutputTokens = Some(4096),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

  /** Builds the section's config against a registry made of `entries`, through the provider's own `buildConfig`. */
  private def build(baseUrl: String, model: String, extras: Map[String, String], entries: ModelMetadata*) =
    val resolver = ContextWindowResolver(ModelRegistryService.fromModels(entries.toList))
    val raw = RawNamedProviderSection(
      provider = Some("openai-compatible"),
      model = Some(model),
      baseUrl = Some(baseUrl),
      apiKey = None,
      extras = extras
    )
    for
      named  <- NamedProviderConfigValidator.validate(ProviderName("t"), raw)
      config <- OpenAICompatibleProvider.buildConfig("t", named)(using resolver)
    yield generic(Right(config)).contextWindow

  "buildConfig, against a registry of two providers that list the same model name" should {

    val entries = Seq(
      entry("groq/shared-model", "groq", Some(111111)),
      entry("together_ai/shared-model", "together_ai", Some(222222))
    )

    "use the entry of the provider the host names" in {
      build("https://api.groq.com/openai/v1", "shared-model", Map.empty, entries*) shouldBe Right(111111)
      build("https://api.together.xyz/v1", "shared-model", Map.empty, entries*) shouldBe Right(222222)
    }

    "use the entry of the explicit provider, whichever the host names" in {
      build(
        "https://api.groq.com/openai/v1",
        "shared-model",
        Map("registryProvider" -> "together_ai"),
        entries*
      ) shouldBe Right(222222)
    }

    "let a configured contextWindow beat both" in {
      build(
        "https://api.groq.com/openai/v1",
        "shared-model",
        Map("registryProvider" -> "together_ai", "contextWindow" -> "999"),
        entries*
      ) shouldBe Right(999)
    }
  }

  "buildConfig" should {

    "never take another provider's entry for the same model name" in {
      // The registry knows `only-here` only under openrouter; the host says groq, so there is nothing to take.
      build(
        "https://api.groq.com/openai/v1",
        "only-here",
        Map.empty,
        entry("openrouter/only-here", "openrouter", Some(65536))
      ) shouldBe Right(Default)
    }

    "not match part of a model name, as the registry's own lookup would" in {
      build(
        "https://api.groq.com/openai/v1",
        "llama-3.1",
        Map.empty,
        entry("groq/llama-3.1-8b-instant", "groq", Some(128000))
      ) shouldBe Right(Default)
    }

    "take the default when the registry has no entries at all" in {
      build("https://api.groq.com/openai/v1", "any-model", Map.empty) shouldBe Right(Default)
    }

    "ignore a registry window below the default, which is often a placeholder" in {
      build(
        "https://api.perplexity.ai",
        "small-model",
        Map.empty,
        entry("perplexity/small-model", "perplexity", Some(4096))
      ) shouldBe Right(Default)
      build(
        "https://llm-gateway.internal.example/v1",
        "small-model",
        Map("registryProvider" -> "perplexity"),
        entry("perplexity/small-model", "perplexity", Some(4096))
      ) shouldBe Right(Default)
    }

    "take a registry window equal to the default" in {
      build(
        "https://api.groq.com/openai/v1",
        "edge-model",
        Map.empty,
        entry("groq/edge-model", "groq", Some(Default))
      ) shouldBe Right(Default)
    }
  }

  "the generic provider's declared keys" should {

    "include registryProvider, optional, with a description that names the inference" in {
      val key = OpenAICompatibleProvider.configSpec.extras
        .find(_.name == OpenAICompatibleProvider.RegistryProviderKey)
        .getOrElse(fail("registryProvider is not declared"))

      key.required shouldBe false
      key.description should include("model registry")
      key.description should include("baseUrl")
    }

    "infer from the exact hosts only" in {
      val infer = (url: String) => OpenAICompatibleProvider.inferRegistryProvider(url)

      infer("https://api.groq.com/openai/v1") shouldBe Some("groq")
      infer("https://api.together.xyz/v1") shouldBe Some("together_ai")
      infer("https://api.together.ai/v1") shouldBe Some("together_ai")
      infer("https://api.fireworks.ai/inference/v1") shouldBe Some("fireworks_ai")
      infer("https://api.x.ai/v1") shouldBe Some("xai")
      infer("https://api.perplexity.ai") shouldBe Some("perplexity")
      infer("https://api.openai.com/v1") shouldBe None
      infer("not a url at all") shouldBe None
      infer("") shouldBe None
      infer("/relative/path") shouldBe None
    }
  }
