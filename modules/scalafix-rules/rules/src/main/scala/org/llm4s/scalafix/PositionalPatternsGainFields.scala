package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * A positional pattern on a type that gained fields since 0.5.1 binds them with `_`.
 *
 * Every field added since 0.5.1 to a public case class or enum case is appended after the old ones, so a pattern that
 * binds the old fields by position gets one `_` per new field: `case ServiceError(message, status, provider,
 * requestId)` becomes `case ServiceError(message, status, provider, requestId, _)` (#2266). `AgentStatus.Suspended`
 * and the `RunEvent` cases that gained fields are not here: each now holds one growth-safe class (#2781), so a
 * positional pattern on one is rewritten by hand to read its fields by name. Nor is `TraceEvent.ErrorOccurred`, whose
 * first field is no longer the exception (#2800): read `message`, `errorType` and `cause` by name. A pattern with 0.6.0's arity gets a `_` for each
 * field added since 0.6.0 (`Completion` is not here: its extractor also lost two fields, so `CompletionFromMessage`
 * rewrites a pattern on it):
 * `ModelCapabilities` gains the five thinking and effort fields (#2115) and `supportsSamplingParams` (#2909),
 * `TraceEvent.CompletionReceived`'s fourteen gain `servedTier` (#2750), `TokenUsage`'s six gain
 * `longCacheCreationTokens` and `ModelPricing`'s fifteen `cacheCreationInputTokenCostAbove1hr` (#2115),
 * `CompletionOptions`'s nine gain `promptCache` (#2115) and `serviceTier` (#2726; its eighth, `budgetTokens`, is now
 * `thinking`, which `ThinkingReplacesBudgetTokens` reports), `TurnState` gains `cut` (#2613) and `RAGConfig`
 * `completionOptions` (#2715), `documentRegistry` (#2714), `questionCondenser` (#2733) and `legFailurePolicy` (#2995). `AgentResult`'s six, the same in
 * 0.5.1 and 0.6.0, gain `runUsage` (#2922). A pattern with any other number of arguments is left
 * alone.
 *
 * [[Llm4sMigrateFrom_1_0]] runs it with [[PositionalPatternsGainFields.arity10]]: since 1.0.0, `OpenAIConfig` gained
 * `api`, `reasoningSummary` and `keepReasoning` and `ModelMetadata` `supportedEndpoints` (#2900), `MemoryBounds`
 * `owner`, `PostgresMemoryStore.Config` `tenant` (#3032, #3034) and `RAGConfig` `legFailurePolicy` (#2995).
 */
class PositionalPatternsGainFields(maps: List[Map[String, (Int, Int)]])
    extends SemanticRule("PositionalPatternsGainFields") {

  /** The rule of 0.5.x and 0.6.x code: [[PositionalPatternsGainFields.arity]] and [[PositionalPatternsGainFields.arity06]]. */
  def this() = this(PositionalPatternsGainFields.since05)

  override def description: String =
    "Adds a _ for each field appended since llm4s 0.5.1 (or 0.6.0) to positional patterns on the types that gained fields"

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect { case p: Pat.Extract =>
      val args = p.argClause.values
      Migration
        .symbolOf(p.fun)
        .map(s => Migration.owner(s))
        .flatMap(PositionalPatternsGainFields.gained(maps, _, args.size))
        .collect {
          case added if args.nonEmpty && !args.exists(_.is[Pat.SeqWildcard]) => Patch.addRight(args.last, ", _" * added)
        }
        .getOrElse(Patch.empty)
    }.asPatch
}

object PositionalPatternsGainFields {

  /**
   * Each type's extractor arity on main among the types that gained fields in a minor release, by its 1.0.0 symbol.
   * Every map below takes such a type from its arity in its release to this one, in one pass (a pass cannot chain
   * 9 to 11 to 14: its patches are all computed on the original tree), so the next field gained is one change here,
   * which every umbrella picks up. `ModelMetadata` is in only [[arity10]]: 0.5.1's and 0.6.0's had ten fields, two of
   * them since removed, so a pattern on it is not widened by a `_`.
   */
  private val current: Map[String, Int] = Map(
    "org/llm4s/llmconnect/config/OpenAIConfig"          -> 14,
    "org/llm4s/model/ModelMetadata"                     -> 9,
    "org/llm4s/agent/memory/MemoryBounds"               -> 3,
    "org/llm4s/agent/memory/PostgresMemoryStore.Config" -> 8,
    "org/llm4s/rag/RAGConfig"                           -> 29
  )

  /** `old` (a type's arity in some release) as `(old, current)` pairs. */
  private def toCurrent(old: Map[String, Int]): Map[String, (Int, Int)] =
    old.map { case (owner, before) => owner -> (before -> current(owner)) }

  /**
   * Each type's extractor arity in 0.5.1 and on main, by its 0.5.1 symbol. Found by comparing every case class and
   * enum case of the 0.5.1 sources of core, the provider modules, rag and agent with main; in each, the new fields
   * follow the old ones. `ServiceError` had a hand-written four-field `unapply`, which is gone. A type whose arity was
   * the same in 0.6.0 as in 0.5.1 is here, not in [[arity06]], so a pattern written against either release matches.
   * `CompletionOptions`' eighth field changed type as well (`budgetTokens` became `thinking`): the added `_` keeps a
   * pattern that ignores the eighth compiling.
   */
  val arity: Map[String, (Int, Int)] = Map(
    "org/llm4s/error/ServiceError"                                  -> (4  -> 5),
    "org/llm4s/agent/AgentResult"                                   -> (6  -> 7),
    "org/llm4s/agent/events/ModelCallCompleted"                     -> (6  -> 11),
    "org/llm4s/agent/events/ToolExecuted"                           -> (5  -> 6),
    "org/llm4s/agent/graph/Checkpoint"                              -> (10 -> 11),
    "org/llm4s/agent/graph/Commit"                                  -> (6  -> 8),
    "org/llm4s/agent/graph/Observer"                                -> (2  -> 3),
    "org/llm4s/agent/graph/RunBudgets"                              -> (3  -> 4),
    "org/llm4s/agent/graph/RunConfig"                               -> (8  -> 10),
    "org/llm4s/agent/graph/toolloop/TurnState"                      -> (5  -> 6),
    "org/llm4s/agent/harness/HarnessProfile"                        -> (12 -> 13),
    "org/llm4s/config/ProvidersConfigModel.NamedProviderConfig"     -> (8  -> 9),
    "org/llm4s/llmconnect/LlmClientOptions"                         -> (2  -> 3),
    "org/llm4s/llmconnect/model/CompletionOptions"                  -> (9  -> 11),
    "org/llm4s/llmconnect/model/TokenUsage"                         -> (6  -> 7),
    "org/llm4s/llmconnect/config/AnthropicConfig"                   -> (7  -> 10),
    "org/llm4s/llmconnect/config/AzureConfig"                       -> (7  -> 10),
    "org/llm4s/llmconnect/config/CohereConfig"                      -> (6  -> 8),
    "org/llm4s/llmconnect/config/DeepSeekConfig"                    -> (6  -> 8),
    "org/llm4s/llmconnect/config/EmbeddingProviderConfig"           -> (4  -> 6),
    "org/llm4s/llmconnect/config/GeminiConfig"                      -> (6  -> 9),
    "org/llm4s/llmconnect/config/MistralConfig"                     -> (6  -> 8),
    "org/llm4s/llmconnect/config/OpenAICompatibleConfig"            -> (9  -> 11),
    "org/llm4s/llmconnect/config/VertexAIConfig"                    -> (8  -> 10),
    "org/llm4s/llmconnect/config/ZaiConfig"                         -> (6  -> 8),
    "org/llm4s/llmconnect/provider/OpenAICompatibleClient.Settings" -> (8  -> 9),
    "org/llm4s/llmconnect/spi/ProviderConfigSpec"                   -> (8  -> 9),
    "org/llm4s/llmconnect/spi/ProviderFeatures"                     -> (2  -> 3),
    "org/llm4s/model/ModelCapabilities"                             -> (18 -> 24),
    "org/llm4s/model/ModelPricing"                                  -> (15 -> 16),
    "org/llm4s/model/TransformationResult"                          -> (3  -> 4),
    "org/llm4s/reranker/RerankProviderConfig"                       -> (3  -> 4),
    "org/llm4s/toolapi/SafeParameterExtractor"                      -> (1  -> 2),
    "org/llm4s/toolapi/ToolRetryPolicy"                             -> (3  -> 5),
    "org/llm4s/trace/TraceEvent.AgentRunEnded"                      -> (7  -> 14),
    "org/llm4s/trace/TraceEvent.CompletionReceived"                 -> (5  -> 15),
    "org/llm4s/trace/TraceEvent.CustomEvent"                        -> (3  -> 4),
    "org/llm4s/trace/TraceEvent.EmbeddingUsageRecorded"             -> (5  -> 7),
    "org/llm4s/trace/TraceEvent.TokenUsageRecorded"                 -> (4  -> 5),
    "org/llm4s/vectorstore/VectorStoreFactory.Config"               -> (4  -> 5)
  ) ++ toCurrent(Map("org/llm4s/llmconnect/config/OpenAIConfig" -> 9, "org/llm4s/rag/RAGConfig" -> 25))

  /**
   * Each type's extractor arity in 0.6.0 and on main, where 0.6.0's differs from 0.5.1's: a pattern written against
   * 0.6.0 does not have 0.5.1's arity, so the two maps never both match.
   */
  val arity06: Map[String, (Int, Int)] = Map(
    "org/llm4s/agent/events/ModelCallCompleted"     -> (8  -> 11),
    "org/llm4s/trace/TraceEvent.CompletionReceived" -> (14 -> 15)
  ) ++ toCurrent(Map("org/llm4s/llmconnect/config/OpenAIConfig" -> 11))

  /**
   * Each type's extractor arity in 1.0.0 and on main, for the types that gained fields in 1.x, by their 1.0.0 symbol:
   * `OpenAIConfig` gained `api`, `reasoningSummary` and `keepReasoning`, `ModelMetadata` `supportedEndpoints` (#2900),
   * `MemoryBounds` `owner`, `PostgresMemoryStore.Config` `tenant` (#3032, #3034) and `RAGConfig` `legFailurePolicy`
   * (#2995). 0.7.0 had the same arities, so
   * [[Llm4sMigrateFrom_0_7]] and [[Llm4sMigrateFrom_1_0]] both run this map.
   */
  val arity10: Map[String, (Int, Int)] = toCurrent(
    Map(
      "org/llm4s/llmconnect/config/OpenAIConfig"          -> 11,
      "org/llm4s/model/ModelMetadata"                     -> 8,
      "org/llm4s/agent/memory/MemoryBounds"               -> 2,
      "org/llm4s/agent/memory/PostgresMemoryStore.Config" -> 7,
      "org/llm4s/rag/RAGConfig"                           -> 28
    )
  )

  /** The maps the rule of [[Llm4sMigrateFrom_0_5]] and [[Llm4sMigrateFrom_0_6]] reads. */
  private[scalafix] val since05: List[Map[String, (Int, Int)]] = List(arity, arity06)

  /** The number of fields a pattern of `size` arguments on `owner` lacks, if a map in `maps` has its arity. */
  def gained(maps: List[Map[String, (Int, Int)]], owner: String, size: Int): Option[Int] =
    maps.flatMap(_.get(owner)).collectFirst { case (before, after) if before == size => after - before }
}
