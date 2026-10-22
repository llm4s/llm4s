package org.llm4s.metrics

import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The label values a backend records for generic [[Metric]]s: what [[MetricsCollector.increment]] and its siblings
 * promise of every backend, in one place. A recording whose number of values is not its metric's number of labels is
 * refused (`None`); a null or blank value becomes `unknown`; and after `limit` distinct values of one label of one metric
 * each further value becomes `other`, logged once per label at WARN. A value already admitted stays admitted.
 * Thread-safe.
 */
final private[llm4s] class MetricLabels(limit: Int = MetricsCollector.MaxLabelValues) {
  require(limit > 0, s"limit must be positive, was $limit")

  private val logger = LoggerFactory.getLogger(classOf[MetricLabels])

  final private class Admitted {
    val values: java.util.Set[String] = ConcurrentHashMap.newKeySet[String]()
    val warned: AtomicBoolean         = new AtomicBoolean(false)
  }

  private val admitted = new ConcurrentHashMap[(String, Int), Admitted]()

  /** `values` as the backend records them for `metric`, or `None` when their number is not its number of labels. */
  def apply(metric: Metric, values: Seq[String]): Option[Vector[String]] =
    if (values == null || values.size != metric.labelNames.size) None
    else Some(values.iterator.zipWithIndex.map((v, i) => admit(metric, i, v)).toVector)

  private def admit(metric: Metric, index: Int, raw: String): String = {
    val value = if (raw == null || raw.isBlank) MetricLabels.Unknown else raw
    val seen  = admitted.computeIfAbsent(metric.name -> index, _ => new Admitted)
    if (seen.values.contains(value)) value
    else
      seen.synchronized {
        if (seen.values.contains(value) || (seen.values.size < limit && seen.values.add(value))) value
        else {
          if (seen.warned.compareAndSet(false, true))
            logger.warn(
              s"More than $limit distinct values of label '${metric.labelNames(index)}' of metric ${metric.name}; " +
                s"further values are recorded as '${MetricLabels.Other}'. Label values must come from a bounded set."
            )
          MetricLabels.Other
        }
      }
  }
}

private[llm4s] object MetricLabels {
  val Unknown: String = "unknown"
  val Other: String   = "other"
}
