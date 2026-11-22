package org.llm4s.llmconnect.provider

import com.anthropic.models.messages.CacheControlEphemeral
import org.llm4s.llmconnect.model.{ CompletionOptions, PromptCache, RequestAdjustment, RequestOption, TokenUsage }

/**
 * `CompletionOptions.promptCache` on Anthropic (RFC 0008, PR 6): where its `cache_control` breakpoints go, and what
 * is reported when nothing was cached.
 *
 * Each [[PromptCache.Scope]] is one breakpoint: the last system block, the last tool, and the conversation so far
 * through the request's top-level `cache_control`, which Anthropic puts on the last block it can cache. That is at
 * most three of the four a request may have, all with the one [[PromptCache.Ttl]], so the rule that a longer TTL must
 * come first never applies. A breakpoint is not part of the prompt a signed thinking block is checked against, so
 * it does not unseal replayed thinking.
 */
private[provider] object AnthropicPromptCache {

  /** The breakpoint for `scope`, when `options` asks to cache it. */
  def control(options: CompletionOptions, scope: PromptCache.Scope): Option[CacheControlEphemeral] =
    options.promptCache.filter(_.scope.contains(scope)).map { cache =>
      val ttl = cache.ttl match {
        case PromptCache.Ttl.Short => CacheControlEphemeral.Ttl.TTL_5M
        case PromptCache.Ttl.Long  => CacheControlEphemeral.Ttl.TTL_1H
      }
      CacheControlEphemeral.builder().ttl(ttl).build()
    }

  /** What is reported before the call: a prompt cache whose scope names nothing has nothing to send. */
  def requested(options: CompletionOptions): List[RequestAdjustment] =
    options.promptCache
      .filter(_.scope.isEmpty)
      .toList
      .map(_ => RequestAdjustment.Dropped(RequestOption.PromptCache, "the prompt cache's scope names nothing to cache"))

  /**
   * What is reported after the call. Anthropic does not cache a prefix shorter than the model's minimum cacheable
   * length, and says nothing, so a request that asked for a prompt cache and neither read from nor wrote to it is
   * reported `Dropped`. Without usage it cannot be told, and nothing is reported.
   */
  def answered(options: CompletionOptions, usage: Option[TokenUsage]): List[RequestAdjustment] =
    if (!options.promptCache.exists(_.scope.nonEmpty)) Nil
    else
      usage.toList
        .filter(u => u.cachedTokens.getOrElse(0) == 0 && u.cacheCreationTokens.getOrElse(0) == 0)
        .map(_ =>
          RequestAdjustment.Dropped(
            RequestOption.PromptCache,
            "nothing was cached: the prefix may be below the model's minimum cacheable length"
          )
        )
}
