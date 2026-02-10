package org.llm4s.agent

import org.llm4s.llmconnect.model.TokenUsage
import upickle.default.{ ReadWriter => RW, macroRW }

case class ModelUsage(
  requestCount: Long = 0L,
  inputTokens: Long = 0L,
  outputTokens: Long = 0L,
  thinkingTokens: Long = 0L,
  totalCost: Double = 0.0
) {
  def add(usage: TokenUsage, cost: Option[Double]): ModelUsage = {
    val thinking = usage.thinkingTokens.getOrElse(0)
    copy(
      requestCount = requestCount + 1L,
      inputTokens = inputTokens + usage.promptTokens.toLong,
      outputTokens = outputTokens + usage.completionTokens.toLong,
      thinkingTokens = thinkingTokens + thinking.toLong,
      totalCost = totalCost + cost.getOrElse(0.0)
    )
  }

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

case class UsageSummary(
  requestCount: Long = 0L,
  inputTokens: Long = 0L,
  outputTokens: Long = 0L,
  thinkingTokens: Long = 0L,
  totalCost: Double = 0.0,
  byModel: Map[String, ModelUsage] = Map.empty
) {

  def add(model: String, usage: TokenUsage, cost: Option[Double]): UsageSummary = {
    val thinking = usage.thinkingTokens.getOrElse(0)

    val updatedModelUsage =
      byModel.getOrElse(model, ModelUsage()).add(usage, cost)

    copy(
      requestCount = requestCount + 1L,
      inputTokens = inputTokens + usage.promptTokens.toLong,
      outputTokens = outputTokens + usage.completionTokens.toLong,
      thinkingTokens = thinkingTokens + thinking.toLong,
      totalCost = totalCost + cost.getOrElse(0.0),
      byModel = byModel.updated(model, updatedModelUsage)
    )
  }

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
  implicit val rw: RW[UsageSummary] = macroRW
}
