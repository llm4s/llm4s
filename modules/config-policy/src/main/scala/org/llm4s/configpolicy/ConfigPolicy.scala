package org.llm4s.configpolicy

import org.llm4s.llmconnect.config._

import scala.util.matching.Regex

final case class ConfigPolicy(
  allowedProviders: Set[String] = Set.empty,
  allowedModelPatterns: List[String] = Nil,
  maxContextWindowByEnv: Map[CatalogEnvironment, Int] = Map.empty,
  requiredBaseUrlPatternByEnv: Map[CatalogEnvironment, String] = Map.empty
) {
  def withAllowedProviders(values: String*): ConfigPolicy =
    copy(allowedProviders = values.map(_.toLowerCase).toSet)

  def withAllowedModelPatterns(values: String*): ConfigPolicy =
    copy(allowedModelPatterns = values.toList)

  def withMaxContextWindow(environment: CatalogEnvironment, max: Int): ConfigPolicy =
    copy(maxContextWindowByEnv = maxContextWindowByEnv + (environment -> max))

  def withRequiredBaseUrlPattern(environment: CatalogEnvironment, pattern: String): ConfigPolicy =
    copy(requiredBaseUrlPatternByEnv = requiredBaseUrlPatternByEnv + (environment -> pattern))
}

object ConfigPolicy {
  val permissive: ConfigPolicy = ConfigPolicy()

  val devSandbox: ConfigPolicy =
    ConfigPolicy()
      .withAllowedProviders("openai", "anthropic", "ollama", "gemini", "deepseek")
      .withMaxContextWindow(CatalogEnvironment.Dev, 128000)

  val prodSafeDefaults: ConfigPolicy =
    ConfigPolicy()
      .withAllowedProviders("openai", "anthropic", "azure", "gemini", "deepseek")
      .withAllowedModelPatterns(
        "openai/gpt-4o",
        "openai/gpt-4o-mini",
        "anthropic/claude-3-5-sonnet.*",
        "azure/.*",
        "gemini/gemini-2\\..*",
        "deepseek/deepseek-chat"
      )
      .withMaxContextWindow(CatalogEnvironment.Prod, 128000)

  def preset(name: String): Option[ConfigPolicy] =
    name.toLowerCase match {
      case "permissive" | "none" => Some(permissive)
      case "dev" | "dev-sandbox" => Some(devSandbox)
      case "prod" | "prod-safe"  => Some(prodSafeDefaults)
      case _                      => None
    }
}

final case class PolicyViolation(rule: String, message: String)

object ConfigPolicyEngine {
  def providerName(config: ProviderConfig): String =
    config match {
      case _: OpenAIConfig    => "openai"
      case _: AzureConfig     => "azure"
      case _: AnthropicConfig => "anthropic"
      case _: OllamaConfig    => "ollama"
      case _: ZaiConfig       => "zai"
      case _: GeminiConfig    => "gemini"
      case _: DeepSeekConfig  => "deepseek"
      case _: CohereConfig    => "cohere"
      case _: MistralConfig   => "mistral"
    }

  def providerModel(config: ProviderConfig): String =
    s"${providerName(config)}/${config.model}"

  def baseUrlOrEndpoint(config: ProviderConfig): Option[String] =
    config match {
      case c: OpenAIConfig    => Some(c.baseUrl)
      case c: AzureConfig     => Some(c.endpoint)
      case c: AnthropicConfig => Some(c.baseUrl)
      case c: OllamaConfig    => Some(c.baseUrl)
      case c: ZaiConfig       => Some(c.baseUrl)
      case c: GeminiConfig    => Some(c.baseUrl)
      case c: DeepSeekConfig  => Some(c.baseUrl)
      case c: CohereConfig    => Some(c.baseUrl)
      case c: MistralConfig   => Some(c.baseUrl)
    }

  def check(config: ProviderConfig, policy: ConfigPolicy, environment: CatalogEnvironment): List[PolicyViolation] = {
    val provider = providerName(config)
    val fullSpec = providerModel(config)

    val providerViolations =
      if (policy.allowedProviders.nonEmpty && !policy.allowedProviders(provider)) {
        List(PolicyViolation("allowedProviders", s"Provider '$provider' is not allowed"))
      } else Nil

    val modelViolations =
      if (policy.allowedModelPatterns.nonEmpty) {
        val matches = policy.allowedModelPatterns.exists { pattern =>
          new Regex(pattern).findFirstIn(fullSpec).isDefined
        }
        if (matches) Nil
        else List(PolicyViolation("allowedModels", s"Model '$fullSpec' does not match configured allowlist"))
      } else Nil

    val maxContextViolations =
      policy.maxContextWindowByEnv
        .get(environment)
        .filter(max => config.contextWindow > max)
        .map(max => PolicyViolation("maxContextWindow", s"contextWindow ${config.contextWindow} exceeds $max"))
        .toList

    val baseUrlViolations =
      policy.requiredBaseUrlPatternByEnv
        .get(environment)
        .toList
        .flatMap { pattern =>
          baseUrlOrEndpoint(config) match {
            case Some(url) if new Regex(pattern).findFirstIn(url).isDefined => Nil
            case Some(_)                                                     => List(PolicyViolation("requiredBaseUrl", s"Endpoint must match $pattern"))
            case None                                                        => List(PolicyViolation("requiredBaseUrl", "No endpoint/baseUrl found"))
          }
        }

    providerViolations ++ modelViolations ++ maxContextViolations ++ baseUrlViolations
  }
}

