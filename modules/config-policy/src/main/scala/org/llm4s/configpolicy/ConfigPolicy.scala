package org.llm4s.configpolicy

import org.llm4s.llmconnect.config._

import scala.util.{ Failure, Success, Try }
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
      case _                     => None
    }
}

final case class PolicyViolation(rule: String, message: String)

object ConfigPolicyEngine {

  private def compileRegexList(
    patterns: List[String],
    rule: String
  ): Either[List[PolicyViolation], List[Regex]] =
    patterns.zipWithIndex.foldLeft[Either[List[PolicyViolation], List[Regex]]](Right(Nil)) {
      case (Left(errs), _) => Left(errs)
      case (Right(acc), (raw, idx)) =>
        Try(new Regex(raw)) match {
          case Success(r) => Right(acc :+ r)
          case Failure(e) =>
            Left(
              List(
                PolicyViolation(
                  rule,
                  s"Invalid regex at index $idx ('$raw'): ${e.getMessage}"
                )
              )
            )
        }
    }

  /** `@unchecked`: keep a default branch for future [[ProviderConfig]] subtypes without failing -Werror. */
  def providerName(config: ProviderConfig): String =
    (config: @unchecked) match {
      case _: OpenAIConfig    => "openai"
      case _: AzureConfig     => "azure"
      case _: AnthropicConfig => "anthropic"
      case _: OllamaConfig    => "ollama"
      case _: ZaiConfig       => "zai"
      case _: GeminiConfig    => "gemini"
      case _: DeepSeekConfig  => "deepseek"
      case _: CohereConfig    => "cohere"
      case _: MistralConfig   => "mistral"
      case other =>
        val simple = other.getClass.getSimpleName.stripSuffix("Config")
        if (simple.isEmpty) "unknown"
        else simple.toLowerCase
    }

  def providerModel(config: ProviderConfig): String =
    s"${providerName(config)}/${config.model}"

  def baseUrlOrEndpoint(config: ProviderConfig): Option[String] =
    (config: @unchecked) match {
      case c: OpenAIConfig    => Some(c.baseUrl)
      case c: AzureConfig     => Some(c.endpoint)
      case c: AnthropicConfig => Some(c.baseUrl)
      case c: OllamaConfig    => Some(c.baseUrl)
      case c: ZaiConfig       => Some(c.baseUrl)
      case c: GeminiConfig    => Some(c.baseUrl)
      case c: DeepSeekConfig  => Some(c.baseUrl)
      case c: CohereConfig    => Some(c.baseUrl)
      case c: MistralConfig   => Some(c.baseUrl)
      case _                  => None
    }

  def check(config: ProviderConfig, policy: ConfigPolicy, environment: CatalogEnvironment): List[PolicyViolation] = {
    val provider = providerName(config)
    val fullSpec = providerModel(config)

    val providerViolations =
      if (policy.allowedProviders.nonEmpty && !policy.allowedProviders(provider)) {
        List(PolicyViolation("allowedProviders", s"Provider '$provider' is not allowed"))
      } else Nil

    val modelViolations =
      if (policy.allowedModelPatterns.isEmpty) Nil
      else
        compileRegexList(policy.allowedModelPatterns, "allowedModelPatterns") match {
          case Left(violations) => violations
          case Right(compiled) =>
            if (compiled.exists(_.findFirstIn(fullSpec).isDefined)) Nil
            else
              List(
                PolicyViolation(
                  "allowedModels",
                  s"Model '$fullSpec' does not match configured allowlist"
                )
              )
        }

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
        .flatMap { rawPattern =>
          compileRegexList(List(rawPattern), "requiredBaseUrl") match {
            case Left(violations) => violations
            case Right(compiled) =>
              val pattern = compiled.head
              baseUrlOrEndpoint(config) match {
                case Some(url) if pattern.findFirstIn(url).isDefined => Nil
                case Some(_) =>
                  List(PolicyViolation("requiredBaseUrl", s"Endpoint must match $rawPattern"))
                case None =>
                  List(PolicyViolation("requiredBaseUrl", "No endpoint/baseUrl found"))
              }
          }
        }

    providerViolations ++ modelViolations ++ maxContextViolations ++ baseUrlViolations
  }
}
