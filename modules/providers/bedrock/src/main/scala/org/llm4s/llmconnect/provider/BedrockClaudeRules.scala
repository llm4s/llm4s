package org.llm4s.llmconnect.provider

import org.llm4s.model.{ ModelCapabilities, ModelRegistryService, RequestTransformer }

/**
 * The Claude models on Bedrock that take no sampling parameter, by name, for an id the registry does not describe
 * (#2909).
 *
 * Anthropic removed `temperature`, `top_p` and `top_k` from Claude Opus 4.7 and later and from every model of the
 * Claude 5 generation (Sonnet 5 and 5.5, Haiku 5.5, Opus 5 and 5.5, Fable, Mythos): a request that sets one to
 * anything but the model's default is a 400. Converse's `inferenceConfig` carries `temperature` and `topP`, so for
 * these models neither is sent, and one the caller set is reported `Dropped`.
 *
 * The registry says so for the Bedrock ids it knows (`supports_sampling_params: false`), and what it says wins; the
 * name rule is the fallback for one it does not know, and treats an unknown newer Claude as rejecting, since leaving
 * a parameter out only means the model's default applies.
 *
 * The id is read wherever it appears: `anthropic.claude-sonnet-5-5`, a geography or region prefix (`us.`, `eu.`,
 * `apac.`, `global.`), a `-v1:0` suffix, a `bedrock/...` path or an inference-profile ARN that names the model. An
 * application inference profile's ARN does not name it, and is sent what was asked.
 *
 * `llm4s-anthropic`'s `AnthropicModelRules` holds the same rule for the Messages API. This module depends on core
 * only, so the rule is written twice; the two specs check the same ids.
 */
private[llm4s] object BedrockClaudeRules {

  /** The request transformer `BedrockClient` uses: the registry's capabilities, adjusted by [[adjust]]. */
  def transformer(service: ModelRegistryService): RequestTransformer =
    RequestTransformer.adjusted(service)((modelId, capabilities) => adjust(modelId.asString, capabilities))

  /** Rules the sampling parameters out for a model that rejects them, unless the registry already says. */
  def adjust(modelId: String, capabilities: ModelCapabilities): ModelCapabilities =
    if (rejectsSampling(modelId))
      capabilities.withSupportsSamplingParams(capabilities.supportsSamplingParams.orElse(Some(false)))
    else capabilities

  /** `claude-<family>`, then a one or two digit major and minor version, `-` or `.` between them. */
  private val Claude = """claude-(opus|sonnet|haiku|fable|mythos)(?:-(\d{1,2})(?!\d)(?:[-.](\d{1,2})(?!\d))?)?""".r

  /**
   * Whether `modelId` names a Claude model that takes no sampling parameter. An id with no version
   * (`claude-sonnet-latest`) is the family's current model, which does not; `claude-3-opus-...`, whose version comes
   * before the family, and a dated `claude-opus-4-20250514` are the older models they are.
   */
  def rejectsSampling(modelId: String): Boolean =
    Claude.findFirstMatchIn(modelId.toLowerCase).exists { m =>
      val version = Option(m.group(2)).map(major => (major.toInt, Option(m.group(3)).fold(0)(_.toInt)))
      (m.group(1), version) match {
        case ("fable" | "mythos", _) => true
        case (_, None)               => true
        case ("opus", Some(v))       => Ordering[(Int, Int)].gteq(v, (4, 7))
        case (_, Some((major, _)))   => major >= 5
      }
    }
}
