package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import upickle.default.ReadWriter

/**
 * The processing tier a provider serves a request at: what it costs, how fast it answers and how readily it sheds
 * the request under load (RFC 0008, #2726). Set it with [[CompletionOptions.withServiceTier]]. It changes what a call
 * costs and how it is scheduled, not what it returns.
 *
 * Each provider maps it onto its own tiers, and reports on [[Completion.adjustments]] what it cannot send as asked:
 *
 *  - '''OpenAI''': `service_tier` `auto`, `default`, `flex` or `priority`. Azure OpenAI takes `auto`, `default` and
 *    `priority`; `Flex` is reported `Dropped` there.
 *  - '''Anthropic''': `service_tier` `auto` (Priority Tier when the organization has a commitment, else standard) or
 *    `standard_only` (`Default`). `Priority` is sent as `auto`, reported `Adjusted`; `Flex` has no counterpart and is
 *    reported `Dropped`.
 *  - '''Gemini''': `serviceTier` `standard`, `flex` or `priority`. '''Vertex AI''': the
 *    `X-Vertex-AI-LLM-Shared-Request-Type` header, `flex` or `priority` (Provisioned Throughput first, then that
 *    tier). '''Bedrock''': Converse's `serviceTier.type`, `default`, `flex` or `priority`. Where a provider names no
 *    tier unless asked, `Auto` is not sent and reported `NotNeeded`; on Vertex AI, so is `Default`.
 *  - '''Every other provider''' has no tier to choose, and reports a set tier `Dropped`.
 *
 * Which models and accounts each tier is open to is the provider's: a provider may serve a request at a different
 * tier than the one asked for (Priority over its limit is served at standard on Gemini and Vertex AI), and Flex may
 * be shed under load, so a Flex caller should expect and retry 429 and 503 replies.
 */
@Stable
enum ServiceTier derives ReadWriter {

  /** The provider's own choice: the tier set for the project or account, or its default. */
  case Auto

  /** The standard tier, at the standard price and speed. */
  case Default

  /** Cheaper and slower, served when there is capacity to spare; may queue or be shed under load. */
  case Flex

  /** Faster and more reliable under load, at a higher price, where the account has access to it. */
  case Priority
}
