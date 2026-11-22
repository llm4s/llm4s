package org.llm4s.llmconnect.provider

import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.llm4s.llmconnect.model.{ CompletionOptions, RequestAdjustment, RequestOption, ServiceTier }
import org.llm4s.model.UnmappedOptions

/**
 * `CompletionOptions.serviceTier` as Chat Completions' `service_tier` (#2726).
 *
 * OpenAI takes `auto`, `default`, `flex` and `priority`
 * (https://developers.openai.com/api/reference/resources/chat, "service_tier"). Azure OpenAI takes `auto`, `default`
 * and `priority` on chat completions (https://learn.microsoft.com/azure/foundry/openai/concepts/priority-processing),
 * so `Flex` is not sent there. Requesty, and any other endpoint reached through an `OpenAIConfig`, is sent no tier.
 */
private[provider] object OpenAIServiceTier {

  /** The `service_tier` to send for `options` to `provider`, and what is reported. */
  def plan(
    provider: String,
    displayName: String,
    options: CompletionOptions
  ): (Option[ChatCompletionCreateParams.ServiceTier], List[RequestAdjustment]) =
    options.serviceTier match {
      case None => (None, Nil)
      case Some(ServiceTier.Flex) if provider == "azure" =>
        (None, List(RequestAdjustment.Dropped(RequestOption.ServiceTier, "Azure OpenAI has no flex tier")))
      case Some(tier) if provider == "openai" || provider == "azure" => (Some(value(tier)), Nil)
      case Some(_) => (None, UnmappedOptions.serviceTier(displayName, options))
    }

  private def value(tier: ServiceTier): ChatCompletionCreateParams.ServiceTier = tier match {
    case ServiceTier.Auto     => ChatCompletionCreateParams.ServiceTier.AUTO
    case ServiceTier.Default  => ChatCompletionCreateParams.ServiceTier.DEFAULT
    case ServiceTier.Flex     => ChatCompletionCreateParams.ServiceTier.FLEX
    case ServiceTier.Priority => ChatCompletionCreateParams.ServiceTier.PRIORITY
  }
}
