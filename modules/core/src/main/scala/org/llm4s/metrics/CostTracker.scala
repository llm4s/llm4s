package org.llm4s.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{ DoubleAdder, LongAdder }

import org.llm4s.llmconnect.model.TokenUsage

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

trait CostTracker extends MetricsCollector {
  def record(model: String, usage: TokenUsage, cost: Option[Double]): Unit
  def totalCost: Double
  def totalTokens: Int
  def totalRequests: Int
  def byModel: Map[String, ModelCostSummary]
  def reset(): Unit
  def summary: String
}

case class ModelCostSummary(
  inputTokens: Long,
  outputTokens: Long,
  requestCount: Int,
  totalCostUsd: Double
)

object CostTracker {
  def create(): CostTracker = new InMemoryCostTracker()
  val noop: CostTracker     = new NoopCostTracker()
}

final private[metrics] class InMemoryCostTracker extends CostTracker {

  final private class ModelAccumulators {
    val inputTokens: LongAdder    = new LongAdder()
    val outputTokens: LongAdder   = new LongAdder()
    val requestCount: LongAdder   = new LongAdder()
    val totalCostUsd: DoubleAdder = new DoubleAdder()

    def snapshot: ModelCostSummary =
      ModelCostSummary(
        inputTokens = inputTokens.sum(),
        outputTokens = outputTokens.sum(),
        requestCount = requestCount.sum().toInt,
        totalCostUsd = totalCostUsd.sum()
      )
  }

  private val totalCostUsd: DoubleAdder    = new DoubleAdder()
  private val totalInputTokens: LongAdder  = new LongAdder()
  private val totalOutputTokens: LongAdder = new LongAdder()
  private val totalRequestCount: LongAdder = new LongAdder()

  private val perModel: ConcurrentHashMap[String, ModelAccumulators] = new ConcurrentHashMap()

  private def modelAccumulators(model: String): ModelAccumulators =
    perModel.computeIfAbsent(model, _ => new ModelAccumulators)

  private def incRequest(model: String): Unit = {
    totalRequestCount.add(1)
    modelAccumulators(model).requestCount.add(1)
  }

  private def incTokens(model: String, inputTokens: Long, outputTokens: Long): Unit = {
    totalInputTokens.add(inputTokens)
    totalOutputTokens.add(outputTokens)

    val acc = modelAccumulators(model)
    acc.inputTokens.add(inputTokens)
    acc.outputTokens.add(outputTokens)
  }

  private def incCost(model: String, costUsd: Double): Unit = {
    totalCostUsd.add(costUsd)
    modelAccumulators(model).totalCostUsd.add(costUsd)
  }

  override def record(model: String, usage: TokenUsage, cost: Option[Double]): Unit =
    try {
      incRequest(model)
      incTokens(
        model = model,
        inputTokens = usage.promptTokens.toLong,
        outputTokens = usage.completionTokens.toLong
      )
      cost.foreach(incCost(model, _))
    } catch {
      case _: Throwable => ()
    }

  override def observeRequest(
    provider: String,
    model: String,
    outcome: Outcome,
    duration: FiniteDuration
  ): Unit =
    try
      incRequest(model)
    catch {
      case _: Throwable => ()
    }

  override def addTokens(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long
  ): Unit =
    try
      incTokens(model, inputTokens, outputTokens)
    catch {
      case _: Throwable => ()
    }

  override def recordCost(
    provider: String,
    model: String,
    costUsd: Double
  ): Unit =
    try
      incCost(model, costUsd)
    catch {
      case _: Throwable => ()
    }

  override def totalCost: Double = totalCostUsd.sum()

  override def totalTokens: Int = {
    val total = totalInputTokens.sum() + totalOutputTokens.sum()
    if (total > Int.MaxValue) Int.MaxValue else total.toInt
  }

  override def totalRequests: Int = {
    val total = totalRequestCount.sum()
    if (total > Int.MaxValue) Int.MaxValue else total.toInt
  }

  override def byModel: Map[String, ModelCostSummary] =
    perModel.asScala.view.mapValues(_.snapshot).toMap

  override def reset(): Unit =
    try {
      totalCostUsd.reset()
      totalInputTokens.reset()
      totalOutputTokens.reset()
      totalRequestCount.reset()
      perModel.clear()
    } catch {
      case _: Throwable => ()
    }

  override def summary: String = {
    val models = byModel.toSeq.sortBy(_._1)

    val header =
      s"totalCostUsd=${totalCost} totalTokens=${totalTokens} totalRequests=${totalRequests}"

    if (models.isEmpty) header
    else {
      val perModelStr = models
        .map { case (model, s) =>
          s"$model: requests=${s.requestCount} inputTokens=${s.inputTokens} outputTokens=${s.outputTokens} costUsd=${s.totalCostUsd}"
        }
        .mkString("\n")
      s"$header\n$perModelStr"
    }
  }
}

final private[metrics] class NoopCostTracker extends CostTracker {
  override def record(model: String, usage: TokenUsage, cost: Option[Double]): Unit = ()

  override def observeRequest(
    provider: String,
    model: String,
    outcome: Outcome,
    duration: FiniteDuration
  ): Unit = ()

  override def addTokens(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long
  ): Unit = ()

  override def recordCost(
    provider: String,
    model: String,
    costUsd: Double
  ): Unit = ()

  override def totalCost: Double                      = 0.0
  override def totalTokens: Int                       = 0
  override def totalRequests: Int                     = 0
  override def byModel: Map[String, ModelCostSummary] = Map.empty
  override def reset(): Unit                          = ()
  override def summary: String                        = "totalCostUsd=0.0 totalTokens=0 totalRequests=0"
}
