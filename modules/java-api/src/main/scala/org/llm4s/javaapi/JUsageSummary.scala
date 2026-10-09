package org.llm4s.javaapi

import org.llm4s.llmconnect.model.{ ModelUsage, UsageSummary }

import java.util.Objects
import scala.jdk.CollectionConverters.*

/**
 * Token usage and estimated cost accumulated over an agent thread, as [[JAgentResult.usage]] reports
 * it: the totals across every model, and the same figures per model in [[byModel]].
 *
 * {{{
 * JUsageSummary u = result.usage();
 * System.out.println(u.inputTokens() + " in, " + u.outputTokens() + " out, $" + u.totalCost());
 * u.byModel().forEach((model, m) -> System.out.println(model + ": " + m.requestCount() + " calls"));
 * }}}
 *
 * A value: two are equal when every field is, a cost by its numeric value whatever its scale (`1.0`
 * equals `1.00`), as Scala's `BigDecimal` compares. [[toString]] writes the cost in plain notation.
 *
 * @param requestCount model calls, across every model
 * @param inputTokens prompt tokens sent, across every model
 * @param outputTokens completion tokens received, across every model
 * @param thinkingTokens extended-thinking tokens, across every model; zero where a provider reports none
 * @param totalCost estimated cost in USD, across every model; zero when no cost is known
 * @param byModel the same figures per model, keyed by model name and sorted by it; an unmodifiable map
 */
final class JUsageSummary private (
  val requestCount: Long,
  val inputTokens: Long,
  val outputTokens: Long,
  val thinkingTokens: Long,
  val totalCost: java.math.BigDecimal,
  val byModel: java.util.Map[String, JModelUsage]
) {

  private def fields: List[Any] =
    List(requestCount, inputTokens, outputTokens, thinkingTokens, totalCost.stripTrailingZeros, byModel)

  override def equals(other: Any): Boolean = other match {
    case that: JUsageSummary => fields == that.fields
    case _                   => false
  }

  override def hashCode: Int = Objects.hash(fields.map(_.asInstanceOf[AnyRef])*)

  override def toString: String =
    s"JUsageSummary($requestCount requests, $inputTokens in, $outputTokens out, $thinkingTokens thinking, ${totalCost.toPlainString} USD)"
}

object JUsageSummary {

  /** `usage` as Java and Kotlin callers read it. */
  private[javaapi] def of(usage: UsageSummary): JUsageSummary = {
    val byModel = new java.util.TreeMap[String, JModelUsage](usage.byModel.map((m, u) => m -> JModelUsage.of(u)).asJava)
    new JUsageSummary(
      usage.requestCount,
      usage.inputTokens,
      usage.outputTokens,
      usage.thinkingTokens,
      usage.totalCost.bigDecimal,
      java.util.Collections.unmodifiableMap(byModel)
    )
  }
}

/**
 * One model's share of a [[JUsageSummary]], as [[JUsageSummary.byModel]] maps it.
 *
 * A value: two are equal when every field is, the cost by its numeric value whatever its scale, as
 * for [[JUsageSummary]].
 *
 * @param requestCount calls to this model
 * @param inputTokens prompt tokens sent to this model
 * @param outputTokens completion tokens received from this model
 * @param thinkingTokens extended-thinking tokens from this model; zero where its provider reports none
 * @param totalCost estimated cost of this model's calls in USD; zero when no cost is known
 */
final class JModelUsage private (
  val requestCount: Long,
  val inputTokens: Long,
  val outputTokens: Long,
  val thinkingTokens: Long,
  val totalCost: java.math.BigDecimal
) {

  private def fields: List[Any] =
    List(requestCount, inputTokens, outputTokens, thinkingTokens, totalCost.stripTrailingZeros)

  override def equals(other: Any): Boolean = other match {
    case that: JModelUsage => fields == that.fields
    case _                 => false
  }

  override def hashCode: Int = Objects.hash(fields.map(_.asInstanceOf[AnyRef])*)

  override def toString: String =
    s"JModelUsage($requestCount requests, $inputTokens in, $outputTokens out, $thinkingTokens thinking, ${totalCost.toPlainString} USD)"
}

object JModelUsage {

  /** `usage` as Java and Kotlin callers read it. */
  private[javaapi] def of(usage: ModelUsage): JModelUsage =
    new JModelUsage(
      usage.requestCount,
      usage.inputTokens,
      usage.outputTokens,
      usage.thinkingTokens,
      usage.totalCost.bigDecimal
    )
}
