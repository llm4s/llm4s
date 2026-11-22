package org.llm4s.llmconnect.provider

import com.anthropic.models.messages.CacheControlEphemeral
import org.llm4s.llmconnect.model.{ CompletionOptions, PromptCache, RequestAdjustment, TokenUsage }
import org.llm4s.model.PromptCacheReport

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
  def requested(options: CompletionOptions): List[RequestAdjustment] = PromptCacheReport.emptyScope(options)

  /** What is reported after the call: a request that asked for a prompt cache and nothing was cached. */
  def answered(options: CompletionOptions, usage: Option[TokenUsage]): List[RequestAdjustment] =
    PromptCacheReport.uncached(options.promptCache.exists(_.scope.nonEmpty), usage)
}
