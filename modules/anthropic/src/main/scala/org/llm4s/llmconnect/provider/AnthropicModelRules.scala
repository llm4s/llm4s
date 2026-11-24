package org.llm4s.llmconnect.provider

import org.llm4s.model.{ ModelCapabilities, ModelRegistryService, RequestTransformer }

/**
 * Anthropic's request rules that follow from a Claude model's name, for an id the registry does not describe (#2909).
 *
 * Claude Opus 4.7 and later, and every model of the Claude 5 generation (Sonnet 5 and 5.5, Haiku 5.5, Opus 5 and
 * 5.5, the Fable and Mythos models), take only adaptive thinking and no sampling parameter:
 *  - `temperature`, `top_p` or `top_k` at anything but the model's default is a 400 ("`temperature` is deprecated
 *    for this model."), so none is sent;
 *  - `thinking: {type: "enabled", budget_tokens}` is a 400, so thinking is `adaptive` and the level goes in
 *    `output_config.effort`.
 *
 * The registry says the same where it knows the id (`supports_sampling_params: false` from the bundled catalog,
 * `supports_budget_thinking: false` from llm4s's overrides), and what it says wins. The name rule is the fallback for
 * an id it has not heard of - a new snapshot, a `-latest` alias, a deployment name - and for a model released after
 * this file was written: a model that rejects a parameter fails the whole request, while leaving a parameter out of
 * a request to a model that takes it only means the model's default applies, so an unknown newer Claude is treated
 * as rejecting. `AnthropicModelRulesSpec` fails when the bundled catalog and the name rule disagree.
 *
 * Sources: Anthropic's migration guide (the Opus 4.7, Sonnet 5, Sonnet 5.5, Haiku 5.5 and Fable sections) and
 * "Adaptive thinking" page, and a probe of the Messages API on 2026-11-24 covering every model named above except
 * Mythos.
 *
 * `llm4s-bedrock`'s `BedrockClaudeRules` holds the sampling half for Converse.
 */
private[llm4s] object AnthropicModelRules {

  /** The request transformer `AnthropicClient` uses: the registry's capabilities, adjusted by [[adjust]]. */
  def transformer(service: ModelRegistryService): RequestTransformer =
    RequestTransformer.adjusted(service)((modelId, capabilities) => adjust(modelId.asString, capabilities))

  /**
   * The capabilities to plan a request with: the registry's, with what the name says filled in where the registry
   * is silent. A model the registry does not know stays unknown (`None`) unless its name places it.
   */
  def capabilities(modelId: String, found: Option[ModelCapabilities]): Option[ModelCapabilities] =
    if (adaptiveOnly(modelId)) Some(adjust(modelId, found.getOrElse(ModelCapabilities()))) else found

  /** Fills in, for an [[adaptiveOnly]] model, each rule the registry's capabilities do not already state. */
  def adjust(modelId: String, capabilities: ModelCapabilities): ModelCapabilities =
    generation(modelId).filter(_.adaptiveOnly).fold(capabilities) { claude =>
      capabilities
        .withSupportsSamplingParams(capabilities.supportsSamplingParams.orElse(Some(false)))
        .withSupportsBudgetThinking(capabilities.supportsBudgetThinking.orElse(Some(false)))
        .withSupportsAdaptiveThinking(capabilities.supportsAdaptiveThinking.orElse(Some(true)))
        .withSupportsOutputConfig(capabilities.supportsOutputConfig.orElse(Some(true)))
        .withSupportsThinkingOff(capabilities.supportsThinkingOff.orElse(Option.when(claude.alwaysThinks)(false)))
    }

  /**
   * Whether `modelId` names a Claude model that takes only adaptive thinking and no sampling parameter: Opus from
   * 4.7, any other family from 5, every Fable and Mythos, and an alias with no version (`claude-sonnet-latest`),
   * which is the family's current model.
   */
  def adaptiveOnly(modelId: String): Boolean = generation(modelId).exists(_.adaptiveOnly)

  /** A Claude model as its id names it; `version` is `None` for an alias such as `claude-opus-latest`. */
  final private[provider] case class Claude(family: String, version: Option[(Int, Int)]) {
    private def from(major: Int, minor: Int) = version.forall(Ordering[(Int, Int)].gteq(_, (major, minor)))

    def adaptiveOnly: Boolean = family match {
      case "fable" | "mythos" => true
      case "opus"             => from(4, 7)
      case _                  => from(5, 0)
    }

    /**
     * Whether `thinking: disabled` is a 400: Opus and Sonnet from 5.5, every Fable and Mythos. Haiku 5.5 and Opus 5
     * still take it (at effort `high` or below), so only a versioned id is judged.
     */
    def alwaysThinks: Boolean = family match {
      case "fable" | "mythos" => true
      case "opus" | "sonnet"  => version.exists(Ordering[(Int, Int)].gteq(_, (5, 5)))
      case _                  => false
    }
  }

  /** `claude-<family>`, then a one or two digit major and minor version, `-` or `.` between them. */
  private val Id = """claude-(opus|sonnet|haiku|fable|mythos)(?:-(\d{1,2})(?!\d)(?:[-.](\d{1,2})(?!\d))?)?""".r

  /**
   * The Claude model `modelId` names, wherever in the id it appears: bare, dated (`claude-sonnet-5-5-20260901`),
   * with a router or platform prefix (`anthropic/`, `vertex_ai/`, `anthropic.`, `us.anthropic.`, `bedrock/...`), a
   * Vertex `@` suffix, a Bedrock `-v1:0` suffix or an OpenRouter dotted version (`claude-fable-5.2`).
   *
   * Only a one or two digit segment is a version, so `claude-opus-4-20250514` is Opus 4 and not 4.20, and the ids
   * that put the version before the family (`claude-3-opus-20240229`, `claude-3-7-sonnet-latest`) name none of the
   * models this rule is about and are `None`.
   */
  private[provider] def generation(modelId: String): Option[Claude] =
    Id.findFirstMatchIn(modelId.toLowerCase).map { m =>
      Claude(m.group(1), Option(m.group(2)).map(major => (major.toInt, Option(m.group(3)).fold(0)(_.toInt))))
    }
}
