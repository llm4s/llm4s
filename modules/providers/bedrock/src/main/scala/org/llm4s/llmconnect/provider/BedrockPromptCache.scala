package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, PromptCache, RequestAdjustment, RequestOption }
import org.llm4s.model.{ ModelCapabilities, PromptCacheReport }
import software.amazon.awssdk.services.bedrockruntime.model.{ CachePointBlock, CachePointType, CacheTTL }

/**
 * `CompletionOptions.promptCache` on Bedrock Converse (RFC 0008, Part 3): which `cachePoint` blocks a request
 * carries, and what is reported when the model cannot take what was asked for.
 *
 * Each [[PromptCache.Scope]] is one cache checkpoint: a `cachePoint` after the last system block, after the last
 * tool, and, for [[PromptCache.Scope.Conversation]], at the end of the last message. That is at most three of the
 * four checkpoints Converse allows, all with one TTL. The documented shape is
 * `{"cachePoint": {"type": "default", "ttl": "1h"}}`, where `ttl` is left out for the 5-minute default, as AWS asks for
 * a model that takes only 5 minutes ("Including the field for these models can result in a `ValidationException`").
 *
 * Which models take checkpoints, where, and with which TTL follows AWS's "Supported models, Regions, and explicit
 * caching limits" table (https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-caching.html):
 *
 *  - '''Anthropic Claude''' takes checkpoints in `system`, `messages` and `tools`, with a 1-hour TTL from Claude
 *    Sonnet 4.5, Haiku 4.5 and Opus 4.5 on. Claude 3.x, Sonnet 4, Opus 4 and Opus 4.1 take only 5 minutes, so `Long`
 *    is sent as `Short` and reported `Adjusted`.
 *  - '''Amazon Nova''' (Micro, Lite, Pro, 2 Lite) takes checkpoints in `system` and `messages` only, with 5 minutes:
 *    `Tools` is left out and `Long` sent as `Short`, reported `Adjusted`.
 *  - '''Any other model''' (Converse does not take explicit checkpoints for OpenAI's models on Bedrock, Llama, Mistral
 *    and the rest): reported `Dropped`.
 *
 * A model the model registry knows is sent checkpoints only when its metadata says it supports prompt caching, so a
 * Claude model that does not (Claude 3 Haiku, Claude 2) is reported `Dropped`, not sent a request Converse rejects. A
 * model id the registry does not know (a newer model, or an inference profile ARN that names one) is judged by its
 * family alone.
 */
private[provider] object BedrockPromptCache {

  /** What one request sends: the scopes that get a checkpoint, the TTL field, and what is reported. */
  final case class Plan(scope: Set[PromptCache.Scope], ttl: Option[CacheTTL], adjustments: List[RequestAdjustment]) {

    /** Whether `scope` gets a checkpoint. */
    def covers(scope: PromptCache.Scope): Boolean = this.scope.contains(scope)

    /** The checkpoint block, the same for every scope. */
    def point: CachePointBlock = {
      val builder = CachePointBlock.builder().`type`(CachePointType.DEFAULT)
      ttl.foreach(builder.ttl)
      builder.build()
    }
  }

  private val NoCheckpoints = Plan(Set.empty, None, Nil)

  private enum Family {
    case Claude(longTtl: Boolean)
    case Nova
    case Other
  }

  // Claude 3.x, Sonnet 4 (claude-sonnet-4-20250514), Opus 4 and Opus 4.1 take only the 5-minute TTL
  private val ShortTtlClaude = """.*claude-(3-.*|(sonnet|opus)-4-(1-)?\d{8}.*)""".r

  // the Nova models AWS documents as taking explicit checkpoints
  private val CachingNova = """.*amazon\.nova-(micro|lite|pro|2-lite)-.*""".r

  private def family(model: String): Family = {
    val id = model.toLowerCase
    if (id.contains("anthropic.claude")) Family.Claude(longTtl = !ShortTtlClaude.matches(id))
    else if (CachingNova.matches(id)) Family.Nova
    else Family.Other
  }

  /**
   * The checkpoints `options` asks for on `model`, and what is reported about them.
   *
   * @param capabilities the model's registry entry, when the registry knows it
   */
  def plan(model: String, options: CompletionOptions, capabilities: Option[ModelCapabilities]): Plan =
    options.promptCache.fold(NoCheckpoints) { cache =>
      if (cache.scope.isEmpty) NoCheckpoints.copy(adjustments = PromptCacheReport.emptyScope(options))
      else
        (family(model), capabilities.map(_.supportsPromptCaching.contains(true))) match {
          case (Family.Other, _) =>
            dropped(s"Converse takes cache checkpoints only from Anthropic Claude and Amazon Nova models, not $model")
          case (_, Some(false)) =>
            dropped(s"the model registry does not list prompt caching for $model")
          case (Family.Claude(longTtl), _) =>
            fitted(model, cache, scopes = cache.scope, longTtl = longTtl)
          case (Family.Nova, _) =>
            fitted(model, cache, scopes = cache.scope - PromptCache.Scope.Tools, longTtl = false)
        }
    }

  private def dropped(reason: String): Plan =
    NoCheckpoints.copy(adjustments = List(RequestAdjustment.Dropped(RequestOption.PromptCache, reason)))

  private def fitted(model: String, cache: PromptCache, scopes: Set[PromptCache.Scope], longTtl: Boolean): Plan = {
    val ttl = if (longTtl) cache.ttl else PromptCache.Ttl.Short
    val reasons =
      Option.when(scopes != cache.scope)(s"$model takes cache checkpoints in system and messages, not tools").toList ++
        Option.when(ttl != cache.ttl)(s"$model keeps a prompt cache for 5 minutes only").toList
    if (scopes.isEmpty) dropped(reasons.mkString("; "))
    else
      Plan(
        scopes,
        Option.when(ttl == PromptCache.Ttl.Long)(CacheTTL.VALUE_1_H),
        Option
          .when(reasons.nonEmpty)(
            RequestAdjustment.Adjusted(
              RequestOption.PromptCache,
              describe(cache.scope, cache.ttl),
              describe(scopes, ttl),
              reasons.mkString("; ")
            )
          )
          .toList
      )
  }

  /** A scope and TTL as a reader sees them, e.g. `System+Tools, Long`, in the order the scopes are declared. */
  private def describe(scope: Set[PromptCache.Scope], ttl: PromptCache.Ttl): String =
    PromptCache.Scope.values.filter(scope.contains).mkString("+") + s", $ttl"
}
