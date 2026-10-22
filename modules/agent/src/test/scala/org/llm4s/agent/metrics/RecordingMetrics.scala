package org.llm4s.agent.metrics

import org.llm4s.metrics.{ Metric, MetricsCollector, Outcome }

import java.util.concurrent.{ ConcurrentHashMap, ConcurrentLinkedQueue }
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/** A collector that keeps every generic recording, for the agent's metrics specs. */
private[agent] class RecordingMetrics extends MetricsCollector:
  private val counters = new ConcurrentHashMap[(String, Vector[String]), Double]()
  private val timings  = new ConcurrentLinkedQueue[(String, Vector[String], FiniteDuration)]()
  private val gauges   = new ConcurrentHashMap[(String, Vector[String]), Double]()

  override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit = ()
  override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit           = ()
  override def recordCost(provider: String, model: String, costUsd: Double): Unit                                = ()

  override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit =
    require(labelValues.size == metric.labelNames.size, s"${metric.name}: $labelValues")
    counters.merge(metric.name -> labelValues.toVector, amount, (a, b) => a + b): Unit

  override def observe(metric: Metric.Timer, labelValues: Seq[String], duration: FiniteDuration): Unit =
    require(labelValues.size == metric.labelNames.size, s"${metric.name}: $labelValues")
    timings.add((metric.name, labelValues.toVector, duration)): Unit

  override def set(metric: Metric.Gauge, labelValues: Seq[String], value: Double): Unit =
    require(labelValues.size == metric.labelNames.size, s"${metric.name}: $labelValues")
    gauges.put(metric.name -> labelValues.toVector, value): Unit

  /** The counter's total for these label values; 0 if never incremented. */
  def count(metric: Metric.Counter, labels: String*): Double =
    Option(counters.get(metric.name -> labels.toVector)).getOrElse(0.0)

  /** Every counter series of `metric`, by label values. */
  def series(metric: Metric.Counter): Map[Vector[String], Double] =
    counters.asScala.collect { case ((name, labels), v) if name == metric.name => labels -> v }.toMap

  /** The durations recorded in the timer for these label values, in order. */
  def durations(metric: Metric.Timer, labels: String*): Vector[FiniteDuration] =
    timings.asScala.collect { case (name, ls, d) if name == metric.name && ls == labels.toVector => d }.toVector

  /** Every label set the timer was recorded with. */
  def timed(metric: Metric.Timer): Set[Vector[String]] =
    timings.asScala.collect { case (name, ls, _) if name == metric.name => ls }.toSet

  /** The gauge's latest value for these label values. */
  def gauge(metric: Metric.Gauge, labels: String*): Option[Double] =
    Option(gauges.get(metric.name -> labels.toVector))
