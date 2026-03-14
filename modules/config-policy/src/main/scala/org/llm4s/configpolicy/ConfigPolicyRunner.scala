package org.llm4s.configpolicy

import org.llm4s.llmconnect.config._

import scala.util.matching.Regex

/**
 * Result of a single policy rule check.
 */
final case class PolicyViolation(rule: String, message: String) {
  override def toString: String = s"[$rule] $message"
}

/**
 * Runs [[ConfigPolicy]] checks against a loaded [[ProviderConfig]].
 */
object ConfigPolicyRunner {

  /**
   * Provider name for a given config (for policy matching).
   */
  def providerName(c: ProviderConfig): String =
    c match {
      case _: OpenAIConfig   => "openai"
      case _: AzureConfig    => "azure"
      case _: AnthropicConfig => "anthropic"
      case _: OllamaConfig   => "ollama"
      case _: ZaiConfig      => "zai"
      case _: GeminiConfig   => "gemini"
      case _: DeepSeekConfig => "deepseek"
      case _: CohereConfig   => "cohere"
      case _: MistralConfig  => "mistral"
    }

  /**
   * Base URL or endpoint for region/URL policy checks.
   */
  def baseUrlOrEndpoint(c: ProviderConfig): Option[String] =
    c match {
      case o: OpenAIConfig    => Some(o.baseUrl)
      case a: AzureConfig     => Some(a.endpoint)
      case a: AnthropicConfig => Some(a.baseUrl)
      case o: OllamaConfig    => Some(o.baseUrl)
      case z: ZaiConfig       => Some(z.baseUrl)
      case g: GeminiConfig    => Some(g.baseUrl)
      case d: DeepSeekConfig  => Some(d.baseUrl)
      case c: CohereConfig    => Some(c.baseUrl)
      case m: MistralConfig   => Some(m.baseUrl)
    }

  /**
   * Full provider/model spec (e.g. "openai/gpt-4o") for model-pattern matching.
   */
  def providerModelSpec(c: ProviderConfig): String = {
    val p = providerName(c)
    s"$p/${c.model}"
  }

  /**
   * Evaluate config against the policy; returns all violations (empty if compliant).
   */
  def check(config: ProviderConfig, policy: ConfigPolicy): List[PolicyViolation] = {
    var out: List[PolicyViolation] = Nil

    val provider = providerName(config)
    if (policy.allowedProviders.nonEmpty && !policy.allowedProviders(provider.toLowerCase)) {
      out = PolicyViolation(
        "allowedProviders",
        s"Provider '$provider' is not in allowed list: ${policy.allowedProviders.mkString(", ")}"
      ) :: out
    }

    val spec = providerModelSpec(config)
    if (policy.allowedModelPatterns.nonEmpty) {
      val matches = policy.allowedModelPatterns.exists { p =>
        try {
          val r = new Regex(p)
          r.findFirstIn(spec).isDefined
        } catch { case _: Exception => false }
      }
      if (!matches) {
        out = PolicyViolation(
          "allowedModels",
          s"Model '$spec' does not match any allowed pattern: ${policy.allowedModelPatterns.mkString(", ")}"
        ) :: out
      }
    }

    policy.maxContextWindow.foreach { max =>
      if (config.contextWindow > max) {
        out = PolicyViolation(
          "maxContextWindow",
          s"contextWindow ${config.contextWindow} exceeds maximum $max"
        ) :: out
      }
    }

    policy.maxReserveCompletion.foreach { max =>
      if (config.reserveCompletion > max) {
        out = PolicyViolation(
          "maxReserveCompletion",
          s"reserveCompletion ${config.reserveCompletion} exceeds maximum $max"
        ) :: out
      }
    }

    policy.requiredBaseUrlPattern.foreach { pattern =>
      baseUrlOrEndpoint(config) match {
        case Some(url) =>
          try {
            val r = new Regex(pattern)
            if (r.findFirstIn(url).isEmpty) {
              out = PolicyViolation(
                "requiredBaseUrl",
                s"Base URL/endpoint does not match required pattern: $pattern"
              ) :: out
            }
          } catch { case _: Exception => }
        case None =>
          out = PolicyViolation("requiredBaseUrl", "No base URL/endpoint to validate") :: out
      }
    }

    out.reverse
  }
}
