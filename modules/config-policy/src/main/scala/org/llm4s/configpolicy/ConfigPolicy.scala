package org.llm4s.configpolicy

import org.llm4s.config.ApiKeySource
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.llm4s.llmconnect.config._

import scala.util.{ Failure, Success, Try }
import scala.util.matching.Regex

/**
 * @param ownApiKeyRequiredIn environments in which every named chat section whose provider needs
 *                            a key must set its own `apiKey`, rather than inherit its vendor's
 *                            shared `llm4s.credentials.<providerId>.apiKey`. A section meant for
 *                            a second account that forgets its key would otherwise bill the
 *                            default account without a word.
 */
final case class ConfigPolicy(
  allowedProviders: Set[String] = Set.empty,
  allowedModelPatterns: List[String] = Nil,
  maxContextWindowByEnv: Map[CatalogEnvironment, Int] = Map.empty,
  requiredBaseUrlPatternByEnv: Map[CatalogEnvironment, String] = Map.empty,
  ownApiKeyRequiredIn: Set[CatalogEnvironment] = Set.empty
) {
  def withAllowedProviders(values: String*): ConfigPolicy =
    copy(allowedProviders = values.map(_.toLowerCase).toSet)

  def withAllowedModelPatterns(values: String*): ConfigPolicy =
    copy(allowedModelPatterns = values.toList)

  def withMaxContextWindow(environment: CatalogEnvironment, max: Int): ConfigPolicy =
    copy(maxContextWindowByEnv = maxContextWindowByEnv + (environment -> max))

  def withRequiredBaseUrlPattern(environment: CatalogEnvironment, pattern: String): ConfigPolicy =
    copy(requiredBaseUrlPatternByEnv = requiredBaseUrlPatternByEnv + (environment -> pattern))

  /** Requires, in `environment`, that every chat section set its own `apiKey`; see [[ownApiKeyRequiredIn]]. */
  def withOwnApiKeyRequired(environment: CatalogEnvironment): ConfigPolicy =
    copy(ownApiKeyRequiredIn = ownApiKeyRequiredIn + environment)
}

object ConfigPolicy {
  val permissive: ConfigPolicy = ConfigPolicy()

  /**
   * Dev: the common providers, plus the generic `openai-compatible` provider for local servers
   * (vLLM, LM Studio, llama.cpp) and experiments.
   */
  val devSandbox: ConfigPolicy =
    ConfigPolicy()
      .withAllowedProviders("openai", "anthropic", "ollama", "gemini", "deepseek", "openai-compatible")
      .withMaxContextWindow(CatalogEnvironment.Dev, 128000)

  /**
   * Prod: named providers with pinned model patterns. The generic `openai-compatible` provider is
   * deliberately '''not''' allowed: it can point at any endpoint, so production must opt in by
   * adding it (ideally with `withRequiredBaseUrlPattern` for the endpoints it may use).
   *
   * Every chat section must also set its own `apiKey` in prod (`withOwnApiKeyRequired`): which
   * account a section bills should be written down, not inherited from `OPENAI_API_KEY`.
   */
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
      .withOwnApiKeyRequired(CatalogEnvironment.Prod)

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

  def providerName(config: ProviderConfig): String = config.providerId.asString

  def providerModel(config: ProviderConfig): String =
    s"${providerName(config)}/${config.model}"

  def baseUrlOrEndpoint(config: ProviderConfig): Option[String] = config.endpointUrl

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

  /**
   * Checks where each named chat section's API key comes from (`Llm4sConfig.apiKeySources`).
   *
   * Where the policy requires it for `environment`, a section that sets no `apiKey` of its own -
   * and so would use its vendor's shared `llm4s.credentials.<providerId>.apiKey`, whether or not
   * that is set where the check runs - is a `ownApiKey` violation.
   */
  def checkApiKeySources(
    sources: Map[ProviderName, ApiKeySource],
    policy: ConfigPolicy,
    environment: CatalogEnvironment
  ): List[PolicyViolation] =
    if (!policy.ownApiKeyRequiredIn.contains(environment)) Nil
    else
      sources.toList
        .sortBy(_._1.asName)
        .collect { case (name, ApiKeySource.Credentials(path)) =>
          PolicyViolation(
            "ownApiKey",
            s"llm4s.providers.${name.asName} sets no apiKey, so it would use the shared $path; " +
              s"set llm4s.providers.${name.asName}.apiKey to the key for the account it should bill"
          )
        }
}
