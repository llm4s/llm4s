package org.llm4s.configpolicy

import org.llm4s.config.ApiKeySource
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.ProviderModelTypes.ProviderId

import scala.util.{ Failure, Success, Try }
import scala.util.matching.Regex

/**
 * Patterns (`allowedModelPatterns`, `requiredBaseUrlPattern*`) must match the '''whole''' value: `openai/gpt-4o`
 * allows `openai/gpt-4o` and not `openai/gpt-4o-mini` or the dated snapshot `openai/gpt-4o-2024-08-06` (write
 * `openai/gpt-4o(-.*)?` for those), and a base-URL pin cannot be satisfied by a lookalike such as
 * `https://api.openai.com.evil.example`.
 *
 * '''End a prefix pin with `/.*`, never a bare `.*`.''' The pin `https://api\.openai\.com.*` accepts
 * `https://api.openai.com.evil.example/v1`, `https://api.openai.com:x@evil.example/` and
 * `https://api.openai.com./v1`; `https://api\.openai\.com/.*` rejects all three.
 *
 * Provider names (in `withAllowedProviders` and the per-provider caps and pins) are canonicalised the way
 * provider ids are: trimmed, lower-cased with `Locale.ROOT`, and an alias such as `google` folded onto the
 * provider that owns it (`gemini`). A per-provider key that names no registered provider and is not in
 * `allowedProviders` is reported as an `unknownProvider` violation rather than ignored, since a typo
 * there would otherwise leave the provider uncapped or unpinned.
 *
 * @param maxContextWindowByProvider per-provider caps, which take precedence over the environment-wide cap
 * @param requiredBaseUrlPatternByProvider per-provider base-URL pins, which '''replace''' the
 *                            environment-wide pin for that provider (they do not add to it, so a loose
 *                            provider pin weakens a strict global one): one endpoint list per provider
 *                            rather than one for all
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
  ownApiKeyRequiredIn: Set[CatalogEnvironment] = Set.empty,
  maxContextWindowByProvider: Map[(CatalogEnvironment, String), Int] = Map.empty,
  requiredBaseUrlPatternByProvider: Map[(CatalogEnvironment, String), String] = Map.empty
) {
  def withAllowedProviders(values: String*): ConfigPolicy =
    copy(allowedProviders = values.map(canonical).toSet)

  def withAllowedModelPatterns(values: String*): ConfigPolicy =
    copy(allowedModelPatterns = values.toList)

  def withMaxContextWindow(environment: CatalogEnvironment, max: Int): ConfigPolicy =
    copy(maxContextWindowByEnv = maxContextWindowByEnv + (environment -> max))

  def withMaxContextWindow(environment: CatalogEnvironment, provider: String, max: Int): ConfigPolicy =
    copy(maxContextWindowByProvider = maxContextWindowByProvider + ((environment, canonical(provider)) -> max))

  def withRequiredBaseUrlPattern(environment: CatalogEnvironment, provider: String, pattern: String): ConfigPolicy =
    copy(requiredBaseUrlPatternByProvider =
      requiredBaseUrlPatternByProvider + ((environment, canonical(provider)) -> pattern)
    )

  def withRequiredBaseUrlPattern(environment: CatalogEnvironment, pattern: String): ConfigPolicy =
    copy(requiredBaseUrlPatternByEnv = requiredBaseUrlPatternByEnv + (environment -> pattern))

  /** Requires, in `environment`, that every chat section set its own `apiKey`; see [[ownApiKeyRequiredIn]]. */
  def withOwnApiKeyRequired(environment: CatalogEnvironment): ConfigPolicy =
    copy(ownApiKeyRequiredIn = ownApiKeyRequiredIn + environment)

  /** Trimmed and lower-cased with `Locale.ROOT`: a Turkish default locale must not turn `OpenAI` into `openaı`. */
  private def canonical(provider: String): String = ProviderId(provider).asString
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
      .withMaxContextWindow(CatalogEnvironment.Dev, 1048576)

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
      // Environment-wide fallback for a provider with no entry below (one added with `withAllowedProviders`,
      // such as the generic `openai-compatible`): the largest window a mainstream hosted model offers today.
      // The few models above it (Llama 4 Scout, 10M) must be allowed with an explicit per-provider cap.
      .withMaxContextWindow(CatalogEnvironment.Prod, 1048576)
      // Caps follow each provider's current models (Gemini 1M, Claude 200k, DeepSeek 131k, GPT-4o 128k),
      // so an allowed model is not rejected for its own native window.
      .withMaxContextWindow(CatalogEnvironment.Prod, "openai", 128000)
      .withMaxContextWindow(CatalogEnvironment.Prod, "azure", 128000)
      .withMaxContextWindow(CatalogEnvironment.Prod, "anthropic", 200000)
      .withMaxContextWindow(CatalogEnvironment.Prod, "gemini", 1048576)
      .withMaxContextWindow(CatalogEnvironment.Prod, "deepseek", 131072)
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

  /**
   * @param registry resolves provider aliases and says which providers exist; the classpath's by default
   */
  def check(
    config: ProviderConfig,
    policy: ConfigPolicy,
    environment: CatalogEnvironment,
    registry: ProviderRegistry = ProviderRegistry.default
  ): List[PolicyViolation] = {
    val provider = providerName(config)
    val fullSpec = providerModel(config)

    def canonical(name: String): String = registry.canonicalId(name).asString

    val allowed = policy.allowedProviders.map(canonical)
    val capsHere =
      policy.maxContextWindowByProvider.collect {
        case ((env, name), max) if env == environment =>
          canonical(name) -> max
      }
    val pinsHere =
      policy.requiredBaseUrlPatternByProvider.collect {
        case ((env, name), pin) if env == environment =>
          canonical(name) -> pin
      }

    // A key naming nothing never matches any config, so a typo would leave its provider uncapped or
    // unpinned without a word: report it instead.
    val unknownKeyViolations =
      (capsHere.keys.map(_ -> "cap") ++ pinsHere.keys.map(_ -> "base-URL pin")).toList.distinct.sorted
        .filterNot { case (name, _) => registry.find(ProviderId(name)).isDefined || allowed.contains(name) }
        .map { case (name, what) =>
          PolicyViolation(
            "unknownProvider",
            s"Policy sets a $what for provider '$name', which no registered provider claims and which is not " +
              "in allowedProviders: a mistyped provider would be left unchecked"
          )
        }

    val providerViolations =
      if (policy.allowedProviders.nonEmpty && !allowed(provider)) {
        List(PolicyViolation("allowedProviders", s"Provider '$provider' is not allowed"))
      } else Nil

    val modelViolations =
      if (policy.allowedModelPatterns.isEmpty) Nil
      else
        compileRegexList(policy.allowedModelPatterns, "allowedModelPatterns") match {
          case Left(violations) => violations
          case Right(compiled) =>
            if (compiled.exists(_.pattern.matcher(fullSpec).matches())) Nil
            else
              List(
                PolicyViolation(
                  "allowedModels",
                  s"Model '$fullSpec' does not match configured allowlist"
                )
              )
        }

    val maxContextViolations =
      capsHere
        .get(provider)
        .orElse(policy.maxContextWindowByEnv.get(environment))
        .filter(max => config.contextWindow > max)
        .map(max => PolicyViolation("maxContextWindow", s"contextWindow ${config.contextWindow} exceeds $max"))
        .toList

    val baseUrlViolations =
      pinsHere
        .get(provider)
        .orElse(policy.requiredBaseUrlPatternByEnv.get(environment))
        .toList
        .flatMap { rawPattern =>
          compileRegexList(List(rawPattern), "requiredBaseUrl") match {
            case Left(violations) => violations
            case Right(compiled) =>
              val pattern = compiled.head
              baseUrlOrEndpoint(config) match {
                case Some(url) if pattern.pattern.matcher(url).matches() => Nil
                case Some(_) =>
                  List(PolicyViolation("requiredBaseUrl", s"Endpoint must match $rawPattern"))
                case None =>
                  List(PolicyViolation("requiredBaseUrl", "No endpoint/baseUrl found"))
              }
          }
        }

    unknownKeyViolations ++ providerViolations ++ modelViolations ++ maxContextViolations ++ baseUrlViolations
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
