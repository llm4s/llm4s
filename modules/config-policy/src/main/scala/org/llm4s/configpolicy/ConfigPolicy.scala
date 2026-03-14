package org.llm4s.configpolicy

/**
 * Policy-as-code rules for LLM provider/model configuration.
 *
 * Use with [[ConfigPolicyRunner]] to validate [[org.llm4s.llmconnect.config.ProviderConfig]]
 * (loaded via [[org.llm4s.config.Llm4sConfig.providerFrom]]) against allowed providers,
 * models, token limits, and base URL/region requirements.
 *
 * @param allowedProviders       Provider names allowed (e.g. "openai", "anthropic", "ollama").
 *                               Empty means no restriction.
 * @param allowedModelPatterns   Regex patterns for allowed `provider/model` (e.g. "openai/gpt-4o-mini.*").
 *                               Empty means no restriction.
 * @param maxContextWindow        Cap on context window; None = no cap.
 * @param maxReserveCompletion    Cap on reserve completion tokens; None = no cap.
 * @param requiredBaseUrlPattern  Regex that base URL or endpoint must match (e.g. ".*\\.azure\\.com.*");
 *                               None = no requirement.
 */
final case class ConfigPolicy(
  allowedProviders: Set[String],
  allowedModelPatterns: List[String],
  maxContextWindow: Option[Int],
  maxReserveCompletion: Option[Int],
  requiredBaseUrlPattern: Option[String]
) {

  def withAllowedProviders(providers: String*): ConfigPolicy =
    copy(allowedProviders = providers.toSet.map(_.toLowerCase))

  def withAllowedModelPatterns(patterns: String*): ConfigPolicy =
    copy(allowedModelPatterns = patterns.toList)

  def withMaxContextWindow(max: Int): ConfigPolicy =
    copy(maxContextWindow = Some(max))

  def withMaxReserveCompletion(max: Int): ConfigPolicy =
    copy(maxReserveCompletion = Some(max))

  def withRequiredBaseUrlPattern(pattern: String): ConfigPolicy =
    copy(requiredBaseUrlPattern = Some(pattern))
}

object ConfigPolicy {

  /** No restrictions. */
  val permissive: ConfigPolicy = ConfigPolicy(
    allowedProviders = Set.empty,
    allowedModelPatterns = Nil,
    maxContextWindow = None,
    maxReserveCompletion = None,
    requiredBaseUrlPattern = None
  )

  /**
   * Dev sandbox: allow common providers and Ollama; cap context to avoid runaway cost.
   */
  val devSandbox: ConfigPolicy = ConfigPolicy(
    allowedProviders = Set("openai", "anthropic", "ollama", "gemini", "deepseek"),
    allowedModelPatterns = Nil,
    maxContextWindow = Some(128000),
    maxReserveCompletion = Some(8192),
    requiredBaseUrlPattern = None
  )

  /**
   * Safe defaults for public cloud prod: restrict to known providers and models,
   * cap tokens, optional region/base URL requirement.
   */
  val prodSafeDefaults: ConfigPolicy = ConfigPolicy(
    allowedProviders = Set("openai", "anthropic", "azure", "gemini", "deepseek"),
    allowedModelPatterns = List(
      "openai/gpt-4o",
      "openai/gpt-4o-mini",
      "openai/gpt-4-turbo",
      "anthropic/claude-3-5-sonnet.*",
      "anthropic/claude-sonnet-4.*",
      "gemini/gemini-2\\.0.*",
      "deepseek/deepseek-chat",
      "azure/.*"
    ),
    maxContextWindow = Some(128000),
    maxReserveCompletion = Some(4096),
    requiredBaseUrlPattern = None
  )

  /**
   * Resolve policy preset by name.
   *
   * Supported: "permissive", "dev", "dev-sandbox", "prod", "prod-safe".
   */
  def preset(name: String): Option[ConfigPolicy] =
    name.toLowerCase match {
      case "permissive" | "none" => Some(permissive)
      case "dev" | "dev-sandbox" => Some(devSandbox)
      case "prod" | "prod-safe"  => Some(prodSafeDefaults)
      case _                     => None
    }
}
