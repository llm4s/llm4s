package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

/**
 * Asks the provider to cache the start of the request on its side, so a later request with the same start reads it
 * from the cache at a lower price and latency (RFC 0008). Set it with [[CompletionOptions.withPromptCache]].
 *
 * This is not [[org.llm4s.llmconnect.caching.CachingLLMClient]], which keeps whole answers on the client and returns
 * one without calling the provider. A prompt cache still calls the provider every time and changes what the call
 * costs, not what it returns.
 *
 * Anthropic is sent `cache_control` breakpoints: on the last system block, the last tool and, for
 * [[PromptCache.Scope.Conversation]], the conversation so far, each with [[PromptCache.Ttl]]. OpenAI, Gemini and
 * Vertex AI cache on their own and report it `NotNeeded`; other providers report it `Dropped`. A prefix shorter than
 * the model's minimum cacheable length is not cached and the provider says nothing, so when a request asked for a
 * prompt cache and nothing was read from or written to it, the completion reports `Dropped`.
 *
 * '''Warm the cache before a fan-out.''' The cache is written when a response starts, so requests sent together all
 * miss it and each pays to write it. Send one request first and wait for it, then send the rest.
 *
 * `@Stable`, not `@Experimental` as RFC 0008 first wrote it: the `@Stable` [[CompletionOptions]] exposes it, as it
 * exposes [[Thinking]] (decisions 14 and 16).
 *
 * @param scope what to cache: the system prompt, the tools, the conversation so far
 * @param ttl how long the provider keeps it
 */
@Stable
final case class PromptCache private (scope: Set[PromptCache.Scope], ttl: PromptCache.Ttl) {

  /** Sets what to cache. */
  def withScope(scope: Set[PromptCache.Scope]): PromptCache = copy(scope = scope)

  /** Sets how long the provider keeps it. */
  def withTtl(ttl: PromptCache.Ttl): PromptCache = copy(ttl = ttl)
}

object PromptCache {

  /** A part of the request to cache. */
  enum Scope {

    /** The system prompt. */
    case System

    /** The tool definitions. */
    case Tools

    /** The conversation so far, up to the last message. */
    case Conversation
  }

  /** How long the provider keeps the cache. */
  enum Ttl {

    /** The provider's short lifetime: 5 minutes on Anthropic, refreshed on each read. */
    case Short

    /** The provider's long lifetime: 1 hour on Anthropic, at a higher write price. */
    case Long
  }

  /** The system prompt and the tools, for the short lifetime: what most repeated requests share. */
  val default: PromptCache = new PromptCache(Set(Scope.System, Scope.Tools), Ttl.Short)

  /** Creates a [[PromptCache]]. */
  def apply(scope: Set[Scope], ttl: Ttl): PromptCache = new PromptCache(scope, ttl)
}
