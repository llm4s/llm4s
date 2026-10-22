package org.llm4s.metrics

import org.llm4s.annotation.Experimental

import scala.concurrent.duration.FiniteDuration

/**
 * A metric a library module declares once and records through [[MetricsCollector.increment]],
 * [[MetricsCollector.observe]] and [[MetricsCollector.set]]: its name, what it measures, and the names of its labels, in
 * the order their values are passed. It is how a module above core (the agent runtime, say) reports metrics core knows
 * nothing about, while every [[MetricsCollector]] backend exports them the same way.
 *
 * The name is lower-case words separated by dots, such as `llm4s.agent.runs.started`, the Micrometer convention. A
 * Prometheus backend turns the dots into underscores and adds the unit suffix: a counter is exposed as
 * `llm4s_agent_runs_started_total`, a timer as `<name>_seconds`, a gauge under its name. So no name ends in `total`,
 * nor a timer's in `seconds`: the backend adds them. A gauge names its own unit, such as
 * `llm4s.agent.queue.oldest.seconds`.
 *
 * Label values must come from a bounded set - an outcome, a status, an agent id fixed by the code - and carry no
 * content. Never a thread, run, request or tenant id, a prompt or a message: each distinct value is a series kept for
 * the life of the process. A backend caps the distinct values of each label (see
 * [[MetricsCollector.MaxLabelValues]]) and records further values as `other`.
 *
 * Built by [[Metric.counter]], [[Metric.timer]] and [[Metric.gauge]], which throw `IllegalArgumentException` for an
 * invalid name, label or bucket: a metric is a constant of the code that declares it, so a bad one fails where it is
 * declared, at class initialisation, rather than at its first recording.
 */
@Experimental
sealed trait Metric {

  /** The metric's name: dot-separated lower-case words. */
  def name: String

  /** What it measures, for the backend's help or description text. */
  def help: String

  /** The names of its labels; each recording passes one value for each, in this order. */
  def labelNames: Vector[String]
}

@Experimental
object Metric {

  /** A count that only grows: [[MetricsCollector.increment]]. */
  final case class Counter private[Metric] (name: String, help: String, labelNames: Vector[String]) extends Metric

  /**
   * A distribution of durations: [[MetricsCollector.observe]]. `buckets` are the histogram's upper bounds, ascending, as
   * a Prometheus backend exposes them (`le`) and a Micrometer backend publishes them (service-level objectives).
   */
  final case class Timer private[Metric] (
    name: String,
    help: String,
    labelNames: Vector[String],
    buckets: Vector[FiniteDuration]
  ) extends Metric

  /** A value that goes up and down, set to its latest reading: [[MetricsCollector.set]]. */
  final case class Gauge private[Metric] (name: String, help: String, labelNames: Vector[String]) extends Metric

  private val NamePattern  = "[a-z][a-z0-9]*(\\.[a-z0-9]+)*".r
  private val LabelPattern = "[a-z][a-z0-9_]*".r
  private val Reserved     = Set("le", "quantile")

  /** `added` are the suffixes a Prometheus backend adds to the metric's kind, which its name must not repeat. */
  private def problems(name: String, help: String, labelNames: Seq[String], added: Seq[String]): List[String] = {
    val badName =
      if (name == null || !NamePattern.matches(name))
        Some(s"name must be dot-separated lower-case words, such as llm4s.agent.runs.started, was '$name'")
      else added.find(name.endsWith).map(s => s"name must not end in '$s': the backend adds it, was '$name'")
    val badHelp   = Option.when(help == null || help.isBlank)(s"help of $name must not be blank")
    val badLabels = labelNames.filter(l => l == null || !LabelPattern.matches(l) || Reserved(l))
    val repeated  = labelNames.groupBy(identity).collect { case (l, ls) if ls.size > 1 => l }
    List(
      badName,
      badHelp,
      Option.when(badLabels.nonEmpty)(
        s"label names of $name must be lower-case words joined by '_', and not ${Reserved.mkString(" or ")}: " +
          badLabels.mkString(", ")
      ),
      Option.when(repeated.nonEmpty)(s"label names of $name given twice: ${repeated.mkString(", ")}")
    ).flatten
  }

  private def bucketProblems(name: String, buckets: Seq[FiniteDuration]): List[String] =
    List(
      Option.when(buckets.isEmpty)(s"buckets of $name must not be empty"),
      Option.when(buckets.exists(_.length <= 0))(s"buckets of $name must be positive"),
      Option.when(buckets.sliding(2).exists(p => p.size == 2 && p(0) >= p(1)))(s"buckets of $name must ascend")
    ).flatten

  private def checked[M](found: List[String])(metric: => M): M = {
    require(found.isEmpty, found.mkString("; "))
    metric
  }

  /** A counter; throws `IllegalArgumentException` for an invalid name or label name. */
  def counter(name: String, help: String, labelNames: String*): Counter =
    checked(problems(name, help, labelNames, Vector(".total")))(Counter(name, help, labelNames.toVector))

  /** A timer with `buckets` as its upper bounds; throws `IllegalArgumentException` for an invalid one. */
  def timer(name: String, help: String, buckets: Seq[FiniteDuration], labelNames: String*): Timer =
    checked(problems(name, help, labelNames, Vector(".seconds", ".total")) ++ bucketProblems(name, buckets))(
      Timer(name, help, labelNames.toVector, buckets.toVector)
    )

  /** A gauge; throws `IllegalArgumentException` for an invalid name or label name. */
  def gauge(name: String, help: String, labelNames: String*): Gauge =
    checked(problems(name, help, labelNames, Vector(".total")))(Gauge(name, help, labelNames.toVector))

}
