package org.llm4s.metrics

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

import org.llm4s.agent.{ ModelUsage, UsageSummary }

/**
 * In-process cost and usage tracker that implements [[MetricsCollector]].
 *
 * Accumulates per-model request counts, token usage, and cost data in memory
 * with lock-free thread safety via `AtomicReference` + CAS. Provides query
 * methods for retrieving aggregated statistics without external infrastructure.
 *
 * Cost is stored as [[BigDecimal]] to prevent floating-point accumulation drift,
 * matching the pattern used by [[UsageSummary]] and [[ModelUsage]].
 *
 * Example usage:
 * {{{
 * val tracker = CostTracker.create()
 * val client = LLMConnect.getClient(config, tracker)
 * // ... make LLM calls ...
 * println(s"Total cost: $$${tracker.totalCost}")
 * println(tracker.summary)
 * }}}
 */
final class CostTracker private () extends MetricsCollector {

  private case class ModelAccumulator(
    requestCount: Long = 0L,
    inputTokens: Long = 0L,
    outputTokens: Long = 0L,
    totalCost: BigDecimal = BigDecimal(0)
  )

  private val state: AtomicReference[Map[String, ModelAccumulator]] =
    new AtomicReference(Map.empty)

  @tailrec
  private def update(model: String)(f: ModelAccumulator => ModelAccumulator): Unit = {
    val current  = state.get()
    val existing = current.getOrElse(model, ModelAccumulator())
    val updated  = current.updated(model, f(existing))
    if (!state.compareAndSet(current, updated)) update(model)(f)
  }

  override def observeRequest(
    provider: String,
    model: String,
    outcome: Outcome,
    duration: FiniteDuration
  ): Unit =
    update(model)(a => a.copy(requestCount = a.requestCount + 1L))

  override def addTokens(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long
  ): Unit =
    update(model) { a =>
      a.copy(
        inputTokens = a.inputTokens + inputTokens,
        outputTokens = a.outputTokens + outputTokens
      )
    }

  override def recordCost(
    provider: String,
    model: String,
    costUsd: Double
  ): Unit =
    update(model)(a => a.copy(totalCost = a.totalCost + BigDecimal.decimal(costUsd)))

  /** Total estimated cost in USD across all models. */
  def totalCost: BigDecimal =
    state.get().values.foldLeft(BigDecimal(0))(_ + _.totalCost)

  /** Total tokens (input + output) across all models. */
  def totalTokens: Long = totalInputTokens + totalOutputTokens

  /** Total input/prompt tokens across all models. */
  def totalInputTokens: Long =
    state.get().values.foldLeft(0L)(_ + _.inputTokens)

  /** Total output/completion tokens across all models. */
  def totalOutputTokens: Long =
    state.get().values.foldLeft(0L)(_ + _.outputTokens)

  /** Total number of requests across all models. */
  def totalRequests: Long =
    state.get().values.foldLeft(0L)(_ + _.requestCount)

  /**
   * Per-model usage breakdown.
   *
   * Note: `thinkingTokens` is always 0 because [[MetricsCollector.addTokens]]
   * does not pass thinking token counts.
   */
  def byModel: Map[String, ModelUsage] =
    state.get().map { case (model, acc) =>
      model -> ModelUsage(
        requestCount = acc.requestCount,
        inputTokens = acc.inputTokens,
        outputTokens = acc.outputTokens,
        thinkingTokens = 0L,
        totalCost = acc.totalCost
      )
    }

  /** Returns a [[UsageSummary]] for interop with agent-level tracking. */
  def snapshot: UsageSummary = {
    val current = state.get()
    val models = current.map { case (model, acc) =>
      model -> ModelUsage(
        requestCount = acc.requestCount,
        inputTokens = acc.inputTokens,
        outputTokens = acc.outputTokens,
        thinkingTokens = 0L,
        totalCost = acc.totalCost
      )
    }
    UsageSummary(
      requestCount = current.values.foldLeft(0L)(_ + _.requestCount),
      inputTokens = current.values.foldLeft(0L)(_ + _.inputTokens),
      outputTokens = current.values.foldLeft(0L)(_ + _.outputTokens),
      thinkingTokens = 0L,
      totalCost = current.values.foldLeft(BigDecimal(0))(_ + _.totalCost),
      byModel = models
    )
  }

  /** Human-readable summary string. */
  def summary: String = {
    val current  = state.get()
    val reqCount = current.values.foldLeft(0L)(_ + _.requestCount)
    val tokenCount = current.values.foldLeft(0L)(_ + _.inputTokens) +
      current.values.foldLeft(0L)(_ + _.outputTokens)
    val cost = current.values
      .foldLeft(BigDecimal(0))(_ + _.totalCost)
      .setScale(6, BigDecimal.RoundingMode.HALF_UP)
    val modelLines = current.toSeq.sortBy(_._1).map { case (model, acc) =>
      val mc = acc.totalCost.setScale(6, BigDecimal.RoundingMode.HALF_UP)
      s"  $model: ${acc.requestCount} requests, ${acc.inputTokens + acc.outputTokens} tokens, $$$mc"
    }
    val header =
      s"CostTracker: $reqCount requests, $tokenCount tokens, $$$cost"
    if (modelLines.isEmpty) header
    else (header +: modelLines).mkString("\n")
  }

  /** Reset all accumulated data. */
  def reset(): Unit = state.set(Map.empty)
}

object CostTracker {

  /** Create a new empty [[CostTracker]]. */
  def create(): CostTracker = new CostTracker()
}
