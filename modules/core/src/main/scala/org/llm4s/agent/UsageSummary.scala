package org.llm4s.agent

import org.llm4s.llmconnect.model.TokenUsage
import upickle.default.{ ReadWriter => RW, macroRW, readwriter }

/**
 * Accumulates token and cost statistics for a single model across one or more requests.
 *
 * `totalCost` is stored as [[BigDecimal]] to avoid floating-point accumulation drift
 * that would occur when summing many `Double` values; incoming `Option[Double]` costs
 * are converted once via [[BigDecimal.decimal]] on first contact.
 *
 * @param requestCount   number of API calls attributed to this model
 * @param inputTokens    cumulative prompt tokens sent to the model
 * @param outputTokens   cumulative completion tokens received from the model
 * @param thinkingTokens cumulative extended-thinking tokens (Anthropic only; zero for other providers)
 * @param totalCost      cumulative estimated cost in USD; `BigDecimal(0)` when cost data is unavailable
 */
case class ModelUsage(
  requestCount: Long = 0L,
  inputTokens: Long = 0L,
  outputTokens: Long = 0L,
  thinkingTokens: Long = 0L,
  totalCost: BigDecimal = BigDecimal(0)
) {

  /**
   * Returns a new [[ModelUsage]] with `usage` and `cost` folded in.
   *
   * @param usage token-usage record from a [[org.llm4s.llmconnect.model.Completion]]
   * @param cost  optional estimated cost in USD; treated as zero when absent
   */
  def add(usage: TokenUsage, cost: Option[Double]): ModelUsage = {
    val thinking  = usage.thinkingTokens.getOrElse(0)
    val costValue = cost.map(BigDecimal.decimal).getOrElse(BigDecimal(0))
    copy(
      requestCount = requestCount + 1L,
      inputTokens = inputTokens + usage.promptTokens.toLong,
      outputTokens = outputTokens + usage.completionTokens.toLong,
      thinkingTokens = thinkingTokens + thinking.toLong,
      totalCost = totalCost + costValue
    )
  }

  /**
   * Combines two [[ModelUsage]] records by summing every field.
   *
   * Useful when aggregating per-model statistics from parallel agent runs or
   * multi-step pipelines where each step produces its own [[ModelUsage]].
   */
  def merge(other: ModelUsage): ModelUsage =
    ModelUsage(
      requestCount = requestCount + other.requestCount,
      inputTokens = inputTokens + other.inputTokens,
      outputTokens = outputTokens + other.outputTokens,
      thinkingTokens = thinkingTokens + other.thinkingTokens,
      totalCost = totalCost + other.totalCost
    )
}

object ModelUsage {
  implicit val rw: RW[ModelUsage] = macroRW
}

/**
 * Aggregates token and cost statistics across all models used in an agent run.
 *
 * The top-level fields (`requestCount`, `inputTokens`, etc.) are roll-ups across
 * every model.  The `byModel` map breaks the same figures down per model name
 * (e.g. `"claude-sonnet-4-5-latest"`, `"gpt-4o"`), allowing cost attribution
 * per provider in multi-model pipelines.
 *
 * `totalCost` uses [[BigDecimal]] rather than `Double` to keep cumulative sums
 * deterministic regardless of the number of requests.
 *
 * @param requestCount   total API calls across all models
 * @param inputTokens    total prompt tokens sent across all models
 * @param outputTokens   total completion tokens received across all models
 * @param thinkingTokens total extended-thinking tokens (non-zero only for Anthropic models)
 * @param totalCost      total estimated cost in USD; `BigDecimal(0)` when unavailable
 * @param byModel        per-model breakdown; keyed by the model identifier string
 *
 * @see [[ModelUsage]] for the per-model record type
 * @see [[org.llm4s.agent.AgentState]] which carries a [[UsageSummary]] for the whole run
 */
case class UsageSummary(
  requestCount: Long = 0L,
  inputTokens: Long = 0L,
  outputTokens: Long = 0L,
  thinkingTokens: Long = 0L,
  totalCost: BigDecimal = BigDecimal(0),
  byModel: Map[String, ModelUsage] = Map.empty
) {

  /**
   * Returns a new [[UsageSummary]] with the given usage record folded in.
   *
   * Creates a new [[ModelUsage]] entry for `model` if one does not yet exist.
   *
   * @param model model identifier string (e.g. `"gpt-4o"`)
   * @param usage token-usage record from a [[org.llm4s.llmconnect.model.Completion]]
   * @param cost  optional estimated cost in USD; treated as zero when absent
   */
  def add(model: String, usage: TokenUsage, cost: Option[Double]): UsageSummary = {
    val thinking = usage.thinkingTokens.getOrElse(0)

    // NOTE:
    // Completion.estimatedCost originates as Option[Double] at provider level.
    // We convert using BigDecimal.decimal to prevent accumulation drift.
    // Any upstream floating precision loss cannot be recovered here,
    // but BigDecimal ensures deterministic aggregation from this point forward.
    val costValue = cost.map(BigDecimal.decimal).getOrElse(BigDecimal(0))

    val updatedModelUsage =
      byModel.getOrElse(model, ModelUsage()).add(usage, cost)

    copy(
      requestCount = requestCount + 1L,
      inputTokens = inputTokens + usage.promptTokens.toLong,
      outputTokens = outputTokens + usage.completionTokens.toLong,
      thinkingTokens = thinkingTokens + thinking.toLong,
      totalCost = totalCost + costValue,
      byModel = byModel.updated(model, updatedModelUsage)
    )
  }

  /**
   * Combines two [[UsageSummary]] records by summing every field and merging
   * the per-model maps.
   *
   * Use this when aggregating results from parallel sub-agents or separate
   * pipeline stages into a single top-level summary.
   */
  def merge(other: UsageSummary): UsageSummary = {
    val mergedByModel = other.byModel.foldLeft(byModel) { case (acc, (model, usage)) =>
      val merged = acc.getOrElse(model, ModelUsage()).merge(usage)
      acc.updated(model, merged)
    }

    UsageSummary(
      requestCount = requestCount + other.requestCount,
      inputTokens = inputTokens + other.inputTokens,
      outputTokens = outputTokens + other.outputTokens,
      thinkingTokens = thinkingTokens + other.thinkingTokens,
      totalCost = totalCost + other.totalCost,
      byModel = mergedByModel
    )
  }
}

object UsageSummary {
  implicit val bigDecimalRw: RW[BigDecimal] = readwriter[ujson.Value].bimap[BigDecimal](
    bd => ujson.Str(bd.toString),
    {
      case ujson.Num(n) => BigDecimal.decimal(n)
      case ujson.Str(s) => BigDecimal(s)
      case _            => BigDecimal(0)
    }
  )

  implicit val rw: RW[UsageSummary] = macroRW
}
