package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * A positional pattern on a type that gained fields since 0.5.1 binds them with `_`.
 *
 * Every field added since 0.5.1 to a public case class or enum case is appended after the old ones, so a pattern that
 * binds the old fields by position gets one `_` per new field: `case ServiceError(message, status, provider,
 * requestId)` becomes `case ServiceError(message, status, provider, requestId, _)` (#2266),
 * `case RunEvent.RunFailed(message, usage)` becomes `case RunEvent.RunFailed(message, usage, _)` (#2049), and
 * `case AgentStatus.Suspended(a, q, m, b)` gains `delegated` (#2050). A pattern with 0.6.0's arity gets a `_` for each
 * field added since 0.6.0: `Completion`'s ten fields gain `finishReason` (#2613) and `servedTier` (#2746),
 * `ModelCapabilities` gains the five thinking and effort fields (#2115), `TraceEvent.ErrorOccurred`'s nine gain `usage`
 * (#2676), `TokenUsage`'s six gain `longCacheCreationTokens` and `ModelPricing`'s fifteen
 * `cacheCreationInputTokenCostAbove1hr` (#2115),
 * `CompletionOptions`'s nine gain `promptCache` (#2115) and `serviceTier` (#2726; its eighth, `budgetTokens`, is now
 * `thinking`, which `ThinkingReplacesBudgetTokens` reports), `TurnState` gains `cut` (#2613) and `RAGConfig`
 * `completionOptions` (#2715) and `documentRegistry` (#2714). A pattern with any other number of arguments is left
 * alone.
 */
class PositionalPatternsGainFields extends SemanticRule("PositionalPatternsGainFields") {

  override def description: String =
    "Adds a _ for each field appended since llm4s 0.5.1 (or 0.6.0) to positional patterns on the types that gained fields"

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect { case p: Pat.Extract =>
      val args = p.argClause.values
      Migration
        .symbolOf(p.fun)
        .map(s => Migration.owner(s))
        .flatMap(PositionalPatternsGainFields.gained(_, args.size))
        .collect {
          case added if args.nonEmpty && !args.exists(_.is[Pat.SeqWildcard]) => Patch.addRight(args.last, ", _" * added)
        }
        .getOrElse(Patch.empty)
    }.asPatch
}

object PositionalPatternsGainFields {

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
    "org/llm4s/agent/AgentStatus.Suspended"                         -> (4  -> 5),
    "org/llm4s/agent/events/ModelCallCompleted"                     -> (6  -> 10),
    "org/llm4s/agent/events/ToolExecuted"                           -> (5  -> 6),
    "org/llm4s/agent/graph/Checkpoint"                              -> (10 -> 11),
    "org/llm4s/agent/graph/Commit"                                  -> (6  -> 8),
    "org/llm4s/agent/graph/Observer"                                -> (2  -> 3),
    "org/llm4s/agent/graph/RunBudgets"                              -> (3  -> 4),
    "org/llm4s/agent/graph/RunConfig"                               -> (8  -> 10),
    "org/llm4s/agent/graph/RunEvent.RunFailed"                      -> (2  -> 3),
    "org/llm4s/agent/graph/RunEvent.RunCancelled"                   -> (1  -> 2),
    "org/llm4s/agent/graph/RunEvent.RunTimedOut"                    -> (1  -> 2),
    "org/llm4s/agent/graph/toolloop/TurnState"                      -> (5  -> 6),
    "org/llm4s/agent/harness/HarnessProfile"                        -> (12 -> 13),
    "org/llm4s/config/ProvidersConfigModel.NamedProviderConfig"     -> (8  -> 9),
    "org/llm4s/llmconnect/LlmClientOptions"                         -> (2  -> 3),
    "org/llm4s/llmconnect/model/Completion"                         -> (9  -> 12),
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
    "org/llm4s/llmconnect/config/OpenAIConfig"                      -> (9  -> 11),
    "org/llm4s/llmconnect/config/VertexAIConfig"                    -> (8  -> 10),
    "org/llm4s/llmconnect/config/ZaiConfig"                         -> (6  -> 8),
    "org/llm4s/llmconnect/provider/OpenAICompatibleClient.Settings" -> (8  -> 9),
    "org/llm4s/llmconnect/spi/ProviderConfigSpec"                   -> (8  -> 9),
    "org/llm4s/llmconnect/spi/ProviderFeatures"                     -> (2  -> 3),
    "org/llm4s/model/ModelCapabilities"                             -> (18 -> 23),
    "org/llm4s/model/ModelPricing"                                  -> (15 -> 16),
    "org/llm4s/rag/RAGConfig"                                       -> (25 -> 27),
    "org/llm4s/model/TransformationResult"                          -> (3  -> 4),
    "org/llm4s/reranker/RerankProviderConfig"                       -> (3  -> 4),
    "org/llm4s/toolapi/SafeParameterExtractor"                      -> (1  -> 2),
    "org/llm4s/toolapi/ToolRetryPolicy"                             -> (3  -> 4),
    "org/llm4s/trace/TraceEvent.AgentRunEnded"                      -> (7  -> 11),
    "org/llm4s/trace/TraceEvent.CompletionReceived"                 -> (5  -> 14),
    "org/llm4s/trace/TraceEvent.CustomEvent"                        -> (3  -> 4),
    "org/llm4s/trace/TraceEvent.EmbeddingUsageRecorded"             -> (5  -> 7),
    "org/llm4s/trace/TraceEvent.ErrorOccurred"                      -> (3  -> 10),
    "org/llm4s/trace/TraceEvent.TokenUsageRecorded"                 -> (4  -> 5),
    "org/llm4s/vectorstore/VectorStoreFactory.Config"               -> (4  -> 5)
  )

  /**
   * Each type's extractor arity in 0.6.0 and on main, where 0.6.0's differs from 0.5.1's: a pattern written against
   * 0.6.0 does not have 0.5.1's arity, so the two maps never both match.
   */
  val arity06: Map[String, (Int, Int)] = Map(
    "org/llm4s/llmconnect/model/Completion"     -> (10 -> 12),
    "org/llm4s/agent/events/ModelCallCompleted" -> (8  -> 10),
    "org/llm4s/trace/TraceEvent.ErrorOccurred"  -> (9  -> 10)
  )

  /** The number of fields a pattern of `size` arguments on `owner` lacks, if it has 0.5.1's or 0.6.0's arity. */
  def gained(owner: String, size: Int): Option[Int] =
    List(arity, arity06).flatMap(_.get(owner)).collectFirst { case (before, after) if before == size => after - before }
}
