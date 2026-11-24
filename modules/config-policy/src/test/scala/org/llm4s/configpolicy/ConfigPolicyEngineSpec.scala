package org.llm4s.configpolicy

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OllamaConfig, OpenAICompatibleConfig, OpenAIConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConfigPolicyEngineSpec extends AnyWordSpec with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get)

  "ConfigPolicyEngine.check" should {
    "pass for dev ollama config under dev policy" in {
      val cfg = OllamaConfig.fromValues(ModelName("llama3"), BaseUrl("http://localhost:11434")).value
      val violations = ConfigPolicyEngine.check(
        cfg,
        ConfigPolicy.devSandbox,
        CatalogEnvironment.Dev
      )
      violations shouldBe empty
    }

    "allow the generic openai-compatible provider under the dev preset" in {
      val cfg = OpenAICompatibleConfig.fromValues(ModelName("qwen"), BaseUrl("http://localhost:8000/v1")).value
      ConfigPolicyEngine.check(cfg, ConfigPolicy.devSandbox, CatalogEnvironment.Dev) shouldBe empty
    }

    "reject the generic openai-compatible provider under the prod preset unless explicitly allowed" in {
      val cfg = OpenAICompatibleConfig.fromValues(ModelName("gpt-4o-mini"), BaseUrl("https://gateway.example/v1")).value
      ConfigPolicyEngine.check(cfg, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod) should contain(
        PolicyViolation("allowedProviders", "Provider 'openai-compatible' is not allowed")
      )

      val optedIn = ConfigPolicy.prodSafeDefaults
        .withAllowedProviders("openai", "anthropic", "azure", "gemini", "deepseek", "openai-compatible")
        .withAllowedModelPatterns("openai-compatible/gpt-4o-mini")
      ConfigPolicyEngine.check(cfg, optedIn, CatalogEnvironment.Prod) shouldBe empty
    }

    "fail when provider is not in allowlist" in {
      val cfg =
        OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("test-key"), BaseUrl("https://api.openai.com/v1")).value
      val policy     = ConfigPolicy.permissive.withAllowedProviders("anthropic")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("allowedProviders")
    }

    "pass when model matches an allowlisted pattern" in {
      val cfg = OpenAIConfig
        .fromValues(ModelName("gpt-4o-mini"), Secret("test-key"), BaseUrl("https://api.openai.com/v1"))
        .value
      val policy     = ConfigPolicy.permissive.withAllowedModelPatterns("openai/gpt-4o-mini")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations shouldBe empty
    }

    "fail when model does not match any pattern" in {
      val cfg = OpenAIConfig
        .fromValues(ModelName("gpt-3.5-turbo"), Secret("test-key"), BaseUrl("https://api.openai.com/v1"))
        .value
      val policy     = ConfigPolicy.permissive.withAllowedModelPatterns("openai/gpt-4o-mini")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("allowedModels")
    }

    "fail when context window exceeds policy max for environment" in {
      val cfg =
        OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("test-key"), BaseUrl("https://api.openai.com/v1")).value
      val policy     = ConfigPolicy.permissive.withMaxContextWindow(CatalogEnvironment.Prod, 1000)
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("maxContextWindow")
    }

    "fail when base URL does not match required pattern" in {
      val cfg =
        OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("test-key"), BaseUrl("https://example.com/v1")).value
      val policy =
        ConfigPolicy.permissive.withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com.*")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("requiredBaseUrl")
    }

    "pass when base URL matches required pattern" in {
      val cfg =
        OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("test-key"), BaseUrl("https://api.openai.com/v1")).value
      val policy =
        ConfigPolicy.permissive.withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com.*")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations shouldBe empty
    }

    "surface invalid model regex in policy as a violation" in {
      val cfg =
        OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("test-key"), BaseUrl("https://api.openai.com/v1")).value
      val policy     = ConfigPolicy.permissive.withAllowedModelPatterns("[invalid")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("allowedModelPatterns")
    }

    "match model patterns against the whole value, so a pin is not a prefix" in {
      val mini =
        OpenAIConfig.fromValues(ModelName("gpt-4o-mini"), Secret("k"), BaseUrl("https://api.openai.com/v1")).value
      val policy = ConfigPolicy.permissive.withAllowedModelPatterns("openai/gpt-4o")
      ConfigPolicyEngine.check(mini, policy, CatalogEnvironment.Prod).map(_.rule) should contain("allowedModels")
      val exact = OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("k"), BaseUrl("https://api.openai.com/v1")).value
      ConfigPolicyEngine.check(exact, policy, CatalogEnvironment.Prod) shouldBe empty
    }

    "not let a lookalike host satisfy an anchored base-URL pin" in {
      val policy = ConfigPolicy.permissive
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com/v1")
      Seq(
        "https://api.openai.com/v1.evil.example",
        "https://api.openai.com.evil.example/v1",
        "https://evil.example/?u=https://api.openai.com/v1"
      ).foreach { url =>
        val cfg = OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("k"), BaseUrl(url)).value
        ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod).map(_.rule) should contain("requiredBaseUrl")
      }
    }

    "apply a per-provider base-URL pin in place of the environment-wide one" in {
      val policy = ConfigPolicy.permissive
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com/v1")
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "openai-compatible", "https://api\\.groq\\.com/openai/v1")
      val groq = OpenAICompatibleConfig.fromValues(ModelName("m"), BaseUrl("https://api.groq.com/openai/v1")).value
      ConfigPolicyEngine.check(groq, policy, CatalogEnvironment.Prod) shouldBe empty
      val other = OpenAICompatibleConfig.fromValues(ModelName("m"), BaseUrl("https://api.openai.com/v1")).value
      ConfigPolicyEngine.check(other, policy, CatalogEnvironment.Prod).map(_.rule) should contain("requiredBaseUrl")
      val openai = OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("k"), BaseUrl("https://api.openai.com/v1")).value
      ConfigPolicyEngine.check(openai, policy, CatalogEnvironment.Prod) shouldBe empty
    }

    "let a per-provider context cap override the environment-wide one" in {
      val policy = ConfigPolicy.permissive
        .withMaxContextWindow(CatalogEnvironment.Prod, 1000)
        .withMaxContextWindow(CatalogEnvironment.Prod, "openai", 200000)
      val cfg = OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("k"), BaseUrl("https://api.openai.com/v1")).value
      ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod) shouldBe empty
      val ollama = OllamaConfig.fromValues(ModelName("llama3"), BaseUrl("http://localhost:11434")).value
      ConfigPolicyEngine.check(ollama, policy, CatalogEnvironment.Prod).map(_.rule) should contain("maxContextWindow")
    }

    def loaded(section: String) =
      org.llm4s.config.Llm4sConfig
        .providerFrom(
          pureconfig.ConfigSource.string(s"llm4s { defaultProvider = \"main\"\n providers { main { $section } } }")
        )
        .value

    "accept the model ids the docs use, at their native context windows (#2885)" in {
      val sections = Seq(
        """provider = "openai", model = "gpt-5.6-luna", apiKey = "k"""",
        """provider = "openai", model = "gpt-5.6-terra", apiKey = "k"""",
        """provider = "openai", model = "gpt-6-luna", apiKey = "k"""",
        """provider = "anthropic", model = "claude-sonnet-5-5", apiKey = "k"""",
        """provider = "anthropic", model = "claude-haiku-5-5", apiKey = "k"""",
        """provider = "anthropic", model = "claude-opus-4-8", apiKey = "k"""",
        """provider = "gemini", model = "gemini-3.8-flash", apiKey = "k"""",
        """provider = "bedrock", model = "us.anthropic.claude-sonnet-5-5", region = "us-east-1"""",
        """provider = "deepseek", model = "deepseek-chat", apiKey = "k""""
      )
      sections.foreach { section =>
        val cfg = loaded(section)
        withClue(s"$section (contextWindow ${cfg.contextWindow})") {
          ConfigPolicyEngine.check(cfg, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod) shouldBe empty
        }
      }
    }

    "reject retired model generations under the prod preset" in {
      Seq(
        """provider = "openai", model = "gpt-4o-mini", apiKey = "k"""",
        """provider = "openai", model = "gpt-4.1", apiKey = "k"""",
        """provider = "anthropic", model = "claude-3-5-sonnet-20241022", apiKey = "k"""",
        """provider = "anthropic", model = "claude-sonnet-4-5", apiKey = "k"""",
        """provider = "gemini", model = "gemini-2.5-pro", apiKey = "k"""",
        """provider = "bedrock", model = "anthropic.claude-3-5-sonnet-20241022-v2:0", region = "us-east-1""""
      ).foreach { section =>
        withClue(section) {
          ConfigPolicyEngine
            .check(loaded(section), ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod)
            .map(_.rule) shouldBe List("allowedModels")
        }
      }
    }

    "match each model family as a whole, not as a prefix" in {
      val families = ConfigPolicy.ModelFamilies
      Seq("gpt-5", "gpt-5.6-luna", "gpt-6.1-terra").foreach(_.matches(families.OpenAI) shouldBe true)
      Seq("gpt-4o", "gpt-50", "xgpt-5").foreach(_.matches(families.OpenAI) shouldBe false)
      Seq("claude-fable-5", "claude-sonnet-5-5", "claude-sonnet-4-6", "claude-haiku-4-5").foreach(
        _.matches(families.Anthropic) shouldBe true
      )
      Seq("claude-3-5-sonnet-latest", "claude-sonnet-4-5", "claude-opus-4-1").foreach(
        _.matches(families.Anthropic) shouldBe false
      )
      Seq("us.anthropic.claude-sonnet-5-5", "anthropic.claude-opus-4-8-v1:0", "global.anthropic.claude-haiku-5-5")
        .foreach(_.matches(families.BedrockAnthropic) shouldBe true)
      Seq("gemini-3.8-flash", "gemini-3-pro-preview").foreach(_.matches(families.Gemini) shouldBe true)
      Seq("gemini-2.5-pro", "gemini-30").foreach(_.matches(families.Gemini) shouldBe false)
    }

    "cap each prod provider no lower than its largest current model's window in the bundled metadata" in {
      val registry = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
      val caps     = ConfigPolicy.prodSafeDefaults.maxContextWindowByProvider
      Seq(
        "openai"    -> "gpt-5.6-luna",
        "anthropic" -> "claude-sonnet-5-5",
        "gemini"    -> "gemini-3.8-flash",
        "deepseek"  -> "deepseek-chat"
      ).foreach { case (provider, model) =>
        val window = registry
          .lookup(org.llm4s.types.ProviderModelTypes.ProviderId(provider), ModelName(model))
          .toOption
          .flatMap(_.maxInputTokens)
          .get
        withClue(s"$provider/$model") {
          caps((CatalogEnvironment.Prod, provider)) should be >= window
        }
      }
    }

    "accept the documented Groq and xAI recipes under the dev preset" in {
      Seq("https://api.groq.com/openai/v1" -> 131072, "https://api.x.ai/v1" -> 500000).foreach { case (url, window) =>
        val cfg = OpenAICompatibleConfig.fromValues(ModelName("m"), BaseUrl(url), contextWindow = Some(window)).value
        ConfigPolicyEngine.check(cfg, ConfigPolicy.devSandbox, CatalogEnvironment.Dev) shouldBe empty
      }
    }
  }
}
