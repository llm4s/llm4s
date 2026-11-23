package org.llm4s.llmconnect.provider

import com.anthropic.models.messages.{ MessageCreateParams, Usage }
import org.llm4s.llmconnect.model.{ CompletionOptions, RequestAdjustment, RequestOption, ServiceTier }

import scala.jdk.OptionConverters.*

/**
 * `CompletionOptions.serviceTier` as the Messages API's `service_tier` (#2726).
 *
 * Anthropic takes `auto` (the default: Priority Tier capacity when the organization has a commitment, else standard)
 * and `standard_only` (https://platform.claude.com/docs/en/api/service-tiers). There is no request value for Priority
 * Tier itself, which `auto` uses when it can, and no flex tier: `Priority` is sent as `auto` (`Adjusted`), and `Flex`
 * is not sent (`Dropped`). `ServiceTier.Other` is sent as written.
 *
 * The reply's `usage.service_tier` names the tier that served the request, `standard`, `priority` or `batch`, read by
 * [[served]] onto `Completion.servedTier` (#2746): `standard` is `Default`, and `batch` is kept as `Other("batch")`.
 */
private[provider] object AnthropicServiceTier {

  /** The `service_tier` to send for `options`, and what is reported. */
  def plan(options: CompletionOptions): (Option[MessageCreateParams.ServiceTier], List[RequestAdjustment]) =
    options.serviceTier match {
      case None                      => (None, Nil)
      case Some(ServiceTier.Auto)    => (Some(MessageCreateParams.ServiceTier.AUTO), Nil)
      case Some(ServiceTier.Default) => (Some(MessageCreateParams.ServiceTier.STANDARD_ONLY), Nil)
      case Some(ServiceTier.Priority) =>
        (
          Some(MessageCreateParams.ServiceTier.AUTO),
          List(
            RequestAdjustment.Adjusted(
              RequestOption.ServiceTier,
              "Priority",
              "auto",
              "Anthropic serves Priority Tier under auto, where the organization has a Priority Tier commitment"
            )
          )
        )
      case Some(ServiceTier.Flex) =>
        (None, List(RequestAdjustment.Dropped(RequestOption.ServiceTier, "Anthropic has no flex tier")))
      case Some(ServiceTier.Other(raw)) => (Some(MessageCreateParams.ServiceTier.of(raw)), Nil)
    }

  /** The tier a reply's `usage.service_tier` names, `None` when it names none or is not a string. */
  def served(usage: Usage): Option[ServiceTier] =
    usage._serviceTier().asKnown().toScala.map(_.asString()).filter(_.nonEmpty).map {
      case "standard" => ServiceTier.Default
      case "priority" => ServiceTier.Priority
      case raw        => ServiceTier.Other(raw)
    }
}
